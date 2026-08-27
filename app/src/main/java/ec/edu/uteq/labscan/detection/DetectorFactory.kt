package ec.edu.uteq.labscan.detection

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import ec.edu.uteq.labscan.App
import ec.edu.uteq.labscan.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Que detector quedo activo y, si es el de prueba, por que. */
sealed interface DetectorStatus {

    /** Todavia no se ha construido ninguno. */
    data object Unknown : DetectorStatus

    /** Corriendo con el modelo real. */
    data class Real(val modelAsset: String, val classCount: Int) : DetectorStatus

    /**
     * Corriendo con [StubDetector].
     *
     * @param reason texto para la UI, como identificador de recurso.
     * @param detail detalle tecnico para el log y la pantalla de Diagnostico de F3.
     */
    data class Stub(
        @param:StringRes val reason: Int,
        val detail: String? = null
    ) : DetectorStatus
}

/**
 * Decide que implementacion de [Detector] usa la app.
 *
 * Aqui se cumple la regla 2 de CLAUDE.md: **nunca propaga una excepcion**. Si algo sale
 * mal devuelve [StubDetector] y publica el motivo en [status], que la UI observa para
 * mostrar el aviso correspondiente.
 *
 * Motivos por los que se cae al detector de prueba:
 * 1. `useStubDetector` es `true` en `model_config.json`.
 * 2. El asset del modelo no existe.
 * 3. `labels.txt` falta o esta vacio.
 * 4. El interprete lanza al construirse.
 */
class DetectorFactory(private val context: Context) {

    private val _status = MutableStateFlow<DetectorStatus>(DetectorStatus.Unknown)
    val status: StateFlow<DetectorStatus> = _status.asStateFlow()

    fun create(config: ModelConfig = ModelConfig.load(context)): Detector {
        val labels = loadLabels(context, config.labelsAsset)

        if (labels.isEmpty()) {
            return fallback(
                R.string.detector_stub_sin_etiquetas,
                "No se pudo leer ${config.labelsAsset}",
                config,
                labels = FALLBACK_LABELS
            )
        }

        if (config.useStubDetector) {
            return fallback(
                R.string.detector_stub_por_configuracion,
                "useStubDetector = true",
                config,
                labels
            )
        }

        if (!assetExists(context, config.modelAsset)) {
            return fallback(
                R.string.detector_stub_sin_modelo,
                "Falta el asset ${config.modelAsset}",
                config,
                labels
            )
        }

        return try {
            val detector = YoloTfliteDetector(context, config, labels)
            _status.value = DetectorStatus.Real(config.modelAsset, labels.size)
            Log.i(App.LOG_TAG, "Detector real activo con ${config.modelAsset}")
            detector
        } catch (mismatch: ModelMismatchException) {
            // El caso mas probable: labels.txt de otro entrenamiento. Merece su propio
            // mensaje, porque la solucion es distinta a la de un modelo corrupto.
            Log.e(App.LOG_TAG, "El modelo no cuadra con labels.txt", mismatch)
            fallback(R.string.detector_stub_desajuste, mismatch.message.orEmpty(), config, labels)
        } catch (error: Throwable) {
            // Throwable y no Exception: un delegado nativo mal cargado lanza Error, y
            // tampoco eso puede tumbar la app (CLAUDE.md, regla 2).
            Log.e(App.LOG_TAG, "El modelo no se pudo cargar", error)
            fallback(
                R.string.detector_stub_error_carga,
                error.message ?: error::class.java.simpleName,
                config,
                labels
            )
        }
    }

    private fun fallback(
        @StringRes reason: Int,
        detail: String,
        config: ModelConfig,
        labels: List<String>
    ): Detector {
        Log.w(App.LOG_TAG, "Detector de prueba activo: $detail")
        _status.value = DetectorStatus.Stub(reason, detail)
        return StubDetector(inputSize = config.inputSize, labels = labels)
    }

    private companion object {
        /**
         * Etiquetas de emergencia por si `labels.txt` no se puede leer. Solo evitan que
         * el overlay muestre texto vacio; no sustituyen al archivo real.
         */
        val FALLBACK_LABELS = listOf("clase_0", "clase_1")
    }
}
