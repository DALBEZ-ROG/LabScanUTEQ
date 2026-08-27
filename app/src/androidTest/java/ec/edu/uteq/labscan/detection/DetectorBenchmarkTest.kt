package ec.edu.uteq.labscan.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.core.graphics.scale

/**
 * Banco de medida del detector, para la tabla de rendimiento de F7.
 *
 * ### Por que un banco y no mirar el logcat de la camara
 *
 * Medir apuntando el telefono da numeros distintos en cada intento: cambia la escena, cambia el
 * numero de objetos que pasan por el NMS, y el sistema sube o baja la frecuencia del procesador
 * segun lo que este haciendo la pantalla. Aqui se pasa **siempre el mismo frame**, el mismo
 * numero de veces, sin camara ni interfaz de por medio. Es lo unico que hace comparables un
 * "antes" y un "despues".
 *
 * Corre con el telefono bloqueado, que es cuando suele estar disponible.
 *
 * ### Se salta solo si no hay modelo
 *
 * En modo demostracion (`useStubDetector: true` o sin `model.tflite`) no hay nada que medir y
 * la prueba se marca como omitida en lugar de fallar. Asi el repositorio se puede entregar sin
 * el modelo sin dejar pruebas en rojo.
 */
@RunWith(AndroidJUnit4::class)
class DetectorBenchmarkTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var detector: Detector
    private lateinit var frame: Bitmap
    private var isRealModel = false

    @Before
    fun setUp() {
        val config = ModelConfig.load(context)
        val factory = DetectorFactory(context)
        detector = factory.create(config)
        isRealModel = factory.status.value is DetectorStatus.Real

        // Frame del tamano que entrega ImageAnalysis en este proyecto: 16:9 a 720p.
        val source = InstrumentationRegistry.getInstrumentation().context.assets
            .open(SAMPLE_ASSET).use { BitmapFactory.decodeStream(it) }
        frame = source.scale(FRAME_WIDTH, FRAME_HEIGHT)
    }

    @After
    fun tearDown() {
        detector.close()
    }

    @Test
    fun latencia_de_inferencia_y_de_frame_completo() = runBlocking {
        assumeTrue("Modo demostracion: no hay modelo que medir", isRealModel)

        // Calentamiento: la primera inferencia paga la reserva de tensores nativos y la
        // subida de frecuencia del procesador. Incluirla falsearia la media hacia arriba.
        repeat(WARMUP) { detector.detect(frame, 90) }

        val totales = LongArray(ITERATIONS)
        val inferencias = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val startedAt = System.nanoTime()
            detector.detect(frame, 90)
            totales[i] = (System.nanoTime() - startedAt) / 1_000_000
            inferencias[i] = detector.lastInferenceMs
        }

        totales.sort()
        inferencias.sort()
        val medianaTotal = totales[ITERATIONS / 2]
        val medianaInferencia = inferencias[ITERATIONS / 2]
        val fpsTeoricos = if (medianaTotal > 0) 1000f / medianaTotal else 0f

        println(
            "BENCH total: mediana=${medianaTotal}ms min=${totales.first()}ms " +
                "max=${totales.last()}ms"
        )
        println(
            "BENCH inferencia: mediana=${medianaInferencia}ms min=${inferencias.first()}ms " +
                "max=${inferencias.last()}ms"
        )
        println("BENCH preparacion del frame: ${medianaTotal - medianaInferencia}ms")
        println("BENCH FPS teoricos: $fpsTeoricos")

        assertTrue("La inferencia no puede tardar mas que el frame completo", medianaInferencia <= medianaTotal)
    }

    /**
     * Comprueba la restriccion de F3: **nada se reserva por frame** en el bucle de inferencia.
     *
     * Se mide con `art.gc.bytes-allocated`, que es el contador acumulado de bytes reservados
     * por la maquina virtual. Dividido entre el numero de frames da lo que cuesta cada uno.
     *
     * No se exige cero: cada llamada devuelve una lista de [Detection] nueva, y eso son unos
     * cientos de bytes que la interfaz necesita inmutables. Lo que se vigila es que no aparezca
     * el bitmap de 640x640 (1,6 MB) ni los buffers, que es lo que dispararia el recolector.
     */
    @Test
    fun no_hay_reservas_grandes_por_frame() = runBlocking {
        assumeTrue("Modo demostracion: no hay bucle de inferencia que vigilar", isRealModel)

        repeat(WARMUP) { detector.detect(frame, 90) }

        val antes = bytesAllocated()
        repeat(ITERATIONS) { detector.detect(frame, 90) }
        val porFrame = (bytesAllocated() - antes) / ITERATIONS

        println("BENCH bytes reservados por frame: $porFrame")
        assertTrue(
            "Se reservan $porFrame bytes por frame; algo grande se coló en el bucle",
            porFrame < MAX_BYTES_PER_FRAME
        )
    }

    private fun bytesAllocated(): Long =
        Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0L

    private companion object {
        const val SAMPLE_ASSET = "coco_sample.jpg"
        const val FRAME_WIDTH = 1280
        const val FRAME_HEIGHT = 720
        const val WARMUP = 5
        const val ITERATIONS = 30

        /**
         * Tope por frame. Un bitmap de 640x640 en ARGB_8888 son 1 638 400 bytes, asi que con
         * 256 KB el margen es amplio para las listas pequenas y estrecho para cualquier
         * reserva que importe.
         */
        const val MAX_BYTES_PER_FRAME = 256L * 1024
    }
}
