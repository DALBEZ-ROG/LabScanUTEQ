package ec.edu.uteq.labscan.detection

import kotlinx.coroutines.flow.StateFlow

/** Descripcion de un tensor tal y como lo declara el interprete. */
data class TensorInfo(
    val shape: List<Int>,
    val dataType: String,
    val quantScale: Float,
    val quantZeroPoint: Int
) {
    val isQuantized: Boolean get() = quantScale != 0f
    val shapeText: String get() = shape.joinToString("x")

    val quantizationText: String
        get() = if (isQuantized) "scale=$quantScale, zero_point=$quantZeroPoint" else "sin cuantizar"
}

/**
 * Todo lo que la pantalla de Diagnostico necesita saber del modelo cargado.
 *
 * Existe para que quien entrena el modelo pueda verificarlo **sin programar**: abre la
 * pantalla, compara con la tabla de docs/INTEGRACION_MODELO.md seccion 4, y sabe si su
 * export es correcto (docs/INTEGRACION_MODELO.md, seccion 5).
 */
data class ModelDiagnostics(
    val modelAsset: String,
    val input: TensorInfo,
    val output: TensorInfo,
    val inputSize: Int,
    val numCandidates: Int,
    /** Clases deducidas del tensor: eje de canales menos las 4 coordenadas. */
    val inferredClassCount: Int,
    /** Clases leidas de `labels.txt`. Si no coincide con la anterior, el modelo no arranca. */
    val labelCount: Int,
    val layout: OutputLayout,
    /** `true` si la forma real contradecia a `model_config.json` y mando la forma. */
    val layoutOverridden: Boolean,
    val usingGpuDelegate: Boolean,
    val threads: Int,
    /** Menor puntaje de clase visto en el ultimo frame. */
    val lastOutputMin: Float = 0f,
    /** Mayor puntaje de clase visto en el ultimo frame. Si no ronda 1, algo va mal. */
    val lastOutputMax: Float = 0f,
    val lastCandidatesOverThreshold: Int = 0
)

/**
 * Lo implementa el detector que puede describirse a si mismo.
 *
 * `StubDetector` no lo implementa a proposito: la pantalla de Diagnostico comprueba el tipo
 * y, si no hay modelo real, muestra el motivo en su lugar.
 */
interface DiagnosticsProvider {
    val diagnostics: StateFlow<ModelDiagnostics>
}
