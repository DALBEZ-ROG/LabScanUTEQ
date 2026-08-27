package ec.edu.uteq.labscan.data

import ec.edu.uteq.labscan.data.remote.dto.ApiErrorEnvelopeDto
import ec.edu.uteq.labscan.data.remote.dto.ChatResponseDto
import ec.edu.uteq.labscan.data.remote.dto.EquipmentDto
import ec.edu.uteq.labscan.data.remote.dto.HealthDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifica que los JSON de `assets/mock/` son respuestas validas del contrato.
 *
 * El mock existe para poder terminar la app sin backend, y eso solo vale si lo que devuelve
 * es indistinguible de lo que devolvera el backend real. Si estos archivos se desvian del
 * contrato, la app se construye contra una mentira y el dia de la integracion falla todo a
 * la vez.
 *
 * Se usa un [Json] **estricto**, sin `ignoreUnknownKeys`: al reves que en la app, aqui un
 * campo mal escrito debe romper. En produccion ese mismo campo se ignoraria en silencio y la
 * seccion apareceria vacia sin explicacion.
 */
class MockAssetsTest {

    private val strict = Json
    private val mockDir = File("src/debug/assets/mock")

    private fun read(name: String): String {
        val file = File(mockDir, name)
        assertTrue("No se encontro ${file.absolutePath}", file.exists())
        return file.readText()
    }

    @Test
    fun `health responde ok`() {
        val health = strict.decodeFromString<HealthDto>(read("health.json"))
        assertTrue("El mock de health deberia estar sano", health.isHealthy)
        assertTrue("Sin documentos indexados el RAG no tiene nada que citar", health.indexedDocuments > 0)
    }

    @Test
    fun `cada ficha de mock encaja con el contrato de equipment`() {
        val fichas = mockDir.listFiles { file -> file.name.startsWith("equipment_") }.orEmpty()
        assertTrue("No hay fichas de mock", fichas.isNotEmpty())

        fichas.forEach { file ->
            val equipment = strict.decodeFromString<EquipmentDto>(file.readText())
            // El nombre del archivo es la ruta del endpoint: si no coinciden, el
            // MockInterceptor devolveria la ficha de otro equipo.
            val expectedId = file.name.removePrefix("equipment_").removeSuffix(".json")
            assertEquals("El classId no coincide con el nombre de ${file.name}", expectedId, equipment.classId)
            assertTrue("displayName vacio en ${file.name}", equipment.displayName.isNotBlank())
            assertTrue("Sin fuentes en ${file.name}", equipment.sources.isNotEmpty())
        }
    }

    @Test
    fun `hay una clase sin ficha de mock para poder probar la caida al catalogo`() {
        val labels = File("src/main/assets/labels.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val conMock = labels.filter { File(mockDir, "equipment_$it.json").exists() }

        assertTrue("El mock no cubre ninguna clase", conMock.isNotEmpty())
        // Si el mock respondiera 200 para las cuatro clases, el camino 404 -> catalogo local
        // no se ejercitaria nunca en el dispositivo.
        assertTrue(
            "Todas las clases tienen mock: no queda forma de probar el 404",
            conMock.size < labels.size
        )
    }

    @Test
    fun `los dos casos de chat estan y son opuestos`() {
        val conContexto = strict.decodeFromString<ChatResponseDto>(read("chat.json"))
        val sinContexto = strict.decodeFromString<ChatResponseDto>(read("chat_sin_contexto.json"))

        assertTrue("chat.json debe tener contexto suficiente", conContexto.hasSufficientContext)
        assertTrue("Sin fuentes no se cumple la regla 6", conContexto.sources.isNotEmpty())

        assertFalse("chat_sin_contexto.json debe declarar contexto insuficiente", sinContexto.hasSufficientContext)
        // El contrato lo exige: sin contexto no se citan fuentes.
        assertTrue("Una respuesta sin contexto no cita fuentes", sinContexto.sources.isEmpty())
        assertTrue("Falta el aviso de consultar al docente", sinContexto.answer.contains("docente"))
    }

    @Test
    fun `los dos casos de voz existen y respetan el registro hablado`() {
        val conContexto = strict.decodeFromString<ChatResponseDto>(read("chat_voz.json"))
        val sinContexto =
            strict.decodeFromString<ChatResponseDto>(read("chat_voz_sin_contexto.json"))

        assertTrue("chat_voz.json debe tener contexto suficiente", conContexto.hasSufficientContext)
        assertTrue("Sin fuentes no se cumple la regla 6", conContexto.sources.isNotEmpty())
        assertFalse(
            "chat_voz_sin_contexto.json debe declarar contexto insuficiente",
            sinContexto.hasSufficientContext
        )
        assertTrue("Una respuesta sin contexto no cita fuentes", sinContexto.sources.isEmpty())
    }

    @Test
    fun `las respuestas de voz caben en tres oraciones y no traen markdown`() {
        // Es el contrato de docs/CONTRATO_API.md para voiceMode. Se comprueba sobre el mock
        // porque es el unico backend que existe hoy: cuando llegue el real, esta prueba
        // sigue describiendo lo que se le pide.
        for (name in listOf("chat_voz.json", "chat_voz_sin_contexto.json")) {
            val respuesta = strict.decodeFromString<ChatResponseDto>(read(name)).answer

            val oraciones = respuesta.count { it == '.' || it == '?' || it == '!' }
            assertTrue("$name pasa de tres oraciones: $oraciones", oraciones <= 3)

            for (marca in listOf("*", "#", "- ", "http", "](", "p.")) {
                assertFalse(
                    "$name no debe traer \"$marca\": no se puede leer en voz alta",
                    respuesta.contains(marca)
                )
            }
        }
    }

    @Test
    fun `el error de mock usa el sobre estandar`() {
        val envelope = strict.decodeFromString<ApiErrorEnvelopeDto>(read("error_not_found.json"))
        assertEquals("EQUIPMENT_NOT_FOUND", envelope.error.code)
        assertTrue("El mensaje de error deberia venir en espanol", envelope.error.message.isNotBlank())
    }
}
