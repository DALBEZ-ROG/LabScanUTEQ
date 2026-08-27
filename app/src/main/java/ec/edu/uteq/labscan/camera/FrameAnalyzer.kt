package ec.edu.uteq.labscan.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import ec.edu.uteq.labscan.App
import ec.edu.uteq.labscan.BuildConfig
import ec.edu.uteq.labscan.detection.Detection
import ec.edu.uteq.labscan.detection.Detector
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Todo lo que la UI necesita saber de un frame analizado.
 *
 * Las medidas viajan juntas a proposito: la lista de detecciones no significa nada sin el
 * tamano del frame, la rotacion y el espejado con los que se produjo. Separarlas seria la
 * forma mas facil de dibujar cajas de un frame sobre la geometria de otro.
 */
data class FrameResult(
    val detections: List<Detection>,
    val frameWidth: Int,
    val frameHeight: Int,
    val rotationDegrees: Int,
    val isFrontCamera: Boolean,
    /** Milisegundos de todo el frame: copia a bitmap, letterbox, inferencia y decodificacion. */
    val latencyMs: Long,
    /** Milisegundos de solo el interprete. Ver [Detector.lastInferenceMs]. */
    val inferenceMs: Long,
    val timestampMs: Long
)

/**
 * `ImageAnalysis.Analyzer` que pasa cada frame por el [Detector].
 *
 * Corre siempre en el ejecutor de un solo hilo que le da `AppContainer`, nunca en el hilo
 * principal.
 *
 * ### Por que `runBlocking`
 *
 * [Detector.detect] es `suspend`, y `analyze` no. Bloquear aqui es lo correcto, no un
 * atajo: estamos en un hilo dedicado al analisis y CameraX usa el retorno de `analyze`
 * como senal de contrapresion. Si se lanzara el trabajo a otro scope y se volviera de
 * inmediato, `STRATEGY_KEEP_ONLY_LATEST` dejaria de funcionar y se acumularian frames.
 */
private const val LOG_INTERVAL_MS = 1000L

/** Por encima de esto el dispositivo va ahogado y conviene descartar frames. */
private const val SKIP_UP_MS = 250L

/** Por debajo de esto se vuelve a procesarlos todos. */
private const val SKIP_DOWN_MS = 150L

/** Tope: nunca menos de uno de cada cuatro, o las cajas se quedarian claramente atrasadas. */
private const val MAX_SKIP = 3

class FrameAnalyzer(
    private val detector: Detector,
    private val onResult: (FrameResult) -> Unit
) : ImageAnalysis.Analyzer {

    /**
     * Cuantos frames se descartan por cada uno que se procesa.
     *
     * Se recalcula solo a partir de la latencia medida. **No sube los FPS de deteccion**, y no
     * pretende hacerlo: con `STRATEGY_KEEP_ONLY_LATEST` el pipeline ya corre tan rapido como
     * permite la inferencia. Lo que hace es soltar antes el `ImageProxy` cuando el dispositivo
     * va ahogado, que deja respirar a la vista previa y baja la temperatura. En un equipo que
     * cumple el presupuesto se queda en 0 y no descarta nada.
     */
    private var skipEvery = 0
    private var framesSeen = 0L

    /**
     * Lo actualiza [CameraBinder] en cada enganche. Es `@Volatile` porque se escribe desde
     * el hilo principal y se lee desde el hilo de analisis.
     */
    @Volatile
    var isFrontCamera: Boolean = false

    private val closed = AtomicBoolean(false)
    private var lastLogMs = 0L

    override fun analyze(imageProxy: ImageProxy) {
        try {
            if (closed.get()) return

            // Salto adaptativo. El overlay no parpadea al descartar un frame porque las
            // detecciones anteriores siguen publicadas: solo dejan de actualizarse un momento,
            // y el suavizado de BoxSmoother lo disimula del todo.
            if (skipEvery > 0 && framesSeen++ % (skipEvery + 1) != 0L) return

            val startedAt = System.nanoTime()
            // El formato de salida es RGBA_8888, asi que toBitmap() es una copia directa,
            // sin conversion YUV a mano.
            val bitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            val detections = runBlocking { detector.detect(bitmap, rotationDegrees) }
            val latencyMs = (System.nanoTime() - startedAt) / 1_000_000
            val inferenceMs = detector.lastInferenceMs

            updateSkipRate(latencyMs)
            logDetections(detections, latencyMs, inferenceMs)

            onResult(
                FrameResult(
                    detections = detections,
                    // El tamano se toma del bitmap y no del ImageProxy: son el mismo, pero
                    // asi es imposible que el recorte del ImageProxy los desalinee.
                    frameWidth = bitmap.width,
                    frameHeight = bitmap.height,
                    rotationDegrees = rotationDegrees,
                    isFrontCamera = isFrontCamera,
                    latencyMs = latencyMs,
                    inferenceMs = inferenceMs,
                    timestampMs = System.currentTimeMillis()
                )
            )
        } catch (error: Exception) {
            // Un frame malo no puede tumbar la camara. Se registra y se sigue con el
            // siguiente.
            Log.e(App.LOG_TAG, "Fallo al analizar un frame", error)
        } finally {
            // OBLIGATORIO. Si un ImageProxy se queda sin cerrar, CameraX deja de entregar
            // frames y la vista previa se congela sin ningun error visible.
            imageProxy.close()
        }
    }

    /**
     * Ajusta cuantos frames descartar segun lo que este tardando el dispositivo.
     *
     * Con histeresis a proposito: se empieza a descartar por encima de [SKIP_UP_MS] y se deja
     * de hacerlo por debajo de [SKIP_DOWN_MS]. Sin esa separacion, un equipo que ronde el
     * umbral entraria y saldria del modo de descarte cada pocos frames y el ritmo de
     * actualizacion de las cajas se volveria irregular, que se nota mas que ir simplemente
     * lento.
     */
    private fun updateSkipRate(latencyMs: Long) {
        skipEvery = when {
            latencyMs > SKIP_UP_MS -> (skipEvery + 1).coerceAtMost(MAX_SKIP)
            latencyMs < SKIP_DOWN_MS -> (skipEvery - 1).coerceAtLeast(0)
            else -> skipEvery
        }
    }

    /**
     * Traza las detecciones en el log, como mucho una vez por segundo y solo en
     * depuracion. Es la forma de verificar un modelo nuevo sin mirar la pantalla:
     * `adb logcat -s LabScan`.
     */
    private fun logDetections(detections: List<Detection>, latencyMs: Long, inferenceMs: Long) {
        if (!BuildConfig.DEBUG) return
        val now = System.currentTimeMillis()
        if (now - lastLogMs < LOG_INTERVAL_MS) return
        lastLogMs = now

        // El desglose "total / inferencia" es lo que se copia a docs/PROGRESO.md al medir.
        val tiempos = "${latencyMs} ms total, ${inferenceMs} ms inferencia"
        if (detections.isEmpty()) {
            Log.d(App.LOG_TAG, "Sin detecciones ($tiempos)")
        } else {
            val resumen = detections.joinToString { d ->
                "${d.classId} ${(d.score * 100).toInt()}%"
            }
            Log.d(App.LOG_TAG, "Detecciones ($tiempos): $resumen")
        }
    }

    /** Deja de analizar. No cierra el [Detector]: de eso se ocupa `AppContainer`. */
    fun stop() {
        closed.set(true)
    }
}
