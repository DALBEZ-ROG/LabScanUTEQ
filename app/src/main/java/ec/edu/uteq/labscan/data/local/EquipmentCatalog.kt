package ec.edu.uteq.labscan.data.local

import android.content.Context
import android.util.Log
import ec.edu.uteq.labscan.App
import ec.edu.uteq.labscan.data.remote.dto.EquipmentDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Resultado de buscar una ficha.
 *
 * @param equipment la ficha. Si [fromCatalog] es `false`, es una ficha minima construida a
 *   partir del `classId`, no un dato real.
 * @param fromCatalog `false` cuando la clase detectada no esta en `catalog.json`. La interfaz
 *   lo usa para avisar de que no hay informacion, en lugar de mostrar una ficha vacia sin
 *   explicacion.
 */
data class CatalogEntry(
    val equipment: EquipmentDto,
    val fromCatalog: Boolean
)

/**
 * Catalogo de fichas tecnicas sin conexion.
 *
 * Lee `assets/catalog.json` una sola vez y lo cachea en memoria. El archivo contiene
 * respuestas del contrato `GET /api/equipment/{classId}` guardadas en el APK, asi que se
 * deserializa con el mismo [EquipmentDto] que usara F5 y no hace falta un segundo modelo.
 *
 * No se usa Room a proposito (CLAUDE.md): son cuatro fichas de solo lectura que viajan
 * dentro del APK.
 *
 * La lectura ocurre en [Dispatchers.IO] y esta protegida por un [Mutex], de modo que varias
 * detecciones tocadas seguidas no disparen varias lecturas del asset.
 */
class EquipmentCatalog(private val context: Context) {

    private val mutex = Mutex()
    private var cache: Map<String, EquipmentDto>? = null

    /**
     * Busca la ficha de una clase detectada.
     *
     * Nunca devuelve `null` ni lanza: si la clase no esta en el catalogo, o si el archivo no
     * se puede leer, construye una ficha minima con el `classId` formateado como titulo.
     * Una deteccion sin ficha sigue siendo una deteccion valida y el estudiante debe poder
     * ver al menos que se detecto.
     */
    suspend fun find(classId: String): CatalogEntry {
        val entries = catalog()
        val found = entries[classId]
        return if (found != null) {
            CatalogEntry(found, fromCatalog = true)
        } else {
            Log.w(App.LOG_TAG, "Sin ficha para '$classId' en $CATALOG_ASSET")
            CatalogEntry(
                EquipmentDto(classId = classId, displayName = classId.toDisplayName()),
                fromCatalog = false
            )
        }
    }

    /** Fuerza la carga. Sirve para precalentar la cache antes del primer toque. */
    suspend fun preload() {
        catalog()
    }

    private suspend fun catalog(): Map<String, EquipmentDto> {
        cache?.let { return it }
        return mutex.withLock {
            // Otra corrutina puede haberlo cargado mientras esperabamos el candado.
            cache ?: load().also { cache = it }
        }
    }

    private suspend fun load(): Map<String, EquipmentDto> = withContext(Dispatchers.IO) {
        try {
            val raw = context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
            json.decodeFromString<List<EquipmentDto>>(raw)
                .associateBy { it.classId }
                .also { Log.i(App.LOG_TAG, "Catalogo cargado: ${it.size} fichas") }
        } catch (error: Exception) {
            // Un catalogo ilegible no puede tumbar la app: se degrada a fichas minimas.
            Log.e(App.LOG_TAG, "No se pudo leer $CATALOG_ASSET", error)
            emptyMap()
        }
    }

    private companion object {
        const val CATALOG_ASSET = "catalog.json"

        val json = Json {
            // Que el backend agregue campos no debe romper una app ya publicada.
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** `camara_electroforesis` -> `Camara electroforesis`. */
        fun String.toDisplayName(): String = replace('_', ' ')
            .replaceFirstChar { it.uppercase() }
    }
}
