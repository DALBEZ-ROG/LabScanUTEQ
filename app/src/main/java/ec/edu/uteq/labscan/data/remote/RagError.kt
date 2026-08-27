package ec.edu.uteq.labscan.data.remote

import androidx.annotation.StringRes
import ec.edu.uteq.labscan.R
import ec.edu.uteq.labscan.data.remote.dto.ApiErrorDto
import ec.edu.uteq.labscan.data.remote.dto.ApiErrorEnvelopeDto
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Fallos de red que la app sabe nombrar.
 *
 * La interfaz **nunca** ve una `IOException`, una `HttpException` ni una excepcion de
 * serializacion: [RagRepository] las traduce todas a uno de estos casos antes de devolver.
 * Asi la pantalla decide que mostrar mirando un tipo cerrado, y no adivinando a partir de
 * un texto que viene del servidor.
 *
 * Hereda de `Exception` porque `Result.failure` exige un `Throwable`, no porque se lance:
 * en el repositorio estos objetos se construyen y se devuelven, no se tiran.
 *
 * @param messageRes texto en espanol de `strings.xml`. El `message` del backend se registra
 *   en el log, pero no se muestra: puede venir vacio, en otro idioma o con jerga interna.
 * @param retryable si tiene sentido ofrecer un boton "Reintentar". Un 404 no lo tiene; un
 *   5xx o un timeout, si.
 */
sealed class RagError(
    @param:StringRes val messageRes: Int,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /** No hay red, o el host no resuelve. Se responde sin salir siquiera del dispositivo. */
    class NoConnection(cause: Throwable? = null) : RagError(
        R.string.error_red_sin_conexion, retryable = true, message = "Sin conexion", cause = cause
    )

    /** El backend tardo mas que los timeouts de [NetworkTimeouts]. */
    class Timeout(cause: Throwable? = null) : RagError(
        R.string.error_red_timeout, retryable = true, message = "Timeout", cause = cause
    )

    /** 404: el `classId` no existe en el backend. La ficha cae al catalogo local. */
    class NotFound(detail: String = "") : RagError(
        R.string.error_red_no_encontrado, retryable = false, message = "No encontrado: $detail"
    )

    /** 5xx generico, `INDEX_NOT_READY` o `RATE_LIMITED`. Reintentar puede funcionar. */
    class ServerError(val code: String = "", detail: String = "") : RagError(
        R.string.error_red_servidor, retryable = true, message = "Servidor [$code] $detail"
    )

    /** `LLM_UNAVAILABLE`: el backend esta vivo pero el modelo de lenguaje no responde. */
    class LlmUnavailable(detail: String = "") : RagError(
        R.string.error_red_llm, retryable = true, message = "LLM no disponible: $detail"
    )

    /** Cualquier otra cosa, incluido un JSON que no encaja con el contrato. */
    class Unknown(cause: Throwable? = null) : RagError(
        R.string.error_red_desconocido, retryable = true, message = "Fallo no clasificado", cause = cause
    )
}

/** Timeouts del cliente HTTP, fijados por la actividad. Viven aqui para poder citarlos. */
object NetworkTimeouts {
    const val CONNECT_SECONDS = 10L
    const val READ_SECONDS = 15L
    const val WRITE_SECONDS = 10L
}

/**
 * Traduce cualquier excepcion de la capa de red a un [RagError].
 *
 * Es `internal` y no privada para poder probarla en la JVM sin dispositivo: es la pieza
 * donde es facil equivocarse de orden. El orden importa porque la jerarquia de `java.io`
 * es traicionera:
 *
 * ```
 * IOException
 *   InterruptedIOException
 *     SocketTimeoutException   <- timeout de lectura o escritura
 *   ConnectException           <- no hay ruta al host
 *   UnknownHostException       <- el DNS no resuelve (tipico de "sin red")
 * ```
 *
 * Si se comprobara `IOException` primero, un timeout se clasificaria como falta de red y el
 * usuario leeria un mensaje equivocado.
 */
internal fun Throwable.toRagError(): RagError = when (this) {
    is RagError -> this
    is HttpException -> httpToRagError()
    // Timeout antes que IOException: SocketTimeoutException tambien es una IOException.
    is SocketTimeoutException -> RagError.Timeout(this)
    is InterruptedIOException -> RagError.Timeout(this)
    is UnknownHostException -> RagError.NoConnection(this)
    is ConnectException -> RagError.NoConnection(this)
    is NoRouteToHostException -> RagError.NoConnection(this)
    // El resto de fallos de entrada/salida son, en la practica, red que se corto a medias.
    is IOException -> RagError.NoConnection(this)
    else -> RagError.Unknown(this)
}

/**
 * Traduce una respuesta 4xx o 5xx.
 *
 * Manda el **codigo del cuerpo** cuando existe, porque es mas especifico que el codigo HTTP:
 * el contrato permite devolver `LLM_UNAVAILABLE` con un 503, y para la UI no es lo mismo que
 * un 503 por indice no listo. Si el cuerpo no se puede leer o no encaja con el contrato, se
 * cae al codigo HTTP, que siempre esta.
 */
private fun HttpException.httpToRagError(): RagError {
    val body = readErrorBody()
    val detail = body?.message.orEmpty()
    return when (body?.code) {
        ApiErrorDto.EQUIPMENT_NOT_FOUND -> RagError.NotFound(detail)
        ApiErrorDto.LLM_UNAVAILABLE -> RagError.LlmUnavailable(detail)
        ApiErrorDto.INDEX_NOT_READY,
        ApiErrorDto.RATE_LIMITED,
        ApiErrorDto.INTERNAL_ERROR -> RagError.ServerError(body.code, detail)

        else -> when (val status = code()) {
            HTTP_NOT_FOUND -> RagError.NotFound(detail)
            in HTTP_SERVER_ERROR_RANGE -> RagError.ServerError(status.toString(), detail)
            else -> RagError.Unknown(this)
        }
    }
}

/**
 * Lee el sobre de error, o `null` si no se puede.
 *
 * Se traga cualquier fallo a proposito: no poder leer el cuerpo de un error no debe
 * convertirse en un segundo error que tape al primero.
 */
private fun HttpException.readErrorBody(): ApiErrorDto? = try {
    response()?.errorBody()?.string()
        ?.takeIf { it.isNotBlank() }
        ?.let { errorJson.decodeFromString<ApiErrorEnvelopeDto>(it).error }
} catch (ignored: Exception) {
    null
}

private const val HTTP_NOT_FOUND = 404
private val HTTP_SERVER_ERROR_RANGE = 500..599

private val errorJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
