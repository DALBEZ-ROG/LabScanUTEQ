package ec.edu.uteq.labscan.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import ec.edu.uteq.labscan.App
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Lectura en voz alta, sobre `android.speech.tts.TextToSpeech`.
 *
 * Es de plataforma a proposito (CLAUDE.md): nada de servicios de voz en la nube. El texto no
 * sale del telefono.
 *
 * ### Por que la inicializacion es asincrona
 *
 * `TextToSpeech` no esta listo cuando devuelve el constructor: el motor se enlaza en otro
 * proceso y avisa por `onInit` decimas de segundo despues. Si alguien llama a [speak] en ese
 * hueco, el texto se pierde en silencio, que es de los fallos mas confusos de depurar. Aqui
 * se guarda esa peticion en [pendingText] y se dice en cuanto el motor responde.
 *
 * ### Que no se lee nunca
 *
 * Ni la lista de fuentes ni los mensajes de error de red. Eso no lo decide esta clase sino
 * quien la llama, pero conviene tenerlo escrito donde se lee: la voz es para el contenido,
 * no para la ficha bibliografica ni para los avisos tecnicos.
 */
class TtsManager(
    context: Context,
    /**
     * Foco de audio. Se pide antes de hablar y se suelta al terminar, para que el asistente
     * no se superponga a lo que el estudiante tenga sonando y para que el sistema pueda
     * apartarlo cuando entre una llamada.
     */
    private val audioFocus: AudioFocusController
) {

    private val appContext = context.applicationContext

    private val _isSpeaking = MutableStateFlow(false)

    /** `true` mientras el motor esta pronunciando algo. La UI lo usa para el boton de parar. */
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _speechCompleted = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Se emite cuando el motor termina de decir **todo** lo que se le encolo.
     *
     * El modo de conversacion por voz lo necesita para saber cuando vuelve a abrir el
     * microfono. Con [isSpeaking] no bastaria: entre dos frases encoladas hay un instante en
     * que el motor no esta pronunciando y la bandera parpadearia, reabriendo el microfono a
     * mitad de la respuesta.
     */
    val speechCompleted: SharedFlow<Unit> = _speechCompleted.asSharedFlow()

    /**
     * Identificador de la ultima locucion encolada.
     *
     * Es lo que distingue "termino una frase" de "termino la respuesta": solo cuando acaba
     * **esta** se baja la bandera, se suelta el foco y se avisa por [speechCompleted].
     */
    private var lastUtteranceId: String? = null

    /** `true` si esta instancia tiene pedido el foco ahora mismo. Mantiene la cuenta cuadrada. */
    private var holdsFocus = false

    private val _isReady = MutableStateFlow(false)

    /**
     * `false` hasta que el motor responde, y tambien si no hay ningun motor instalado.
     *
     * La interfaz lo usa para deshabilitar los botones de altavoz en lugar de ofrecer algo
     * que no va a sonar.
     */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /** Texto pedido antes de que el motor estuviera listo. Se dice en cuanto lo este. */
    private var pendingText: String? = null

    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configure()
            } else {
                Log.w(App.LOG_TAG, "No hay motor de sintesis de voz disponible (status=$status)")
                _isReady.value = false
            }
        }
    }

    private fun configure() {
        val tts = engine ?: return

        // Voz de asistente, no multimedia: el sistema la enruta como habla y no la mezcla
        // con la musica del estudiante como si fuera una cancion mas.
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                // Solo la ultima de la cola cierra el turno. Las intermedias no: entre frase
                // y frase el asistente sigue teniendo la palabra.
                if (utteranceId == lastUtteranceId) finishSpeaking(notify = true)
            }

            @Deprecated("La firma sin errorCode sigue siendo obligatoria de implementar")
            override fun onError(utteranceId: String?) {
                finishSpeaking(notify = true)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(App.LOG_TAG, "Fallo la lectura en voz alta (error=$errorCode)")
                // Se avisa igual que si hubiera terminado: quien espera para volver a
                // escuchar no puede quedarse colgado porque el motor fallara.
                finishSpeaking(notify = true)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                // Sin aviso: a `stop()` se llega porque alguien decidio callar al asistente,
                // y ese alguien ya sabe lo que viene despues.
                finishSpeaking(notify = false)
            }
        })

        val chosen = selectLocale(tts)
        Log.i(App.LOG_TAG, "Voz lista, idioma: ${chosen?.toLanguageTag() ?: "por defecto"}")
        _isReady.value = true

        // Si alguien pidio hablar mientras el motor arrancaba, ahora si.
        pendingText?.let { text ->
            pendingText = null
            speak(text)
        }
    }

    /**
     * Elige el idioma mas cercano al del estudiante.
     *
     * Se intenta primero es-EC, que es como se habla aqui. Casi ningun dispositivo trae esa
     * variante instalada, asi que el respaldo real es es-ES; y si tampoco, se deja el idioma
     * por defecto del sistema. Leer con acento peninsular es peor que leer con el de aqui,
     * pero infinitamente mejor que no leer.
     *
     * @return el locale que quedo activo, o `null` si no se pudo fijar ninguno de los dos.
     */
    private fun selectLocale(tts: TextToSpeech): Locale? {
        for (candidate in PREFERRED_LOCALES) {
            val result = tts.setLanguage(candidate)
            val usable = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            if (usable) return candidate
            Log.d(App.LOG_TAG, "Idioma no disponible: ${candidate.toLanguageTag()}")
        }
        return null
    }

    /**
     * Lee [text] en voz alta, interrumpiendo lo que estuviera diciendo.
     *
     * El texto se limpia antes con [sanitizeForSpeech]: el backend responde en un markdown
     * ligero y el motor pronunciaria los asteriscos.
     *
     * Se usa `QUEUE_FLUSH` y no `QUEUE_ADD` porque cada respuesta sustituye a la anterior. Si
     * se encolaran, mandar tres preguntas seguidas dejaria al estudiante escuchando la
     * primera mientras lee la tercera.
     */
    fun speak(text: String) {
        val clean = sanitizeForSpeech(text)
        if (clean.isBlank()) return

        val tts = engine
        if (tts == null || !_isReady.value) {
            // Todavia arrancando: se guarda para decirlo en configure().
            pendingText = clean
            return
        }

        acquireFocus()
        lastUtteranceId = UTTERANCE_ID
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTERANCE_ID)
    }

    /**
     * Lee una respuesta larga partida en frases, encolandolas.
     *
     * ### Para que sirve partirla
     *
     * Para que la primera palabra suene antes. Un motor de sintesis no empieza a sonar hasta
     * que ha sintetizado la locucion que se le dio, y ese trabajo crece con la longitud del
     * texto. Dandole primero una frase corta, el estudiante oye la respuesta empezar mientras
     * el motor todavia esta preparando el resto.
     *
     * No es la tecnica de una respuesta que llega en trozos por la red: `POST /api/chat`
     * devuelve el texto completo de una vez, asi que aqui no se gana esperando menos al
     * backend, se gana esperando menos al sintetizador. La medida esta en `docs/PROGRESO.md`.
     *
     * @param text respuesta completa del asistente. Se limpia y se parte aqui.
     * @return `true` si se encolo algo. `false` si no habia nada que decir.
     */
    fun speakSentences(text: String): Boolean {
        val clean = sanitizeForSpeech(text)
        if (clean.isBlank()) return false

        val tts = engine
        if (tts == null || !_isReady.value) {
            pendingText = clean
            return true
        }

        val sentences = splitIntoSentences(clean)
        if (sentences.isEmpty()) return false

        acquireFocus()
        // El identificador tiene que fijarse ANTES de encolar: con frases muy cortas el
        // motor puede terminar la primera mientras todavia se esta encolando la segunda, y
        // si `lastUtteranceId` no estuviera puesto ya, ese `onDone` cerraria el turno entero.
        lastUtteranceId = "$UTTERANCE_ID-${sentences.lastIndex}"

        sentences.forEachIndexed { index, sentence ->
            // La primera vacia la cola, sustituyendo lo que hubiera; las demas se suman.
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(sentence, queueMode, Bundle(), "$UTTERANCE_ID-$index")
        }
        return true
    }

    private fun acquireFocus() {
        if (holdsFocus) return
        holdsFocus = audioFocus.request()
    }

    /** Baja la bandera, suelta el foco y, si toca, avisa de que el asistente acabo. */
    private fun finishSpeaking(notify: Boolean) {
        lastUtteranceId = null
        _isSpeaking.value = false
        if (holdsFocus) {
            holdsFocus = false
            audioFocus.abandon()
        }
        if (notify) _speechCompleted.tryEmit(Unit)
    }

    /**
     * Corta la lectura.
     *
     * Se llama al salir de la pantalla, al enviar otra pregunta y al pulsar el microfono:
     * hablarle al asistente mientras el asistente habla no funciona, porque el reconocedor
     * se oye a si mismo.
     */
    fun stop() {
        pendingText = null
        engine?.stop()
        // `stop()` no siempre dispara onStop en todos los motores, asi que se cierra a mano:
        // un boton de "detener" que se queda pegado es peor que un aviso de mas, y un foco de
        // audio que no se suelta deja la musica del estudiante bajada para siempre.
        finishSpeaking(notify = false)
    }

    /**
     * Suelta el motor. Despues de esto la instancia ya no sirve.
     *
     * Lo llama `AppContainer.close()`. No se llama al salir de una pantalla: el motor tarda
     * en enlazarse y reconstruirlo en cada navegacion se nota como un retardo antes de la
     * primera palabra.
     */
    fun shutdown() {
        stop()
        engine?.shutdown()
        engine = null
        _isReady.value = false
    }

    private companion object {
        /**
         * Orden de preferencia. Ecuador primero; Espana como respaldo realista, porque es la
         * voz en espanol que suele venir instalada.
         */
        val PREFERRED_LOCALES = listOf(
            Locale.forLanguageTag("es-EC"),
            Locale.forLanguageTag("es-ES")
        )

        /**
         * Identificador de locucion. Es unico porque solo se dice una cosa a la vez; lo que
         * importa es que **exista**: sin el, `UtteranceProgressListener` no recibe callbacks
         * y [isSpeaking] no se enteraria de nada.
         */
        const val UTTERANCE_ID = "labscan-respuesta"
    }
}

/**
 * Deja el texto listo para pronunciarse.
 *
 * El backend responde con markdown ligero, y un motor de sintesis lee los simbolos tal cual:
 * "asterisco asterisco importante asterisco asterisco". Tambien quita las referencias entre
 * corchetes, que en pantalla son utiles y en voz alta son ruido.
 *
 * Es una funcion suelta y no un metodo para poder probarla en la JVM sin dispositivo.
 */
internal fun sanitizeForSpeech(raw: String): String {
    var text = raw

    // Bloques de codigo y codigo en linea: se leen los backticks si no se quitan.
    text = text.replace(CODE_FENCE, " ")
    text = text.replace(INLINE_CODE, "$1")

    // Enlaces markdown: se conserva el texto visible y se tira la URL, que es impronunciable.
    text = text.replace(MARKDOWN_LINK, "$1")

    // Referencias sueltas entre corchetes: [1], [Manual CX23, p. 8]. En pantalla orientan;
    // dichas en voz alta interrumpen la frase.
    text = text.replace(BRACKET_REFERENCE, " ")

    // Enfasis y encabezados. Se hace despues de los enlaces para no romper su sintaxis.
    text = text.replace(EMPHASIS, "$2")
    text = text.replace(HEADING, "")

    // Vinetas al principio de linea: el guion se pronuncia "menos".
    text = text.replace(BULLET, "")

    // Cualquier resto de espacios dobles o saltos multiples se colapsa, para que el motor no
    // meta pausas de longitud aleatoria.
    return text.replace(WHITESPACE, " ").trim()
}

/**
 * Parte una respuesta en frases pronunciables.
 *
 * Corta por punto, interrogacion y exclamacion, que es lo que pide F8. Dos detalles que
 * importan mas de lo que parecen:
 *
 * - **Se conserva el signo final.** Un motor de sintesis usa la puntuacion para la entonacion:
 *   sin el signo de interrogacion, una pregunta se lee como una afirmacion.
 * - **Los fragmentos muy cortos se pegan al siguiente.** Al partir por punto, una abreviatura
 *   o un decimal ("40x", "1.5 ml") deja trozos de dos caracteres. Encolarlos por separado
 *   mete una pausa en mitad de la palabra y suena peor que no partir nada.
 *
 * Es `internal` para poder probarla en la JVM sin dispositivo.
 */
internal fun splitIntoSentences(text: String): List<String> {
    val pieces = SENTENCE_END.findAll(text)
        .map { it.value.trim() }
        .filter { it.isNotEmpty() }
        .toList()

    if (pieces.isEmpty()) return if (text.isBlank()) emptyList() else listOf(text.trim())

    val merged = mutableListOf<String>()
    for (piece in pieces) {
        val previous = merged.lastOrNull()
        if (previous != null && previous.length < MIN_SENTENCE_LENGTH) {
            merged[merged.lastIndex] = "$previous $piece"
        } else {
            merged.add(piece)
        }
    }
    return merged
}

/**
 * Un trozo hasta el siguiente `.`, `?` o `!`, con el signo incluido, o hasta el final.
 *
 * Los signos de apertura del espanol no cortan: `¿` y `¡` abren la frase, no la cierran.
 */
private val SENTENCE_END = Regex("[^.?!]+[.?!]*")

/** Por debajo de esto un "fragmento" es una abreviatura o un decimal, no una frase. */
private const val MIN_SENTENCE_LENGTH = 12

private val CODE_FENCE = Regex("```[\\s\\S]*?```")
private val INLINE_CODE = Regex("`([^`]*)`")
private val MARKDOWN_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
private val BRACKET_REFERENCE = Regex("\\[[^\\]]*]")
private val EMPHASIS = Regex("(\\*{1,3}|_{1,3})(.+?)\\1")
private val HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
private val BULLET = Regex("(?m)^\\s*[-*+]\\s+")
private val WHITESPACE = Regex("\\s+")
