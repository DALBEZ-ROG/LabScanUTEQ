package ec.edu.uteq.labscan.ui.scanner

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ec.edu.uteq.labscan.BuildConfig
import ec.edu.uteq.labscan.R
import ec.edu.uteq.labscan.detection.Detection
import ec.edu.uteq.labscan.ui.theme.SelectionAmber
import ec.edu.uteq.labscan.ui.theme.UteqGreenLight
import kotlin.math.roundToInt

/**
 * Capa que dibuja las cajas sobre la vista previa.
 *
 * No calcula nada: pide la matriz a [BoxMapper] y la aplica. Toda la matematica vive alli.
 *
 * ### Por que los parametros son lambdas
 *
 * Las detecciones cambian unas 30 veces por segundo. Si se recibieran como valores, cada
 * frame provocaria una recomposicion de este composable. Recibiendolas como lambdas que se
 * invocan **dentro** del bloque de dibujo, la lectura del estado se aplaza a la fase de
 * dibujo: Compose invalida el dibujo, no la composicion, y el resultado es fluido.
 */
@Composable
fun DetectionOverlay(
    detections: () -> List<Detection>,
    geometry: () -> FrameGeometry?,
    selected: () -> Detection?,
    modelInputSize: Int,
    onDetectionTapped: (Detection?) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    // Suavizado del movimiento de las cajas. El detector entrega unas 9 listas por segundo y
    // la pantalla refresca a 60 o mas: sin esto la caja da tirones y tiembla. Ver BoxSmoother.
    val smoother = remember { BoxSmoother() }
    // El fotograma actual se lee DENTRO del bloque de dibujo, asi que invalida solo el dibujo,
    // nunca la composicion. Es el mismo motivo por el que los parametros son lambdas.
    var frameNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameNanos = it }
        }
    }
    DisposableEffect(modelInputSize) {
        // Cambiar de camara cambia la geometria: las posiciones dibujadas ya no significan
        // nada y animar hacia las nuevas se veria como cajas cruzando la pantalla.
        smoother.reset()
        onDispose { smoother.reset() }
    }
    // El formato se resuelve en composicion: acceder a recursos en la fase de dibujo no es
    // sensible a los cambios de configuracion.
    val labelFormat = stringResource(R.string.scanner_etiqueta_deteccion)

    val tapModifier = modifier.pointerInput(modelInputSize) {
        detectTapGestures { offset ->
            val frame = geometry() ?: return@detectTapGestures
            val matrix = BoxMapper.build(
                modelInputSize = modelInputSize,
                analysisWidth = frame.width,
                analysisHeight = frame.height,
                rotationDegrees = frame.rotationDegrees,
                isFrontCamera = frame.isFrontCamera,
                viewWidth = size.width.toFloat(),
                viewHeight = size.height.toFloat()
            )
            // La matriz se recalcula aqui en lugar de compartir estado con la fase de
            // dibujo: son unas pocas multiplicaciones y ocurre solo al tocar, mientras que
            // compartir un estado mutable entre el gesto y el dibujo es una carrera segura.
            val rect = RectF()
            val hit = detections()
                .filter { detection ->
                    rect.set(detection.box)
                    matrix.mapRect(rect)
                    rect.contains(offset.x, offset.y)
                }
                // Con cajas solapadas gana la de mayor confianza.
                .maxByOrNull { it.score }
            // hit puede ser null: un toque fuera de toda caja cierra la ficha.
            onDetectionTapped(hit)
        }
    }

    Canvas(modifier = tapModifier) {
        // Guias de calibracion, solo en depuracion: una cruz en el centro exacto de la
        // vista y marcas al 25 % y al 75 % de cada eje. Con la caja de calibracion del
        // StubDetector, su centro debe caer sobre la cruz en vertical, en horizontal y con
        // la camara frontal. Es la forma de comprobar a ojo, sin regla, los tres criterios
        // de aceptacion de F2.
        if (BuildConfig.DEBUG) drawCenterGuides()

        val frame = geometry() ?: return@Canvas
        // Leer frameNanos aqui es lo que hace que el Canvas se redibuje en cada fotograma
        // mientras haya cajas, que es la condicion para que la interpolacion se vea.
        val visible = smoother.smooth(detections(), frameNanos)
        if (visible.isEmpty()) return@Canvas

        val matrix = BoxMapper.build(
            modelInputSize = modelInputSize,
            analysisWidth = frame.width,
            analysisHeight = frame.height,
            rotationDegrees = frame.rotationDegrees,
            isFrontCamera = frame.isFrontCamera,
            viewWidth = size.width,
            viewHeight = size.height
        )

        val highlighted = selected()
        val mapped = RectF()
        visible.forEach { detection ->
            mapped.set(detection.box)
            // mapRect devuelve el rectangulo envolvente ya normalizado, asi que resuelve
            // solo el hecho de que un giro de 90 grados intercambia izquierda y derecha.
            matrix.mapRect(mapped)
            drawDetection(
                detection = detection,
                box = mapped,
                textMeasurer = textMeasurer,
                labelFormat = labelFormat,
                isSelected = highlighted === detection,
                dimmed = highlighted != null && highlighted !== detection
            )
        }
    }
}

/**
 * @param isSelected la caja que el estudiante toco: borde mas grueso y color de seleccion.
 * @param dimmed hay otra caja seleccionada, asi que esta se atenua para no competir con ella.
 */
private fun DrawScope.drawDetection(
    detection: Detection,
    box: RectF,
    textMeasurer: TextMeasurer,
    labelFormat: String,
    isSelected: Boolean,
    dimmed: Boolean
) {
    val strokeWidth = if (isSelected) SELECTED_STROKE_DP.dp.toPx() else STROKE_DP.dp.toPx()
    val corner = CornerRadius(8.dp.toPx())
    val alpha = if (dimmed) DIMMED_ALPHA else 1f
    val boxColor = if (isSelected) SelectionAmber else UteqGreenLight

    drawRoundRect(
        color = boxColor.copy(alpha = alpha),
        topLeft = Offset(box.left, box.top),
        size = Size(box.width(), box.height()),
        cornerRadius = corner,
        style = Stroke(width = strokeWidth)
    )

    // Velo suave dentro de la caja elegida, para que se distinga de un vistazo incluso
    // cuando el fondo tiene el mismo color que el borde.
    if (isSelected) {
        drawRoundRect(
            color = boxColor.copy(alpha = 0.15f),
            topLeft = Offset(box.left, box.top),
            size = Size(box.width(), box.height()),
            cornerRadius = corner
        )
    }

    // Se muestra el classId legible. La ficha, que es donde el estudiante lee de verdad, ya
    // usa el displayName del catalogo; en la caja no cabe un nombre largo.
    val name = detection.classId.replace('_', ' ')
    val percent = (detection.score * 100f).roundToInt()
    val layout = textMeasurer.measure(
        text = AnnotatedString(labelFormat.format(name, percent)),
        style = TextStyle(
            color = Color.White.copy(alpha = alpha),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    )

    val paddingX = 6.dp.toPx()
    val paddingY = 3.dp.toPx()
    val labelWidth = layout.size.width + paddingX * 2
    val labelHeight = layout.size.height + paddingY * 2

    // La etiqueta va sobre el borde superior. Si no cabe arriba, porque la caja toca el
    // techo de la pantalla, se dibuja por dentro para que no quede cortada.
    val fitsAbove = box.top - labelHeight >= 0f
    val labelTop = if (fitsAbove) box.top - labelHeight else box.top
    // Tampoco puede salirse por la derecha.
    val labelLeft = box.left.coerceIn(0f, (size.width - labelWidth).coerceAtLeast(0f))

    drawRoundRect(
        color = Color.Black.copy(alpha = 0.65f * alpha),
        topLeft = Offset(labelLeft, labelTop),
        size = Size(labelWidth, labelHeight),
        cornerRadius = CornerRadius(4.dp.toPx())
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(labelLeft + paddingX, labelTop + paddingY)
    )
}

/**
 * Cruz fina en el centro exacto del `Canvas`, mas dos marcas al 25 % y al 75 % de cada eje.
 * Solo en depuracion: es el patron contra el que se verifica el centrado de las cajas.
 */
private fun DrawScope.drawCenterGuides() {
    val guide = Color.White.copy(alpha = 0.35f)
    val thickness = 1.dp.toPx()
    val arm = 24.dp.toPx()
    val centerX = size.width / 2f
    val centerY = size.height / 2f

    drawLine(guide, Offset(centerX - arm, centerY), Offset(centerX + arm, centerY), thickness)
    drawLine(guide, Offset(centerX, centerY - arm), Offset(centerX, centerY + arm), thickness)

    val tick = 10.dp.toPx()
    listOf(0.25f, 0.75f).forEach { fraction ->
        val y = size.height * fraction
        drawLine(guide, Offset(0f, y), Offset(tick, y), thickness)
        drawLine(guide, Offset(size.width - tick, y), Offset(size.width, y), thickness)
        val x = size.width * fraction
        drawLine(guide, Offset(x, 0f), Offset(x, tick), thickness)
        drawLine(guide, Offset(x, size.height - tick), Offset(x, size.height), thickness)
    }
}

/** Grosor del borde de una caja normal, en dp. */
private const val STROKE_DP = 3

/** Grosor del borde de la caja seleccionada. Se nota sin llegar a tapar el objeto. */
private const val SELECTED_STROKE_DP = 5

/** Opacidad de las cajas no elegidas mientras hay una seleccion. */
private const val DIMMED_ALPHA = 0.35f
