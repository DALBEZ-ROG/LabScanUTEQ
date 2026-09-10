package ec.edu.uteq.labscan.ui.chat

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import ec.edu.uteq.labscan.R
import ec.edu.uteq.labscan.data.local.SettingsStore
import ec.edu.uteq.labscan.data.remote.ChatTurn
import ec.edu.uteq.labscan.data.remote.RagError
import ec.edu.uteq.labscan.data.remote.RagRepository
import ec.edu.uteq.labscan.data.remote.dto.ChatRole
import ec.edu.uteq.labscan.data.remote.dto.SourceDto
import ec.edu.uteq.labscan.di.AppContainer
import ec.edu.uteq.labscan.voice.TtsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Quien escribio un mensaje de la conversacion. */
enum class Author { USER, ASSISTANT }

/**
 * Un mensaje en pantalla.
 *
 * @param sources fuentes citadas. Siempre vacio cuando [hasSufficientContext] es `false`: el
 *   repositorio ya las descarta, y la burbuja no las pinta.
 * @param hasSufficientContext `false` marca la respuesta de "no tengo informacion suficiente".
 *   La burbuja va en ambar y sin fuentes (docs/CONTRATO_API.md).
 */
data class ChatMessage(
    val id: Long,
    val author: Author,
    val text: String,
    val sources: List<SourceDto> = emptyList(),
    val hasSufficientContext: Boolean = true,
    /** `true` si el backend contesto buscando en internet, no con los manuales. */
    val fromWeb: Boolean = false
)

/**
 * Fallo de la ultima pregunta.
 *
 * Va aparte de la lista de mensajes y no como una burbuja mas: un error de red no es algo que
 * dijera el asistente, y ademas tiene que poder desaparecer al reintentar sin dejar rastro en
 * la conversacion.
 */
data class ChatError(
    @param:StringRes val messageRes: Int,
    val retryable: Boolean
)

data class ChatUiState(
    /** Equipo en contexto, o `null` si el estudiante quito el contexto o entro sin el. */
    val classId: String? = null,
    /** Nombre legible del equipo, para el encabezado. */
    val equipmentName: String = "",
    val messages: List<ChatMessage> = emptyList(),
    /** `true` mientras se espera al backend: pinta el indicador de escritura. */
    val isSending: Boolean = false,
    val error: ChatError? = null
) {
    /**
     * Las sugerencias solo tienen sentido con un equipo en contexto y antes de la primera
     * pregunta: despues estorban, porque el estudiante ya sabe que puede preguntar.
     */
    val showSuggestions: Boolean get() = classId != null && messages.isEmpty() && !isSending
}

/**
 * Conversacion con el asistente RAG.
 *
 * ### Lo que sale de aqui
 *
 * Solo `classId` y texto (CLAUDE.md regla 5). El historial se manda entero a
 * [RagRepository.chat], que es quien lo recorta a los ultimos seis turnos: el tope vive en un
 * unico sitio, junto al contrato que lo fija, y no repartido entre el repositorio y cada
 * pantalla que llame.
 *
 * ### Voz
 *
 * El ViewModel decide **cuando** se lee, no como. Lee la respuesta del asistente y nunca las
 * fuentes ni los errores de red: una lista de titulos y numeros de pagina dicha en voz alta no
 * ayuda a nadie, y un "error 503" menos.
 */
class ChatViewModel(
    private val ragRepository: RagRepository,
    private val ttsManager: TtsManager,
    private val settingsStore: SettingsStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** `true` mientras el motor de voz habla. La UI cambia el altavoz por un "detener". */
    val isSpeaking: StateFlow<Boolean> = ttsManager.isSpeaking

    /** `false` si el dispositivo no tiene motor de voz: los altavoces se deshabilitan. */
    val isVoiceReady: StateFlow<Boolean> = ttsManager.isReady

    /** Pregunta que fallo, para poder reintentarla sin que el estudiante la reescriba. */
    private var failedQuestion: String? = null

    /**
     * Si la ultima pregunta venia del modo de voz.
     *
     * Se guarda para que [retry] reintente en el mismo registro: reintentar una pregunta
     * hablada como si fuera escrita devolveria un parrafo con vinetas que luego habria que
     * leer en voz alta.
     */
    private var lastWasVoice: Boolean = false

    private var nextId = 0L

    /**
     * Fija el equipo del que se habla.
     *
     * Se llama una sola vez, al entrar desde la ficha tecnica. El nombre legible se pide al
     * repositorio, que ya sabe caer al catalogo local si no hay red: el encabezado del chat no
     * puede quedarse en blanco por un problema de conexion.
     */
    fun setEquipmentContext(classId: String?) {
        if (classId.isNullOrBlank() || _uiState.value.classId == classId) return
        _uiState.update { it.copy(classId = classId, equipmentName = classId.toDisplayName()) }

        viewModelScope.launch {
            ragRepository.equipment(classId).onSuccess { equipment ->
                val name = equipment.details.displayName.ifBlank { classId.toDisplayName() }
                _uiState.update {
                    // Puede haberse quitado el contexto mientras se cargaba.
                    if (it.classId == classId) it.copy(equipmentName = name) else it
                }
            }
        }
    }

    /**
     * Quita el equipo del contexto.
     *
     * El historial se conserva: el estudiante sigue la misma conversacion, solo que a partir
     * de ahora el backend responde con contexto general del laboratorio. El contrato permite
     * `classId` nulo justo para esto.
     */
    fun clearEquipmentContext() {
        _uiState.update { it.copy(classId = null, equipmentName = "") }
    }

    /**
     * Envia una pregunta escrita, dictada o hablada.
     *
     * @param voiceMode `true` cuando llega del modo de conversacion por voz de F8. Cambia dos
     *   cosas: la peticion lleva la bandera que le pide al backend registro hablado, y **no**
     *   se lee la respuesta aqui. Lo segundo es importante: en modo de voz quien habla es la
     *   maquina de estados de la conversacion, que necesita partir la respuesta en frases y
     *   saber cuando termino para reabrir el microfono. Si ademas leyera este ViewModel, se
     *   oiria la respuesta dos veces.
     */
    fun send(question: String, voiceMode: Boolean = false) {
        val text = question.trim()
        if (text.isEmpty() || _uiState.value.isSending) return

        // Callar antes de preguntar: si el asistente sigue leyendo la respuesta anterior, la
        // nueva se le encimaria.
        ttsManager.stop()

        lastWasVoice = voiceMode
        failedQuestion = text
        _uiState.update { state ->
            state.copy(
                messages = state.messages + ChatMessage(
                    id = nextId++,
                    author = Author.USER,
                    text = text
                ),
                isSending = true,
                error = null
            )
        }
        ask(text, voiceMode)
    }

    /**
     * Reintenta la ultima pregunta fallida.
     *
     * No vuelve a agregar la burbuja del usuario: ya esta en la lista desde el primer intento,
     * y duplicarla daria la impresion de haber preguntado dos veces.
     */
    fun retry() {
        val text = failedQuestion ?: return
        if (_uiState.value.isSending) return
        _uiState.update { it.copy(isSending = true, error = null) }
        ask(text, lastWasVoice)
    }

    /** Descarta el aviso de error sin reintentar. */
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun ask(question: String, voiceMode: Boolean) {
        viewModelScope.launch {
            val history = _uiState.value.messages
                // La pregunta que se esta enviando no forma parte de su propio historial.
                .dropLast(1)
                .map { message ->
                    ChatTurn(
                        role = if (message.author == Author.USER) ChatRole.USER else ChatRole.ASSISTANT,
                        content = message.text
                    )
                }

            val result = ragRepository.chat(
                classId = _uiState.value.classId,
                message = question,
                history = history,
                voiceMode = voiceMode
            )

            result
                .onSuccess { answer ->
                    failedQuestion = null
                    val message = ChatMessage(
                        id = nextId++,
                        author = Author.ASSISTANT,
                        text = answer.answer,
                        sources = answer.sources,
                        hasSufficientContext = answer.hasSufficientContext,
                        fromWeb = answer.fromWeb
                    )
                    _uiState.update {
                        it.copy(messages = it.messages + message, isSending = false, error = null)
                    }
                    // En modo de voz lee la maquina de estados de la conversacion, no aqui.
                    if (!voiceMode) maybeReadAloud(message)
                }
                .onFailure { error ->
                    val ragError = error as? RagError
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            error = ChatError(
                                messageRes = ragError?.messageRes ?: R.string.error_red_desconocido,
                                retryable = ragError?.retryable ?: true
                            )
                        )
                    }
                    // Deliberadamente NO se lee el error en voz alta.
                }
        }
    }

    /**
     * Lee la respuesta si el ajuste esta activo.
     *
     * Se lee tambien cuando `hasSufficientContext` es `false`: ese texto le dice al estudiante
     * que consulte al docente, que es informacion util y probablemente la que mas necesita
     * escuchar. Lo que no se lee, nunca, es la lista de fuentes.
     */
    private fun maybeReadAloud(message: ChatMessage) {
        viewModelScope.launch {
            if (settingsStore.autoReadAnswers.first()) {
                ttsManager.speak(message.text)
            }
        }
    }

    /** Altavoz de una burbuja: lee, o corta si ya estaba leyendo. */
    fun toggleSpeak(message: ChatMessage) {
        if (isSpeaking.value) {
            ttsManager.stop()
        } else {
            ttsManager.speak(message.text)
        }
    }

    /** Corta la lectura. La pantalla lo llama al salir y al pulsar el microfono. */
    fun stopSpeaking() {
        ttsManager.stop()
    }

    companion object {
        /** Preguntas con las que arranca un estudiante que no sabe que preguntar. */
        val SUGGESTIONS = listOf(
            R.string.chat_sugerencia_encender,
            R.string.chat_sugerencia_apagar,
            R.string.chat_sugerencia_proteccion,
            R.string.chat_sugerencia_riesgos
        )

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras
                ): T = ChatViewModel(
                    ragRepository = container.ragRepository,
                    ttsManager = container.ttsManager,
                    settingsStore = container.settingsStore
                ) as T
            }
    }
}

/** `camara_electroforesis` -> `Camara electroforesis`. Respaldo mientras carga el nombre real. */
private fun String.toDisplayName(): String =
    replace('_', ' ').replaceFirstChar { it.uppercase() }
