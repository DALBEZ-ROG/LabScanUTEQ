package ec.edu.uteq.labscan.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prueba de punta a punta de la inferencia real, sobre el dispositivo.
 *
 * Usa una fotografia conocida en lugar de la camara: asi el resultado no depende de hacia
 * donde apunte el telefono y la prueba es repetible. Verifica que el detector real carga el
 * modelo de `assets/`, infiere y devuelve clases coherentes.
 *
 * Si no hay `model.tflite` en assets, o si es un modelo de la UTEQ y no de COCO, las pruebas
 * de contenido se saltan solas: esto tiene que poder ejecutarse en cualquier estado del repo.
 */
@RunWith(AndroidJUnit4::class)
class YoloTfliteDetectorTest {

    private lateinit var detector: YoloTfliteDetector
    private lateinit var image: Bitmap
    private var available = false

    @Before
    fun setUp() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val config = ModelConfig.load(appContext)
        if (!assetExists(appContext, config.modelAsset)) return

        val labels = loadLabels(appContext, config.labelsAsset)
        detector = YoloTfliteDetector(appContext, config, labels)

        val testContext = InstrumentationRegistry.getInstrumentation().context
        image = testContext.assets.open("coco_sample.jpg").use { BitmapFactory.decodeStream(it) }
        available = true
    }

    @After
    fun tearDown() {
        if (available) detector.close()
    }

    @Test
    fun elModeloSeIntrospeccionaYCuadraConLabels() {
        if (!available) return
        val d = detector.diagnostics.value

        Log.i("LabScanTest", "Entrada: ${d.input.shapeText} ${d.input.dataType} ${d.input.quantizationText}")
        Log.i("LabScanTest", "Salida:  ${d.output.shapeText} ${d.output.dataType} ${d.output.quantizationText}")
        Log.i("LabScanTest", "Clases: labels=${d.labelCount} tensor=${d.inferredClassCount} layout=${d.layout}")

        assertEquals(
            "labels.txt y el tensor deben declarar el mismo numero de clases",
            d.labelCount,
            d.inferredClassCount
        )
        assertEquals(detector.inputSize, d.input.shape[1])
    }

    @Test
    fun detectaObjetosComunesEnUnaFotoConocida() {
        if (!available) return

        // Se ejecuta dos veces a proposito: la segunda pasada comprueba que reutilizar los
        // buffers entre frames no ensucia el resultado.
        val first = runBlocking { detector.detect(image, 0) }
        val second = runBlocking { detector.detect(image, 0) }

        val names = first.map { "${it.classId} ${(it.score * 100).toInt()}%" }
        Log.i("LabScanTest", "Detecciones: $names")
        Log.i(
            "LabScanTest",
            "Rango salida: ${detector.diagnostics.value.lastOutputMin} .. " +
                detector.diagnostics.value.lastOutputMax
        )

        assertTrue("El detector no encontro nada en la foto de prueba", first.isNotEmpty())
        assertEquals(
            "Dos pasadas sobre la misma imagen deben dar lo mismo",
            first.map { it.classId },
            second.map { it.classId }
        )

        first.forEach { detection ->
            assertTrue("Score fuera de rango: ${detection.score}", detection.score in 0f..1f)
            assertTrue("Caja fuera del cuadrado: ${detection.box}", detection.box.left >= 0f)
            assertTrue("Caja invertida: ${detection.box}", detection.box.right > detection.box.left)
            assertTrue("Caja invertida: ${detection.box}", detection.box.bottom > detection.box.top)
        }

        // Solo tiene sentido con el modelo COCO de prueba.
        if (detector.labels.contains("person")) {
            assertTrue(
                "Se esperaba al menos una persona en coco_sample.jpg, se obtuvo $names",
                first.any { it.classId == "person" }
            )
        }
    }
}
