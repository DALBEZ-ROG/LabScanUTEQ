package ec.edu.uteq.labscan.detection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Parametros del letterbox.
 *
 * Son deterministas: dependen solo del tamano del frame y del lado del modelo. Por eso
 * [ec.edu.uteq.labscan.ui.scanner.BoxMapper] puede recalcularlos por su cuenta en lugar de
 * arrastrarlos por toda la app, y por eso [Detector] no necesita devolverlos.
 *
 * @param scale factor unico aplicado a los dos ejes, sin deformar.
 * @param padX pixeles de relleno a cada lado, en el cuadrado del modelo.
 * @param padY pixeles de relleno arriba y abajo, en el cuadrado del modelo.
 */
data class LetterboxParams(
    val scale: Float,
    val padX: Float,
    val padY: Float
)

/** Gris con el que Ultralytics rellena las bandas. Cambiarlo desplaza la distribucion. */
private const val PAD_GRAY = 114

/**
 * Calcula el letterbox sin tocar pixeles.
 *
 * Es la unica definicion del letterbox en el proyecto. La usan tanto quien redimensiona la
 * imagen como quien invierte el mapeo, para que no puedan discrepar.
 */
fun letterboxParams(sourceWidth: Int, sourceHeight: Int, inputSize: Int): LetterboxParams {
    require(sourceWidth > 0 && sourceHeight > 0) { "Frame vacio: ${sourceWidth}x$sourceHeight" }
    require(inputSize > 0) { "inputSize invalido: $inputSize" }

    // Un solo factor para los dos ejes: es lo que preserva el aspecto.
    val scale = min(inputSize.toFloat() / sourceWidth, inputSize.toFloat() / sourceHeight)
    // El sobrante se reparte a partes iguales, asi que la imagen queda centrada.
    return LetterboxParams(
        scale = scale,
        padX = (inputSize - sourceWidth * scale) / 2f,
        padY = (inputSize - sourceHeight * scale) / 2f
    )
}

/**
 * Aplica el letterbox reutilizando siempre el mismo bitmap de destino.
 *
 * Existe por una restriccion dura del bucle de inferencia: **nada se asigna por frame**. Un
 * `Bitmap.createBitmap` de 640x640 son 1,6 MB; hacerlo 30 veces por segundo dispara el
 * recolector de basura y se lleva por delante los FPS. Aqui el bitmap, el `Canvas`, el
 * `Paint` y el `Rect` se crean una vez y se reescriben.
 *
 * No es seguro entre hilos. Cada instancia pertenece a un solo detector, y el detector solo
 * se usa desde el hilo de analisis.
 */
class LetterboxScaler(private val inputSize: Int) {

    /** Bitmap de destino. Su contenido cambia en cada llamada a [scale]. */
    val bitmap: Bitmap = createBitmap(inputSize, inputSize)

    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = Rect()

    /**
     * Dibuja [source] centrado dentro de [bitmap], preservando el aspecto y rellenando el
     * resto de gris.
     *
     * @return los parametros necesarios para deshacer la transformacion.
     */
    fun scale(source: Bitmap): LetterboxParams {
        val params = letterboxParams(source.width, source.height, inputSize)

        // Repintar el fondo completo en cada frame: si el tamano del frame cambiara, los
        // restos del anterior asomarian por los bordes.
        canvas.drawColor(Color.rgb(PAD_GRAY, PAD_GRAY, PAD_GRAY))

        val left = params.padX.roundToInt()
        val top = params.padY.roundToInt()
        destination.set(
            left,
            top,
            left + (source.width * params.scale).roundToInt(),
            top + (source.height * params.scale).roundToInt()
        )
        // FILTER_BITMAP: sin interpolacion el reescalado introduce escalones que degradan
        // la precision del modelo en objetos pequenos.
        canvas.drawBitmap(source, null, destination, paint)
        return params
    }
}
