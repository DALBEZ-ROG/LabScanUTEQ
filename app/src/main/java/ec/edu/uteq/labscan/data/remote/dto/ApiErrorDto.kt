package ec.edu.uteq.labscan.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Cuerpo de error estandar del contrato, para cualquier 4xx o 5xx:
 *
 * ```json
 * { "error": { "code": "EQUIPMENT_NOT_FOUND", "message": "Descripcion legible en espanol." } }
 * ```
 *
 * El sobre existe porque el contrato anida el objeto bajo la clave `error`. No se aplana:
 * la forma del JSON es del backend, no nuestra.
 */
@Serializable
data class ApiErrorEnvelopeDto(
    val error: ApiErrorDto = ApiErrorDto()
)

/**
 * Detalle del error.
 *
 * [message] viene ya redactado en espanol por el backend, pero la app **no** lo muestra tal
 * cual: usa sus propios textos de `strings.xml`, porque un mensaje del servidor puede llegar
 * en cualquier idioma, con jerga tecnica o vacio. El `message` se registra en el log.
 */
@Serializable
data class ApiErrorDto(
    /**
     * Codigos previstos en el contrato: `EQUIPMENT_NOT_FOUND`, `INDEX_NOT_READY`,
     * `LLM_UNAVAILABLE`, `RATE_LIMITED`, `INTERNAL_ERROR`. Se deja como `String` y no como
     * enum a proposito: un codigo nuevo del backend no debe romper la deserializacion.
     */
    val code: String = "",
    val message: String = ""
) {
    companion object {
        const val EQUIPMENT_NOT_FOUND = "EQUIPMENT_NOT_FOUND"
        const val INDEX_NOT_READY = "INDEX_NOT_READY"
        const val LLM_UNAVAILABLE = "LLM_UNAVAILABLE"
        const val RATE_LIMITED = "RATE_LIMITED"
        const val INTERNAL_ERROR = "INTERNAL_ERROR"
    }
}
