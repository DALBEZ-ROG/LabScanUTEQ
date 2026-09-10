package ec.edu.uteq.labscan.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Quien dijo cada turno del historial.
 *
 * El contrato usa `"user"` y `"assistant"` en minusculas; los `@SerialName` son el unico
 * sitio del proyecto donde esa traduccion existe.
 */
@Serializable
enum class ChatRole {
    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT
}

/** Un turno del historial de la conversacion. */
@Serializable
data class ChatMessageDto(
    val role: ChatRole,
    val content: String
)

/**
 * Cuerpo de `POST /api/chat`.
 *
 * CLAUDE.md, regla 5, y requisito explicito de la actividad academica: aqui **solo** viaja
 * texto y el `classId`. Nunca un frame, una imagen ni un documento. Si alguna vez hace falta
 * agregar un campo a este DTO, revisar primero que no sea una via para colar contenido
 * binario.
 *
 * @param classId puede ser `null` cuando el estudiante abre el chat sin haber tocado ningun
 *   equipo. El backend responde entonces con contexto general del laboratorio.
 * @param history maximo los ultimos 6 turnos. Lo trunca
 *   [ec.edu.uteq.labscan.data.remote.RagRepository] antes de construir este objeto.
 * @param voiceMode `true` cuando la pregunta viene del modo de conversacion por voz (F8).
 *   Le pide al backend que responda en registro hablado: como maximo tres oraciones, sin
 *   listas, sin vinetas, sin markdown y sin URLs ni numeros de pagina dentro del texto. Las
 *   fuentes siguen viniendo en `sources`, pero la app **no las lee en voz alta**, solo las
 *   muestra. Tiene valor por defecto para no cambiar nada del chat escrito de F6.
 */
@Serializable
data class ChatRequestDto(
    val classId: String? = null,
    val message: String,
    val history: List<ChatMessageDto> = emptyList(),
    val voiceMode: Boolean = false
)

/**
 * Respuesta de `POST /api/chat`.
 *
 * @param hasSufficientContext `false` cuando el RAG no encontro material suficiente. La
 *   interfaz lo pinta en ambar y no lee las fuentes en voz alta (docs/CONTRATO_API.md).
 *   Cuando es `false`, `sources` viene vacio.
 */
@Serializable
data class ChatResponseDto(
    val answer: String = "",
    val hasSufficientContext: Boolean = false,
    val sources: List<SourceDto> = emptyList(),
    /**
     * `true` cuando la respuesta NO salio de los manuales del laboratorio sino de una
     * busqueda en internet que hizo el backend.
     *
     * Tiene valor por defecto porque un backend anterior no lo envia, y en ese caso la
     * respuesta viene de los manuales, que es lo que dice `false`.
     */
    val fromWeb: Boolean = false
)
