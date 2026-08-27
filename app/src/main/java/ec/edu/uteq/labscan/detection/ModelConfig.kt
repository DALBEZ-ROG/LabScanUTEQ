package ec.edu.uteq.labscan.detection

import android.content.Context
import android.util.Log
import ec.edu.uteq.labscan.App
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Contenido de `assets/model_config.json`.
 *
 * Espeja campo a campo el JSON documentado en docs/INTEGRACION_MODELO.md, seccion 3.
 * **Todos** los campos tienen valor por defecto a proposito: si el JSON entregado viene
 * incompleto o mal escrito, la app arranca igual y lo reporta, en lugar de crashear.
 */
@Serializable
data class ModelConfig(
    val useStubDetector: Boolean = true,
    val modelAsset: String = "model.tflite",
    val labelsAsset: String = "labels.txt",
    val inputSize: Int = 640,
    val quantized: Boolean = true,
    val outputLayout: String = "TRANSPOSED",
    val coordsNormalized: Boolean = true,
    val confidenceThreshold: Float = 0.45f,
    val iouThreshold: Float = 0.50f,
    val maxDetections: Int = 20,
    val useGpuDelegate: Boolean = false
) {
    companion object {
        private const val CONFIG_ASSET = "model_config.json"

        private val json = Json {
            // Que el modelo agregue campos nuevos no debe romper una app ya publicada.
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Lee la configuracion de assets. Si el archivo falta o no se puede interpretar,
         * devuelve los valores por defecto, que dejan la app en modo de prueba.
         */
        fun load(context: Context): ModelConfig = try {
            val raw = context.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
            json.decodeFromString<ModelConfig>(raw)
        } catch (error: Exception) {
            Log.e(App.LOG_TAG, "No se pudo leer $CONFIG_ASSET, se usan valores por defecto", error)
            ModelConfig()
        }
    }
}

/**
 * Lee las clases de `labels.txt`, una por linea.
 *
 * El numero de clases NUNCA se codifica a mano (CLAUDE.md, regla 4): sale de aqui en
 * tiempo de ejecucion. Si el archivo falta, devuelve una lista vacia y quien la use debe
 * arreglarselas; [DetectorFactory] lo trata como motivo para caer al detector de prueba.
 */
fun loadLabels(context: Context, asset: String): List<String> = try {
    context.assets.open(asset).bufferedReader().use { reader ->
        reader.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }
} catch (error: Exception) {
    Log.e(App.LOG_TAG, "No se pudo leer $asset", error)
    emptyList()
}

/** `true` si el asset existe. Se usa para decidir si hay modelo antes de intentar cargarlo. */
fun assetExists(context: Context, asset: String): Boolean = try {
    context.assets.open(asset).close()
    true
} catch (_: Exception) {
    false
}
