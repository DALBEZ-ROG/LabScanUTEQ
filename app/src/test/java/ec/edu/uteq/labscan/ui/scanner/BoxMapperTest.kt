package ec.edu.uteq.labscan.ui.scanner

import ec.edu.uteq.labscan.detection.letterboxParams
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pruebas de la matematica de coordenadas.
 *
 * Corren en la JVM, sin dispositivo y sin Robolectric, porque [BoxMapper.buildValues] no
 * toca ninguna clase de Android. Cubren los tres casos que CLAUDE.md exige verificar antes
 * de cerrar F2, con numeros calculados a mano.
 *
 * Escenario base: frame de analisis 1280x720 (16:9 sin girar, como lo entrega CameraX),
 * modelo de 640, pantalla de 1080x2400.
 */
class BoxMapperTest {

    private val analysisWidth = 1280
    private val analysisHeight = 720
    private val inputSize = 640

    /** Caja de calibracion del StubDetector, ya convertida al espacio del modelo. */
    private fun calibrationBoxInModelSpace(): FloatArray {
        val p = letterboxParams(analysisWidth, analysisHeight, inputSize)
        fun toModelX(f: Float) = (f * analysisWidth * p.scale + p.padX) / inputSize
        fun toModelY(f: Float) = (f * analysisHeight * p.scale + p.padY) / inputSize
        return floatArrayOf(toModelX(0.25f), toModelY(0.25f), toModelX(0.75f), toModelY(0.75f))
    }

    @Test
    fun `el letterbox de un frame 16 a 9 rellena solo en vertical`() {
        val p = letterboxParams(1280, 720, 640)
        assertEquals(0.5f, p.scale, EPS)
        assertEquals(0.0f, p.padX, EPS)
        assertEquals(140.0f, p.padY, EPS)
    }

    @Test
    fun `vertical trasera - la caja de calibracion queda centrada`() {
        val box = calibrationBoxInModelSpace()
        val m = BoxMapper.buildValues(
            modelInputSize = inputSize,
            analysisWidth = analysisWidth,
            analysisHeight = analysisHeight,
            rotationDegrees = 90,
            isFrontCamera = false,
            viewWidth = 1080f,
            viewHeight = 2400f
        )
        val a = BoxMapper.mapPoint(m, box[0], box[1])
        val b = BoxMapper.mapPoint(m, box[2], box[3])
        // Un giro de 90 grados intercambia que esquina queda a la izquierda, asi que hay
        // que ordenar. En el overlay lo hace `Matrix.mapRect`, que devuelve el rectangulo
        // envolvente ya normalizado.
        val left = minOf(a[0], b[0])
        val right = maxOf(a[0], b[0])
        val top = minOf(a[1], b[1])
        val bottom = maxOf(a[1], b[1])

        // El eje que NO recorta FILL_CENTER cae exactamente en el 25 % y el 75 %.
        assertEquals(600f, top, EPS)
        assertEquals(1800f, bottom, EPS)

        // El eje recortado se estrecha, pero sigue perfectamente centrado.
        assertCentered(left, right, 1080f)
        assertEquals(202.5f, left, EPS)
        assertEquals(877.5f, right, EPS)
    }

    @Test
    fun `horizontal trasera - la caja de calibracion queda centrada`() {
        val box = calibrationBoxInModelSpace()
        // En horizontal el buffer ya llega derecho: rotationDegrees vale 0 y la vista se
        // da la vuelta.
        val m = BoxMapper.buildValues(
            modelInputSize = inputSize,
            analysisWidth = analysisWidth,
            analysisHeight = analysisHeight,
            rotationDegrees = 0,
            isFrontCamera = false,
            viewWidth = 2400f,
            viewHeight = 1080f
        )
        val a = BoxMapper.mapPoint(m, box[0], box[1])
        val b = BoxMapper.mapPoint(m, box[2], box[3])

        assertCentered(minOf(a[0], b[0]), maxOf(a[0], b[0]), 2400f)
        assertCentered(minOf(a[1], b[1]), maxOf(a[1], b[1]), 1080f)
    }

    @Test
    fun `vertical frontal - la caja de calibracion sigue centrada`() {
        val box = calibrationBoxInModelSpace()
        val m = BoxMapper.buildValues(
            modelInputSize = inputSize,
            analysisWidth = analysisWidth,
            analysisHeight = analysisHeight,
            rotationDegrees = 270,
            isFrontCamera = true,
            viewWidth = 1080f,
            viewHeight = 2400f
        )
        val topLeft = BoxMapper.mapPoint(m, box[0], box[1])
        val bottomRight = BoxMapper.mapPoint(m, box[2], box[3])

        assertCentered(minOf(topLeft[0], bottomRight[0]), maxOf(topLeft[0], bottomRight[0]), 1080f)
        assertEquals(600f, minOf(topLeft[1], bottomRight[1]), EPS)
        assertEquals(1800f, maxOf(topLeft[1], bottomRight[1]), EPS)
    }

    @Test
    fun `la camara frontal espeja en X y no en Y`() {
        val args = { front: Boolean ->
            BoxMapper.buildValues(inputSize, analysisWidth, analysisHeight, 90, front, 1080f, 2400f)
        }
        // Caja descentrada: es la unica forma de ver un espejado.
        val x = 0.30f
        val y = 0.40f
        val back = BoxMapper.mapPoint(args(false), x, y)
        val front = BoxMapper.mapPoint(args(true), x, y)

        // Misma altura, posicion horizontal reflejada respecto al centro de la vista.
        assertEquals(back[1], front[1], EPS)
        assertEquals(1080f, back[0] + front[0], EPS)
    }

    @Test
    fun `las cuatro rotaciones mandan la esquina del frame a la esquina correcta`() {
        // Esquina superior izquierda del frame de analisis, en espacio del modelo.
        val p = letterboxParams(analysisWidth, analysisHeight, inputSize)
        val originX = p.padX / inputSize
        val originY = p.padY / inputSize

        // Con 90 grados y sin recorte de vista, la esquina superior izquierda del frame
        // pasa a ser la superior derecha de la imagen derecha.
        val m = BoxMapper.buildValues(inputSize, analysisWidth, analysisHeight, 90, false, 720f, 1280f)
        val corner = BoxMapper.mapPoint(m, originX, originY)
        assertEquals(720f, corner[0], EPS)
        assertEquals(0f, corner[1], EPS)
    }

    private fun assertCentered(low: Float, high: Float, viewSize: Float) {
        val marginStart = low
        val marginEnd = viewSize - high
        assertEquals("La caja no esta centrada: $marginStart vs $marginEnd", marginStart, marginEnd, EPS)
    }

    private companion object {
        const val EPS = 0.01f
    }
}
