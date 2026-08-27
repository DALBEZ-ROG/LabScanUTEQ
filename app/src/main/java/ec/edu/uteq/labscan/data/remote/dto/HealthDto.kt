package ec.edu.uteq.labscan.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Respuesta de `GET /api/health`.
 *
 * La app la usa para una sola decision: si el asistente esta disponible o hay que trabajar
 * en modo sin conexion. Los otros dos campos no cambian la interfaz, pero se conservan
 * porque aparecen en el contrato y son lo primero que se mira al depurar un despliegue.
 */
@Serializable
data class HealthDto(
    /** `"ok"` cuando el backend puede responder. Cualquier otro valor cuenta como caido. */
    val status: String = "",
    /** Numero de documentos indexados. Si es 0, el RAG respondera sin contexto. */
    val indexedDocuments: Int = 0,
    /** Modelo de lenguaje activo en el backend, por ejemplo `claude-haiku-4-5`. */
    val model: String = ""
) {
    /** Unica lectura que le importa a la app. */
    val isHealthy: Boolean get() = status.equals(STATUS_OK, ignoreCase = true)

    private companion object {
        const val STATUS_OK = "ok"
    }
}
