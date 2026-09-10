package ec.edu.uteq.labscan.data.remote

import ec.edu.uteq.labscan.data.remote.dto.ChatRole
import ec.edu.uteq.labscan.data.remote.dto.EquipmentDto
import ec.edu.uteq.labscan.data.remote.dto.SourceDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Asistente que habla con Claude **directamente desde el telefono**, con la clave del propio
 * estudiante y sin backend de por medio.
 *
 * ### Por que existe, y en que se diferencia del backend
 *
 * El backend RAG sigue existiendo y sigue siendo mejor, pero obliga a que alguien tenga un
 * PC encendido y accesible. En la revision del 2026-09-10 se decidio que la app tiene que
 * funcionar sola: cada estudiante pone su clave y consume su propia cuenta.
 *
 * Eso cambia de donde sale el contexto:
 *
 * | | Backend RAG | Este camino |
 * |---|---|---|
 * | Fuente | 1888 fragmentos de manuales | la ficha del equipo, dentro del APK |
 * | Busqueda | semantica sobre todo el corpus | ninguna, va la ficha entera |
 * | Necesita | un PC encendido | solo la clave del estudiante |
 *
 * El corpus completo son unos 837 000 tokens y **no cabe** en la ventana del modelo, asi que
 * sin servidor no hay forma de buscar en el. Lo que si cabe, y de sobra, es la ficha del
 * equipo que el estudiante esta mirando: son unos cientos de palabras. La respuesta sale de
 * ahi y cita las mismas fuentes que la ficha declara, asi que la regla 6 de CLAUDE.md se
 * sigue cumpliendo: toda respuesta muestra de donde sale.
 *
 * ### Sobre la clave
 *
 * Es la clave **del estudiante**, que el escribe en su telefono. No es la del proyecto y no
 * viaja en el APK, que era lo que habia que evitar. Se guarda en DataStore, en el
 * almacenamiento privado de la app, y **no se escribe nunca en el registro**.
 */
class DirectAssistant(
    private val client: OkHttpClient,
    private val json: Json,
    private val model: String = MODEL
) {

    /**
     * Pregunta sobre un equipo, con su ficha como unica fuente.
     *
     * @param apiKey clave del estudiante. Si viene vacia se devuelve [RagError.LlmUnavailable]:
     *   quien llama ya deberia haberla pedido.
     * @param equipment ficha del equipo detectado, o `null` si se pregunta sin equipo.
     */
    fun ask(
        apiKey: String,
        question: String,
        equipment: EquipmentDto?,
        history: List<ChatTurn>,
        voiceMode: Boolean
    ): Result<ChatAnswer> {
        if (apiKey.isBlank()) {
            return Result.failure(RagError.LlmUnavailable("Falta la clave de la API"))
        }

        // Sin ficha no hay fuente que citar, asi que no se llama al modelo. Es la misma regla
        // que aplica el backend: sin contexto no se pregunta, porque un modelo al que se le
        // pregunta sin fuentes responde de memoria y suena igual de seguro.
        if (equipment == null || equipment.classId.isBlank()) {
            return Result.success(
                ChatAnswer(
                    answer = SIN_EQUIPO,
                    hasSufficientContext = false,
                    sources = emptyList()
                )
            )
        }

        val cuerpo = MensajesRequest(
            model = model,
            maxTokens = if (voiceMode) 300 else 1024,
            system = SYSTEM_PROMPT + (if (voiceMode) VOICE_RULES else "") + fichaComoContexto(equipment),
            messages = history.takeLast(HISTORIAL * 2).map {
                Mensaje(
                    role = if (it.role == ChatRole.USER) "user" else "assistant",
                    content = it.content
                )
            } + Mensaje(role = "user", content = question)
        )

        val peticion = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .post(json.encodeToString(cuerpo).toRequestBody(JSON_MEDIA))
            .build()

        return try {
            client.newCall(peticion).execute().use { respuesta ->
                val texto = respuesta.body?.string().orEmpty()
                if (!respuesta.isSuccessful) {
                    return Result.failure(aError(respuesta.code, texto))
                }
                val decodificada = json.decodeFromString<MensajesResponse>(texto)
                val contenido = decodificada.content
                    .filter { it.type == "text" }
                    .joinToString("\n") { it.text.orEmpty() }
                    .trim()

                if (contenido.isEmpty()) {
                    return Result.failure(RagError.LlmUnavailable("Respuesta vacia"))
                }

                Result.success(
                    ChatAnswer(
                        answer = contenido,
                        hasSufficientContext = true,
                        // Las fuentes son las que la propia ficha declara. No las inventa el
                        // modelo, igual que en el backend.
                        sources = equipment.sources
                    )
                )
            }
        } catch (error: IOException) {
            Result.failure(RagError.NoConnection(error))
        } catch (error: Exception) {
            Result.failure(RagError.Unknown(error))
        }
    }

    /**
     * Traduce el codigo HTTP de Anthropic a los errores que la interfaz ya sabe pintar.
     *
     * El 401 se trata como [RagError.LlmUnavailable] con un mensaje propio: es, con mucho, el
     * fallo mas probable aqui, y "clave incorrecta" es accionable mientras que "el asistente
     * no esta disponible" manda al estudiante a mirar donde no es.
     */
    private fun aError(codigo: Int, cuerpo: String): RagError = when (codigo) {
        401, 403 -> RagError.LlmUnavailable(
            "La clave de la API no es valida o no tiene permiso. Revisela en Ajustes."
        )
        429 -> RagError.ServerError(
            "RATE_LIMITED",
            "Demasiadas consultas seguidas. Espere unos segundos."
        )
        // 400 con este mensaje significa saldo agotado, y es lo segundo que mas va a pasar.
        400 -> if (cuerpo.contains("credit", ignoreCase = true)) {
            RagError.LlmUnavailable("La cuenta no tiene saldo. Recargue en console.anthropic.com.")
        } else {
            RagError.ServerError("BAD_REQUEST", "La consulta no tenia el formato esperado.")
        }
        in 500..599 -> RagError.ServerError("UPSTREAM", "El servicio de Claude no respondio.")
        else -> RagError.Unknown()
    }

    private fun fichaComoContexto(equipment: EquipmentDto): String = buildString {
        append("\n\n--- FICHA DEL EQUIPO QUE EL ESTUDIANTE TIENE DELANTE ---\n\n")
        append("Equipo: ${equipment.displayName}\n\n")
        if (equipment.shortDescription.isNotBlank()) {
            append("Descripción: ${equipment.shortDescription}\n\n")
        }
        if (equipment.function.isNotBlank()) {
            append("Función: ${equipment.function}\n\n")
        }
        seccion("Componentes", equipment.components)
        seccion("Procedimiento básico, en orden", equipment.basicProcedure)
        seccion("Equipo de protección personal", equipment.ppe)
        seccion("Riesgos", equipment.risks)
        seccion("Prácticas relacionadas", equipment.relatedPractices)
        if (equipment.sources.isNotEmpty()) {
            append("Esta ficha se elaboró a partir de: ")
            append(equipment.sources.joinToString("; ") { it.title })
            append("\n")
        }
    }

    private fun StringBuilder.seccion(titulo: String, valores: List<String>) {
        if (valores.isEmpty()) return
        append("$titulo:\n")
        valores.forEach { append("- $it\n") }
        append("\n")
    }

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"

        /**
         * El mismo modelo que usaba el backend.
         *
         * Se conserva porque ahora paga el estudiante: es el mas barato de la familia y para
         * responder sobre una ficha de unos cientos de palabras sobra. Cambiarlo por uno mayor
         * multiplicaria el coste de cada pregunta sin mejorar una respuesta que ya viene
         * acotada por la ficha.
         */
        const val MODEL = "claude-haiku-4-5"

        private const val HISTORIAL = 6
        private val JSON_MEDIA = "application/json".toMediaType()

        /** Lo que se responde cuando se pregunta sin haber tocado ningun equipo. */
        const val SIN_EQUIPO =
            "Apunte la cámara a un equipo y toque su cuadro para que pueda responder sobre él. " +
                "Sin equipo seleccionado no tengo ninguna ficha de la que sacar la respuesta."

        private const val SYSTEM_PROMPT = """Eres el asistente del laboratorio de la Universidad Técnica Estatal de Quevedo (UTEQ).
Ayudas a estudiantes de primer semestre que nunca han usado estos equipos.

Reglas que no puedes romper:

1. Responde ÚNICAMENTE con la información de la ficha que te entrego más abajo. No completes
   con conocimiento general, aunque estés seguro de la respuesta y aunque el equipo te resulte
   familiar.
2. Si la ficha no alcanza para responder, dilo con claridad y recomienda consultar al docente
   o al responsable del laboratorio. No inventes un procedimiento parecido ni rellenes huecos.
3. Escribe para alguien sin experiencia: nada de jerga sin explicar, y los pasos en el orden
   exacto en que se ejecutan.
4. Si la ficha menciona un riesgo, una precaución o un equipo de protección para lo que se está
   preguntando, adviértelo aunque no te lo hayan preguntado.
5. Nunca te inventes números de página, títulos de manual ni nombres de práctica.

Escribe en español, en segunda persona y de forma directa.

FORMATO. La app muestra tu respuesta en texto plano que NO renderiza markdown. Todo lo que
escribas con sintaxis de markdown se le enseña al estudiante tal cual, con los símbolos a la
vista. Por lo tanto: nada de almohadillas, nada de asteriscos, nada de emojis. Para enumerar
pasos usa líneas que empiecen por "1.", "2.", "3.". Separa las ideas en párrafos cortos."""

        private const val VOICE_RULES = """

MODO DE VOZ. Tu respuesta se va a leer en voz alta con un sintetizador. Máximo tres oraciones,
sin listas, sin viñetas y sin markdown. Registro hablado: frases cortas, como se lo explicarías
a alguien que tiene las manos ocupadas."""
    }
}

// --- Cuerpo de la API de Anthropic -------------------------------------------------------
//
// Se declaran aqui y a mano, con kotlinx.serialization, en lugar de traerse el SDK de Java de
// Anthropic. El SDK arrastra dependencias pensadas para servidor y la regla 8 de CLAUDE.md
// pide justificar cada dependencia nueva: para una sola llamada POST no compensa.

@Serializable
private data class MensajesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<Mensaje>
)

@Serializable
private data class Mensaje(
    val role: String,
    val content: String
)

@Serializable
private data class MensajesResponse(
    val content: List<BloqueContenido> = emptyList()
)

@Serializable
private data class BloqueContenido(
    val type: String = "",
    val text: String? = null
)
