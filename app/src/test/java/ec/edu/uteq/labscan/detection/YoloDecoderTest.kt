package ec.edu.uteq.labscan.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la decodificacion del tensor de YOLOv8.
 *
 * Corren en la JVM con tensores sinteticos: no hace falta el modelo ni el telefono. Es la
 * unica forma de comprobar la aritmetica antes de tener el `.tflite` de Mario.
 *
 * `RectF` no se usa en las aserciones porque en una prueba unitaria las clases de
 * `android.graphics` son esqueletos; se comprueban `classIndex` y `score`, y las
 * coordenadas se verifican indirectamente con el NMS.
 */
class YoloDecoderTest {

    private val numCandidates = 10
    private val numClasses = 3

    private fun spec(layout: OutputLayout) = OutputSpec(
        numCandidates = numCandidates,
        numClasses = numClasses,
        layout = layout,
        layoutOverridden = false
    )

    private fun decoder(layout: OutputLayout, iou: Float = 0.5f, maxDetections: Int = 20) =
        YoloDecoder(
            spec = spec(layout),
            confidenceThreshold = 0.45f,
            iouThreshold = iou,
            maxDetections = maxDetections,
            coordsNormalized = true,
            inputSize = 640
        )

    private val labels = listOf("microscopio_binocular", "incubadora", "vortex")

    /** Escribe un candidato en el tensor, respetando la disposicion. */
    private fun FloatArray.putCandidate(
        index: Int,
        layout: OutputLayout,
        cx: Float, cy: Float, w: Float, h: Float,
        scores: FloatArray
    ) {
        val channels = 4 + numClasses
        val values = floatArrayOf(cx, cy, w, h) + scores
        for (c in 0 until channels) {
            val position = if (layout == OutputLayout.TRANSPOSED) {
                c * numCandidates + index
            } else {
                index * channels + c
            }
            this[position] = values[c]
        }
    }

    @Test
    fun `la forma real decide la disposicion, aunque la config diga otra cosa`() {
        // [1, 84, 8400]: el eje corto es el de canales, luego es TRANSPOSED.
        val transposed = OutputSpec.from(intArrayOf(1, 84, 8400), OutputLayout.STANDARD)
        assertEquals(OutputLayout.TRANSPOSED, transposed.layout)
        assertEquals(80, transposed.numClasses)
        assertEquals(8400, transposed.numCandidates)
        assertTrue("Debe avisar de que corrigio la config", transposed.layoutOverridden)

        // [1, 8400, 84]: el orden intuitivo.
        val standard = OutputSpec.from(intArrayOf(1, 8400, 84), OutputLayout.STANDARD)
        assertEquals(OutputLayout.STANDARD, standard.layout)
        assertEquals(80, standard.numClasses)
        assertEquals(false, standard.layoutOverridden)
    }

    @Test
    fun `las dos disposiciones producen el mismo resultado`() {
        val results = OutputLayout.entries.map { layout ->
            val tensor = FloatArray(numCandidates * (4 + numClasses))
            tensor.putCandidate(
                index = 3, layout = layout,
                cx = 0.5f, cy = 0.5f, w = 0.2f, h = 0.2f,
                scores = floatArrayOf(0.1f, 0.88f, 0.2f)
            )
            decoder(layout).decode(tensor, labels)
        }

        results.forEach { detections ->
            assertEquals(1, detections.size)
            assertEquals(1, detections[0].classIndex)
            assertEquals("incubadora", detections[0].classId)
            assertEquals(0.88f, detections[0].score, 1e-5f)
        }
    }

    @Test
    fun `el score es el maximo de las clases, sin canal de objectness`() {
        val layout = OutputLayout.TRANSPOSED
        val tensor = FloatArray(numCandidates * (4 + numClasses))
        // Si alguien tratara el primer canal de clase como objectness, saldria 0.30f.
        tensor.putCandidate(
            index = 0, layout = layout,
            cx = 0.5f, cy = 0.5f, w = 0.1f, h = 0.1f,
            scores = floatArrayOf(0.30f, 0.20f, 0.91f)
        )
        val detections = decoder(layout).decode(tensor, labels)

        assertEquals(1, detections.size)
        assertEquals(2, detections[0].classIndex)
        assertEquals(0.91f, detections[0].score, 1e-5f)
    }

    @Test
    fun `los candidatos por debajo del umbral se descartan`() {
        val layout = OutputLayout.TRANSPOSED
        val tensor = FloatArray(numCandidates * (4 + numClasses))
        tensor.putCandidate(0, layout, 0.5f, 0.5f, 0.1f, 0.1f, floatArrayOf(0.44f, 0f, 0f))
        tensor.putCandidate(1, layout, 0.2f, 0.2f, 0.1f, 0.1f, floatArrayOf(0.46f, 0f, 0f))

        val detections = decoder(layout).decode(tensor, labels)
        assertEquals(1, detections.size)
        assertEquals(0.46f, detections[0].score, 1e-5f)
    }

    @Test
    fun `el NMS suprime solapamientos de la misma clase y respeta los de clases distintas`() {
        val layout = OutputLayout.TRANSPOSED
        val tensor = FloatArray(numCandidates * (4 + numClasses))
        // Dos cajas casi identicas de la clase 0: debe sobrevivir la de mayor puntaje.
        tensor.putCandidate(0, layout, 0.50f, 0.50f, 0.20f, 0.20f, floatArrayOf(0.90f, 0f, 0f))
        tensor.putCandidate(1, layout, 0.51f, 0.51f, 0.20f, 0.20f, floatArrayOf(0.70f, 0f, 0f))
        // Una caja en el mismo sitio pero de otra clase: NO debe suprimirse.
        tensor.putCandidate(2, layout, 0.50f, 0.50f, 0.20f, 0.20f, floatArrayOf(0f, 0f, 0.80f))

        val detections = decoder(layout).decode(tensor, labels)

        assertEquals(2, detections.size)
        assertEquals(0.90f, detections[0].score, 1e-5f)
        assertEquals(0.80f, detections[1].score, 1e-5f)
        assertEquals(setOf(0, 2), detections.map { it.classIndex }.toSet())
    }

    @Test
    fun `maxDetections recorta el resultado`() {
        val layout = OutputLayout.TRANSPOSED
        val tensor = FloatArray(numCandidates * (4 + numClasses))
        // Cinco cajas separadas de la misma clase: ninguna se suprime por IoU.
        repeat(5) { i ->
            tensor.putCandidate(
                i, layout,
                cx = 0.1f + i * 0.2f, cy = 0.5f, w = 0.05f, h = 0.05f,
                scores = floatArrayOf(0.9f - i * 0.01f, 0f, 0f)
            )
        }
        val detections = decoder(layout, maxDetections = 3).decode(tensor, labels)
        assertEquals(3, detections.size)
    }

    @Test
    fun `el rango observado se registra para el diagnostico`() {
        val layout = OutputLayout.TRANSPOSED
        val tensor = FloatArray(numCandidates * (4 + numClasses))
        tensor.putCandidate(0, layout, 0.5f, 0.5f, 0.1f, 0.1f, floatArrayOf(0.97f, 0f, 0f))

        val d = decoder(layout)
        d.decode(tensor, labels)

        assertEquals(0.97f, d.lastStats.max, 1e-5f)
        assertEquals(0f, d.lastStats.min, 1e-5f)
        assertEquals(1, d.lastStats.candidatesOverThreshold)
    }

    @Test
    fun `si las etiquetas se quedan cortas se usa un nombre de emergencia`() {
        val layout = OutputLayout.TRANSPOSED
        val tensor = FloatArray(numCandidates * (4 + numClasses))
        tensor.putCandidate(0, layout, 0.5f, 0.5f, 0.1f, 0.1f, floatArrayOf(0f, 0f, 0.8f))

        val detections = decoder(layout).decode(tensor, listOf("solo_una"))
        assertEquals("clase_2", detections[0].classId)
    }
}
