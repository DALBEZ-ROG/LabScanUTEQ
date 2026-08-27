package ec.edu.uteq.labscan.ui.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import ec.edu.uteq.labscan.ui.theme.UteqGreen
import ec.edu.uteq.labscan.ui.theme.UteqGreenLight
import ec.edu.uteq.labscan.ui.theme.WarningAmber
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Forma organica que dice, sin texto, que esta haciendo la app.
 *
 * En una pantalla que se usa sin tocarla y a veces sin mirarla de cerca, la forma es la
 * realimentacion principal: el estudiante tiene que poder saber de un vistazo si le toca
 * hablar o esperar. Por eso cada estado tiene un movimiento **cualitativamente** distinto y
 * no solo un color distinto:
 *
 * | Estado | Movimiento | Que comunica |
 * |---|---|---|
 * | `Connecting` | quieta y tenue | todavia no |
 * | `Listening` | pulso lento y regular | puede hablar |
 * | `Capturing` | se deforma con la voz | le estoy oyendo |
 * | `Thinking` | giro lento | espere |
 * | `Speaking` | ondas hacia afuera | estoy hablando |
 * | `Error` | quieta y ambar | algo pasa, mire |
 *
 * Todo es `Canvas` de Compose con `animateFloatAsState` e `infiniteRepeatable`: ni Lottie ni
 * GIF, como pide F8.
 *
 * @param amplitude volumen del microfono `0f..1f`. Solo se usa en `Capturing`.
 */
@Composable
fun VoiceOrb(
    state: VoiceState,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "orbe")

    // Fase base, siempre girando. De ella cuelgan la ondulacion del contorno y el giro de
    // "pensando", para que las dos vayan sincronizadas y no se vea un batido raro.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fase"
    )

    // Respiracion del estado de escucha. Lenta a proposito: rapida transmitiria urgencia.
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "respiracion"
    )

    // Ondas de "hablando". Van desfasadas entre si para que salgan una detras de otra.
    val ripple by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ondas"
    )

    // El tamano cambia entre estados con una animacion propia, no de golpe: el salto seco
    // entre "escuchando" y "pensando" se lee como un parpadeo y distrae.
    val targetScale = when (state) {
        VoiceState.Connecting -> 0.72f
        VoiceState.Listening -> 0.86f + breath * 0.06f
        VoiceState.Capturing -> 0.88f + amplitude.coerceIn(0f, 1f) * 0.22f
        VoiceState.Thinking -> 0.82f
        VoiceState.Speaking -> 0.94f
        is VoiceState.Error -> 0.76f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 220),
        label = "escala"
    )

    // Cuanto se deforma el contorno. En captura manda la voz; en el resto es un rizo suave
    // que basta para que la forma no parezca un circulo muerto.
    val targetWobble = when (state) {
        VoiceState.Capturing -> 0.10f + amplitude.coerceIn(0f, 1f) * 0.16f
        VoiceState.Speaking -> 0.09f
        VoiceState.Listening -> 0.05f
        else -> 0.03f
    }
    val wobble by animateFloatAsState(
        targetValue = targetWobble,
        animationSpec = tween(durationMillis = 160),
        label = "deformacion"
    )

    val color = when (state) {
        is VoiceState.Error -> WarningAmber
        VoiceState.Capturing -> UteqGreenLight
        else -> UteqGreen
    }

    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = minOf(size.width, size.height) / 2f * 0.62f * scale

        if (state is VoiceState.Speaking) {
            drawRipples(center, baseRadius, ripple, color)
        }

        if (state is VoiceState.Thinking) {
            // El giro se aplica solo al arco: la forma de debajo sigue quieta, y el contraste
            // entre las dos es lo que se lee como "esta trabajando".
            rotate(degrees = phase * 180f / PI.toFloat(), pivot = center) {
                drawThinkingArc(center, baseRadius * 1.28f, color)
            }
        }

        drawOrganicBlob(
            path = path,
            center = center,
            baseRadius = baseRadius,
            wobble = wobble,
            phase = phase,
            color = color
        )
    }
}

/**
 * Dibuja el contorno ondulado.
 *
 * El radio de cada punto es el radio base mas dos senos de frecuencias distintas y no
 * multiplos entre si ([LOBES_SLOW] y [LOBES_FAST]). Es lo que evita que la figura parezca un
 * poligono girando: al no cerrar el ciclo a la vez, el contorno nunca repite exactamente la
 * misma forma.
 *
 * Se une con curvas cuadraticas por el punto medio de cada par, que es la forma barata de
 * conseguir un contorno sin esquinas a partir de puntos sueltos.
 */
private fun DrawScope.drawOrganicBlob(
    path: Path,
    center: Offset,
    baseRadius: Float,
    wobble: Float,
    phase: Float,
    color: Color
) {
    path.reset()

    val points = ArrayList<Offset>(BLOB_POINTS)
    for (index in 0 until BLOB_POINTS) {
        val angle = 2f * PI.toFloat() * index / BLOB_POINTS
        val deform = sin(angle * LOBES_SLOW + phase) * 0.6f +
            sin(angle * LOBES_FAST - phase * 1.4f) * 0.4f
        val radius = baseRadius * (1f + wobble * deform)
        points.add(
            Offset(
                x = center.x + radius * cos(angle),
                y = center.y + radius * sin(angle)
            )
        )
    }

    // Se arranca en el punto medio del ultimo par para que el cierre del contorno sea tan
    // suave como el resto y no se note la costura.
    var previous = points.last()
    path.moveTo((previous.x + points[0].x) / 2f, (previous.y + points[0].y) / 2f)
    for (index in points.indices) {
        val current = points[index]
        val next = points[(index + 1) % points.size]
        path.quadraticTo(
            current.x,
            current.y,
            (current.x + next.x) / 2f,
            (current.y + next.y) / 2f
        )
        previous = current
    }
    path.close()

    drawPath(
        path = path,
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.12f)),
            center = center,
            radius = baseRadius * 1.4f
        )
    )
    drawPath(
        path = path,
        color = color.copy(alpha = 0.9f),
        style = Stroke(width = 3f)
    )
}

/** Tres ondas saliendo del centro, desfasadas, que se desvanecen al crecer. */
private fun DrawScope.drawRipples(
    center: Offset,
    baseRadius: Float,
    progress: Float,
    color: Color
) {
    repeat(RIPPLE_COUNT) { index ->
        // El modulo es lo que hace que cada onda arranque cuando la anterior va por su tercio.
        val offset = (progress + index.toFloat() / RIPPLE_COUNT) % 1f
        val radius = baseRadius * (1f + offset * 0.85f)
        drawCircle(
            color = color.copy(alpha = 0.35f * (1f - offset)),
            radius = radius,
            center = center,
            style = Stroke(width = 4f * (1f - offset) + 1f)
        )
    }
}

/** Arco incompleto que gira. La abertura es lo que hace visible el giro. */
private fun DrawScope.drawThinkingArc(center: Offset, radius: Float, color: Color) {
    drawArc(
        color = color.copy(alpha = 0.7f),
        startAngle = 0f,
        sweepAngle = 110f,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
        style = Stroke(width = 5f)
    )
}

/** Puntos del contorno. Suficientes para que las curvas se vean lisas, pocos para ser baratos. */
private const val BLOB_POINTS = 24

/** Lobulos lentos y rapidos. Primos entre si a proposito: ver [drawOrganicBlob]. */
private const val LOBES_SLOW = 3f
private const val LOBES_FAST = 5f

private const val RIPPLE_COUNT = 3
