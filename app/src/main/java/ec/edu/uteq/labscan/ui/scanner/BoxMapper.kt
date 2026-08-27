package ec.edu.uteq.labscan.ui.scanner

import android.graphics.Matrix
import ec.edu.uteq.labscan.detection.letterboxParams
import kotlin.math.max

/**
 * UNICO lugar del proyecto donde vive la matematica de coordenadas.
 *
 * Convierte una caja normalizada del espacio del modelo a pixeles del `Canvas` de Compose.
 * La cadena completa, en este orden (CLAUDE.md, seccion critica):
 *
 * ```
 * caja normalizada 0..1 del cuadrado del modelo
 *   1. a pixeles del cuadrado del modelo        (x * inputSize)
 *   2. quitar el relleno del letterbox          (- padX, - padY)
 *   3. deshacer el escalado del letterbox       (/ scale)  -> frame de analisis SIN GIRAR
 *   4. girar rotationDegrees en sentido horario            -> frame derecho
 *   5. espejar en X si la camara es frontal
 *   6. escalar y centrar segun FILL_CENTER                 -> pixeles de la vista
 * ```
 *
 * ### Por que la matriz se compone a mano
 *
 * Los pasos se calculan como matrices 3x3 de Kotlin puro y solo al final se vuelcan en una
 * [Matrix] de Android. Asi la parte dificil se puede probar con JUnit corriente, sin
 * Robolectric ni dispositivo: en una prueba unitaria `android.graphics.Matrix` es un
 * esqueleto que devuelve ceros. Ver `BoxMapperTest`.
 *
 * Las rotaciones se escriben como constantes exactas en lugar de `sin` y `cos`, para que
 * en 90, 180 y 270 grados no haya error de coma flotante.
 */
object BoxMapper {

    /**
     * Construye la matriz de transformacion.
     *
     * @param modelInputSize lado del cuadrado del modelo.
     * @param analysisWidth ancho del frame de `ImageAnalysis`, sin girar.
     * @param analysisHeight alto del frame de `ImageAnalysis`, sin girar.
     * @param rotationDegrees `ImageProxy.imageInfo.rotationDegrees`.
     * @param isFrontCamera `true` si la vista previa esta espejada.
     * @param viewWidth ancho del `Canvas` en pixeles.
     * @param viewHeight alto del `Canvas` en pixeles.
     */
    fun build(
        modelInputSize: Int,
        analysisWidth: Int,
        analysisHeight: Int,
        rotationDegrees: Int,
        isFrontCamera: Boolean,
        viewWidth: Float,
        viewHeight: Float
    ): Matrix = Matrix().apply {
        setValues(
            buildValues(
                modelInputSize = modelInputSize,
                analysisWidth = analysisWidth,
                analysisHeight = analysisHeight,
                rotationDegrees = rotationDegrees,
                isFrontCamera = isFrontCamera,
                viewWidth = viewWidth,
                viewHeight = viewHeight
            )
        )
    }

    /**
     * La misma transformacion, como los 9 valores de una matriz afin en el orden que usa
     * [Matrix.setValues]. Kotlin puro, sin dependencias de Android: es lo que se prueba.
     */
    fun buildValues(
        modelInputSize: Int,
        analysisWidth: Int,
        analysisHeight: Int,
        rotationDegrees: Int,
        isFrontCamera: Boolean,
        viewWidth: Float,
        viewHeight: Float
    ): FloatArray {
        val letterbox = letterboxParams(analysisWidth, analysisHeight, modelInputSize)
        var m = identity()

        // 1. De normalizado a pixeles del cuadrado del modelo.
        m = m then scale(modelInputSize.toFloat(), modelInputSize.toFloat())

        // 2. Quitar las bandas grises. Tras esto el origen es la esquina de la imagen
        //    real, no la del cuadrado.
        m = m then translate(-letterbox.padX, -letterbox.padY)

        // 3. Deshacer el reescalado. Ya estamos en pixeles del frame de analisis sin
        //    girar, o sea en [0, analysisWidth] x [0, analysisHeight].
        m = m then scale(1f / letterbox.scale, 1f / letterbox.scale)

        // 4. Girar el frame para verlo derecho. El giro deja coordenadas negativas, asi
        //    que se traslada para devolver el origen a la esquina superior izquierda.
        val rotation = normalizeRotation(rotationDegrees)
        m = m then rotate(rotation)
        m = m then when (rotation) {
            90 -> translate(analysisHeight.toFloat(), 0f)
            180 -> translate(analysisWidth.toFloat(), analysisHeight.toFloat())
            270 -> translate(0f, analysisWidth.toFloat())
            else -> identity()
        }

        // Tamano del frame ya girado. En 90 y 270 los ejes se intercambian.
        val swapped = rotation == 90 || rotation == 270
        val rotatedWidth = if (swapped) analysisHeight.toFloat() else analysisWidth.toFloat()
        val rotatedHeight = if (swapped) analysisWidth.toFloat() else analysisHeight.toFloat()

        // 5. La vista previa de la camara frontal se muestra espejada, como un espejo de
        //    verdad. El espejado va DESPUES del giro, porque espeja lo que se ve en
        //    pantalla, no el buffer del sensor.
        if (isFrontCamera) {
            m = m then scale(-1f, 1f)
            m = m then translate(rotatedWidth, 0f)
        }

        // 6. FILL_CENTER: se escala por el factor MAYOR, de modo que no queden bandas, y
        //    se centra. El sobrante se recorta, asi que los desplazamientos son negativos
        //    o cero. Es exactamente lo que hace PreviewView.ScaleType.FILL_CENTER.
        val viewScale = max(viewWidth / rotatedWidth, viewHeight / rotatedHeight)
        m = m then scale(viewScale, viewScale)
        m = m then translate(
            (viewWidth - rotatedWidth * viewScale) / 2f,
            (viewHeight - rotatedHeight * viewScale) / 2f
        )

        return m
    }

    /** Lleva cualquier angulo al conjunto {0, 90, 180, 270}. */
    private fun normalizeRotation(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        // Se redondea al multiplo de 90 mas cercano: CameraX nunca entrega otra cosa, pero
        // un valor raro no debe producir una matriz sin sentido.
        return ((normalized + 45) / 90 * 90) % 360
    }

    // -----------------------------------------------------------------------------------
    // Algebra de matrices afines 3x3, en el orden de Matrix.setValues:
    //   [0] escalaX  [1] sesgoX   [2] traslacionX
    //   [3] sesgoY   [4] escalaY  [5] traslacionY
    //   [6] 0        [7] 0        [8] 1
    // -----------------------------------------------------------------------------------

    private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private fun scale(sx: Float, sy: Float) = floatArrayOf(sx, 0f, 0f, 0f, sy, 0f, 0f, 0f, 1f)

    private fun translate(tx: Float, ty: Float) = floatArrayOf(1f, 0f, tx, 0f, 1f, ty, 0f, 0f, 1f)

    /**
     * Giro horario exacto en multiplos de 90. En coordenadas de pantalla, con la Y hacia
     * abajo, `(x, y) -> (-y, x)` es un giro horario de 90 grados.
     */
    private fun rotate(degrees: Int) = when (degrees) {
        90 -> floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        180 -> floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
        270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        else -> identity()
    }

    /**
     * Encadena transformaciones: `a then b` aplica primero `a` y despues `b`, o sea el
     * producto `b * a`. Se lee en el mismo orden en que ocurren los pasos.
     */
    private infix fun FloatArray.then(after: FloatArray): FloatArray {
        val a = after
        val b = this
        return floatArrayOf(
            a[0] * b[0] + a[1] * b[3],
            a[0] * b[1] + a[1] * b[4],
            a[0] * b[2] + a[1] * b[5] + a[2],
            a[3] * b[0] + a[4] * b[3],
            a[3] * b[1] + a[4] * b[4],
            a[3] * b[2] + a[4] * b[5] + a[5],
            0f, 0f, 1f
        )
    }

    /** Aplica una matriz de [buildValues] a un punto. Existe sobre todo para las pruebas. */
    fun mapPoint(values: FloatArray, x: Float, y: Float): FloatArray = floatArrayOf(
        values[0] * x + values[1] * y + values[2],
        values[3] * x + values[4] * y + values[5]
    )
}
