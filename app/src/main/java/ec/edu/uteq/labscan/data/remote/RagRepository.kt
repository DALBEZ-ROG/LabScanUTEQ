package ec.edu.uteq.labscan.data.remote

import android.util.Log
import ec.edu.uteq.labscan.App
import ec.edu.uteq.labscan.data.local.EquipmentCatalog
import ec.edu.uteq.labscan.data.remote.dto.ChatMessageDto
import ec.edu.uteq.labscan.data.remote.dto.ChatRequestDto
import ec.edu.uteq.labscan.data.remote.dto.ChatRole
import ec.edu.uteq.labscan.data.remote.dto.EquipmentDto
import ec.edu.uteq.labscan.data.remote.dto.SourceDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** De donde salio el dato que se esta mostrando. */
enum class DataOrigin {
    /** Lo devolvio el backend en esta misma sesion. */
    NETWORK,

    /** Salio de `assets/catalog.json` porque la red o el backend no respondieron. */
    CACHE
}

/**
 * Ficha tecnica lista para la interfaz, con su procedencia.
 *
 * @param details los datos, con la forma exacta del contrato.
 * @param origin [DataOrigin.CACHE] hace que la ficha se muestre con la etiqueta
 *   "sin conexion".
 * @param fromCatalog `false` cuando ni el backend ni el catalogo local tenian la clase, y
 *   [details] es una ficha minima construida con el `classId`.
 * @param reason por que se cayo a la cache. `null` si el dato vino del backend.
 */
data class Equipment(
    val details: EquipmentDto,
    val origin: DataOrigin,
    val fromCatalog: Boolean,
    val reason: RagError? = null
)

/** Un turno de la conversacion, en el vocabulario de la app. */
data class ChatTurn(
    val role: ChatRole,
    val content: String
)

/**
 * Respuesta del asistente.
 *
 * @param hasSufficientContext `false` obliga a la interfaz a pintar la respuesta en ambar y
 *   a no leer las fuentes en voz alta (docs/CONTRATO_API.md, CLAUDE.md regla 6).
 */
data class ChatAnswer(
    val answer: String,
    val hasSufficientContext: Boolean,
    val sources: List<SourceDto>,
    /**
     * `true` si el backend respondio buscando en internet en vez de con los manuales del
     * laboratorio. La UI lo avisa en la propia burbuja: ver `ChatBubbles.kt`.
     */
    val fromWeb: Boolean = false
)

/**
 * Unico punto por el que la app habla con el backend RAG.
 *
 * Aplica la tabla de "Comportamiento de la app ante fallos" de docs/CONTRATO_API.md, y es
 * la frontera donde mueren las excepciones: hacia arriba solo salen [Result] y [RagError].
 *
 * ### Que sale de aqui y que no
 *
 * Por este repositorio viajan **solo** `classId` y texto. Nunca un frame, una imagen ni un
 * documento (CLAUDE.md regla 5, y requisito explicito de la actividad). El RAG lo hace el
 * backend; la app ni siquiera conoce los manuales.
 *
 * ### Hilos
 *
 * Todo el trabajo va a [ioDispatcher]. Los `suspend` de Retrofit ya cambian de hilo por su
 * cuenta, pero la lectura del catalogo local y el parseo del cuerpo de error tambien tienen
 * que salir del hilo principal, asi que se envuelve la operacion completa.
 */
class RagRepository(
    private val api: LabScanApi,
    private val catalog: EquipmentCatalog,
    private val connectivity: ConnectivityObserver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Comprueba si el backend puede atender.
     *
     * Devuelve `false` ante cualquier fallo, incluida la falta de red: quien pregunta solo
     * quiere saber si ofrecer el asistente o el modo sin conexion, y para eso todos los
     * motivos de "no" son el mismo.
     */
    suspend fun health(): Boolean = withContext(ioDispatcher) {
        if (!connectivity.isOnlineNow()) return@withContext false
        try {
            api.health().isHealthy
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(App.LOG_TAG, "El backend no responde: ${error.toRagError().message}")
            false
        }
    }

    /**
     * Ficha tecnica de un equipo, del backend o del catalogo local.
     *
     * Camino: si no hay red se va derecho al catalogo; si la hay, se pide al backend y
     * cualquier fallo (404, timeout, 5xx) tambien cae al catalogo. El resultado dice de
     * donde vino con [Equipment.origin].
     *
     * ### Por que devuelve `Result` si nunca falla
     *
     * Hoy siempre es `success`, porque el catalogo local es un respaldo total:
     * [EquipmentCatalog.find] nunca lanza y, si la clase no esta, devuelve una ficha minima.
     * La firma se mantiene con [Result] porque es lo que el resto de la app espera de una
     * operacion de red, y porque el dia que el respaldo deje de ser total (fichas descargadas
     * bajo demanda, por ejemplo) el tipo ya no habra que cambiarlo.
     */
    suspend fun equipment(classId: String): Result<Equipment> = withContext(ioDispatcher) {
        if (!connectivity.isOnlineNow()) {
            return@withContext Result.success(fromCatalog(classId, RagError.NoConnection()))
        }
        try {
            val remote = api.equipment(classId)
            Result.success(
                Equipment(
                    details = remote,
                    origin = DataOrigin.NETWORK,
                    fromCatalog = true
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            val ragError = error.toRagError()
            Log.w(App.LOG_TAG, "Ficha de '$classId' desde el catalogo local: ${ragError.message}")
            Result.success(fromCatalog(classId, ragError))
        }
    }

    /**
     * Pregunta al asistente.
     *
     * Aqui **no** hay respaldo local: una respuesta inventada sin RAG seria justo lo que la
     * regla 6 de CLAUDE.md prohibe. Si falla, falla, y la interfaz ofrece reintentar segun
     * [RagError.retryable].
     *
     * @param classId equipo seleccionado, o `null` si el estudiante abrio el chat sin tocar
     *   ninguna caja.
     * @param history conversacion previa completa. Se trunca aqui a los ultimos
     *   [MAX_HISTORY_TURNS] turnos antes de enviarla.
     * @param voiceMode `true` desde el modo de conversacion por voz. Solo viaja como bandera:
     *   el registro hablado lo decide el backend (docs/CONTRATO_API.md). La app no reescribe
     *   la respuesta, porque recortar a tres oraciones aqui podria cortar una advertencia de
     *   seguridad por la mitad.
     */
    suspend fun chat(
        classId: String?,
        message: String,
        history: List<ChatTurn>,
        voiceMode: Boolean = false
    ): Result<ChatAnswer> = withContext(ioDispatcher) {
        if (!connectivity.isOnlineNow()) {
            return@withContext Result.failure(RagError.NoConnection())
        }
        try {
            val response = api.chat(
                ChatRequestDto(
                    classId = classId,
                    message = message,
                    history = history.truncateForRequest().map {
                        ChatMessageDto(role = it.role, content = it.content)
                    },
                    voiceMode = voiceMode
                )
            )
            Result.success(
                ChatAnswer(
                    answer = response.answer,
                    hasSufficientContext = response.hasSufficientContext,
                    // El contrato ya manda `sources` vacio cuando no hay contexto, pero se
                    // fuerza aqui: la regla 6 dice que no se citan fuentes de una respuesta
                    // que el propio backend marca como insuficiente.
                    sources = if (response.hasSufficientContext) response.sources else emptyList(),
                    fromWeb = response.fromWeb
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            val ragError = error.toRagError()
            Log.w(App.LOG_TAG, "Fallo en /api/chat: ${ragError.message}")
            Result.failure(ragError)
        }
    }

    private suspend fun fromCatalog(classId: String, reason: RagError): Equipment {
        val entry = catalog.find(classId)
        return Equipment(
            details = entry.equipment,
            origin = DataOrigin.CACHE,
            fromCatalog = entry.fromCatalog,
            reason = reason
        )
    }

    companion object {
        /** Tope de turnos que viajan al backend, fijado por docs/CONTRATO_API.md. */
        const val MAX_HISTORY_TURNS = 6
    }
}

/**
 * Deja solo los ultimos [RagRepository.MAX_HISTORY_TURNS] turnos.
 *
 * Se queda con el final y no con el principio porque lo que da contexto a una pregunta de
 * seguimiento ("y para apagarlo?") es lo que se acaba de decir, no el saludo inicial.
 *
 * Es `internal` para poder probarla en la JVM sin dispositivo.
 */
internal fun List<ChatTurn>.truncateForRequest(): List<ChatTurn> =
    takeLast(RagRepository.MAX_HISTORY_TURNS)
