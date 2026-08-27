package ec.edu.uteq.labscan.data.remote

import ec.edu.uteq.labscan.data.remote.dto.ChatRole
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Pruebas de la traduccion de fallos de red.
 *
 * Corren en la JVM, sin dispositivo: `toRagError` no toca ninguna clase de Android, y okhttp
 * y retrofit son bibliotecas de Java puro. Cubren lo que la tabla de docs/CONTRATO_API.md
 * promete que ve el usuario en cada situacion.
 */
class RagErrorTest {

    private fun httpError(code: Int, body: String): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaType()))
    )

    @Test
    fun `un timeout no se confunde con falta de red`() {
        // SocketTimeoutException tambien es IOException: si el orden de las ramas estuviera
        // mal, esto caeria en NoConnection y el usuario leeria el mensaje equivocado.
        val error = SocketTimeoutException("timeout").toRagError()
        assertTrue("Se esperaba Timeout y llego $error", error is RagError.Timeout)
        assertTrue("Un timeout se puede reintentar", error.retryable)
    }

    @Test
    fun `los fallos de socket y de DNS son falta de conexion`() {
        assertTrue(UnknownHostException("dns").toRagError() is RagError.NoConnection)
        assertTrue(ConnectException("refused").toRagError() is RagError.NoConnection)
        assertTrue(IOException("stream cerrado").toRagError() is RagError.NoConnection)
    }

    @Test
    fun `un 404 con el cuerpo del contrato es NotFound y no se reintenta`() {
        val error = httpError(
            404,
            """{"error":{"code":"EQUIPMENT_NOT_FOUND","message":"No existe."}}"""
        ).toRagError()

        assertTrue("Se esperaba NotFound y llego $error", error is RagError.NotFound)
        assertFalse("Reintentar un 404 no sirve de nada", error.retryable)
    }

    @Test
    fun `manda el codigo del cuerpo sobre el codigo HTTP`() {
        // El contrato permite devolver LLM_UNAVAILABLE con un 503. Para la interfaz no es lo
        // mismo que un 503 generico, asi que el cuerpo tiene que ganar.
        val error = httpError(
            503,
            """{"error":{"code":"LLM_UNAVAILABLE","message":"El modelo no responde."}}"""
        ).toRagError()

        assertTrue("Se esperaba LlmUnavailable y llego $error", error is RagError.LlmUnavailable)
    }

    @Test
    fun `un 500 sin cuerpo legible cae al codigo HTTP`() {
        val error = httpError(500, "<html>Bad Gateway</html>").toRagError()
        assertTrue("Se esperaba ServerError y llego $error", error is RagError.ServerError)
        assertEquals("500", (error as RagError.ServerError).code)
    }

    @Test
    fun `un codigo desconocido del backend se trata como error de servidor o desconocido`() {
        // Un codigo nuevo no debe romper la deserializacion ni acabar en un crash.
        val error = httpError(
            418,
            """{"error":{"code":"COFFEE_NOT_FOUND","message":"Soy una tetera."}}"""
        ).toRagError()
        assertTrue("Se esperaba Unknown y llego $error", error is RagError.Unknown)
    }

    @Test
    fun `el historial se recorta a los ultimos seis turnos`() {
        val history = (1..10).map {
            ChatTurn(
                role = if (it % 2 == 1) ChatRole.USER else ChatRole.ASSISTANT,
                content = "turno $it"
            )
        }

        val sent = history.truncateForRequest()

        assertEquals(RagRepository.MAX_HISTORY_TURNS, sent.size)
        // Se conserva el final, que es lo que da contexto a una pregunta de seguimiento.
        assertEquals("turno 5", sent.first().content)
        assertEquals("turno 10", sent.last().content)
    }

    @Test
    fun `un historial corto viaja entero`() {
        val history = listOf(ChatTurn(ChatRole.USER, "unica pregunta"))
        assertEquals(history, history.truncateForRequest())
    }
}
