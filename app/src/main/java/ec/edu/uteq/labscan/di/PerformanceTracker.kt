package ec.edu.uteq.labscan.di

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FPS y latencias medias de los ultimos frames.
 *
 * Las dos latencias van separadas porque se optimizan con palancas distintas: [averageLatencyMs]
 * incluye la copia del `ImageProxy` a bitmap y el letterbox, que se bajan reduciendo la
 * resolucion de `ImageAnalysis`; [averageInferenceMs] es solo el interprete, que se baja con un
 * `inputSize` menor o con otro delegado. La diferencia entre ambas es lo que cuesta preparar el
 * frame, y saberlo es lo que evita optimizar el lado equivocado.
 */
data class PerformanceStats(
    val fps: Float = 0f,
    val averageLatencyMs: Long = 0L,
    val averageInferenceMs: Long = 0L,
    val sampleCount: Int = 0
) {
    /** Milisegundos que se van en preparar el frame, fuera del interprete. */
    val overheadMs: Long get() = (averageLatencyMs - averageInferenceMs).coerceAtLeast(0L)
}

/**
 * Mide el rendimiento del pipeline sobre una ventana deslizante de 30 frames.
 *
 * Vive en el [AppContainer] y no en el ViewModel del scanner porque lo leen dos pantallas
 * distintas: el HUD de depuracion y la pantalla de Diagnostico, que estan en rutas de
 * navegacion diferentes y por tanto no comparten ViewModel.
 *
 * `record` se llama desde el hilo de analisis; `stats` se lee desde el principal.
 * `MutableStateFlow` es seguro entre hilos, y la ventana solo la toca el hilo de analisis.
 */
class PerformanceTracker {

    private val _stats = MutableStateFlow(PerformanceStats())
    val stats: StateFlow<PerformanceStats> = _stats.asStateFlow()

    private val latencies = ArrayDeque<Long>()
    private val inferences = ArrayDeque<Long>()
    private var windowStartMs = 0L
    private var framesInWindow = 0

    /**
     * Latencia total mas reciente, sin promediar.
     *
     * La lee `FrameAnalyzer` para decidir si conviene saltarse frames. Se expone cruda y no
     * promediada a proposito: la decision de saltar tiene que reaccionar al frame que acaba de
     * tardar, no a la media de los treinta anteriores.
     */
    @Volatile
    var lastLatencyMs: Long = 0L
        private set

    fun record(latencyMs: Long, inferenceMs: Long, timestampMs: Long) {
        lastLatencyMs = latencyMs
        latencies.addLast(latencyMs)
        if (latencies.size > WINDOW) latencies.removeFirst()
        inferences.addLast(inferenceMs)
        if (inferences.size > WINDOW) inferences.removeFirst()

        framesInWindow++
        if (windowStartMs == 0L) windowStartMs = timestampMs

        val elapsed = timestampMs - windowStartMs
        // Se publica cada medio segundo y no en cada frame: ninguna de las dos pantallas
        // necesita mas, y asi no se recomponen 30 veces por segundo.
        if (elapsed >= PUBLISH_INTERVAL_MS) {
            _stats.value = PerformanceStats(
                fps = framesInWindow * 1000f / elapsed,
                averageLatencyMs = latencies.average().toLong(),
                averageInferenceMs = inferences.average().toLong(),
                sampleCount = latencies.size
            )
            windowStartMs = timestampMs
            framesInWindow = 0
        }
    }

    private companion object {
        const val WINDOW = 30
        const val PUBLISH_INTERVAL_MS = 500L
    }
}
