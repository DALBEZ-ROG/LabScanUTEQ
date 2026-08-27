package ec.edu.uteq.labscan.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import ec.edu.uteq.labscan.App
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Lo que el sistema de audio le pide a la app. */
enum class AudioFocusEvent {
    /**
     * Callese y espere: hay algo mas importante sonando.
     *
     * Es lo que llega cuando entra una llamada telefonica, que es el caso que obliga a
     * tenerlo (requisito 8 de F8). Tambien lo manda una alarma o el asistente del sistema.
     */
    PAUSE,

    /** Se acabo la interrupcion. Se puede seguir. */
    RESUME,

    /**
     * Perdida definitiva: otra app se quedo con el audio para rato.
     *
     * No se reanuda solo. La pantalla de conversacion sale del modo de voz.
     */
    STOP
}

/**
 * Foco de audio de la app, en un solo sitio.
 *
 * Pedir el foco no es una formalidad: sin el, el TTS habla encima de la musica que el
 * estudiante tenga puesta, y sobre todo la app no se entera de que entro una llamada. El
 * `AudioManager` es la unica via sin permisos para detectar esa llamada; hacerlo con
 * `TelephonyManager` exigiria `READ_PHONE_STATE`, que es un permiso peligroso y que aqui no
 * hace falta.
 *
 * ### Por que lleva cuenta de peticiones
 *
 * Dos partes de la app piden foco por su cuenta: [TtsManager] mientras lee una respuesta, y
 * la pantalla de conversacion por voz durante toda la llamada. Si cada una llamara a
 * `abandonAudioFocusRequest` al terminar, la primera en acabar le quitaria el foco a la otra
 * y la app dejaria de enterarse de las llamadas telefonicas a mitad de conversacion.
 *
 * Por eso [request] y [abandon] llevan una cuenta y el foco solo se suelta de verdad cuando
 * la ultima parte lo libera.
 *
 * ### Por que `TRANSIENT_MAY_DUCK`
 *
 * Es lo que pide la especificacion de F8, y ademas es lo correcto: el asistente habla unos
 * segundos y devuelve el audio. Pedir `GAIN` a secas pararia la musica del estudiante para
 * siempre; pedir `TRANSIENT` a secas la silenciaria del todo. Con `MAY_DUCK` el sistema baja
 * el volumen de lo demas y lo devuelve solo.
 */
class AudioFocusController(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _events = MutableSharedFlow<AudioFocusEvent>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Interrupciones del sistema. Las observa la pantalla de conversacion por voz. */
    val events: SharedFlow<AudioFocusEvent> = _events.asSharedFlow()

    /**
     * Cuantas partes de la app tienen el foco pedido ahora mismo.
     *
     * Solo se toca desde el hilo principal, que es desde donde hablan el TTS y la pantalla.
     */
    private var holders = 0

    private var request: AudioFocusRequest? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS ->
                _events.tryEmit(AudioFocusEvent.STOP)

            // Los dos transitorios se tratan igual y se pausa en ambos. Un asistente que
            // sigue hablando a medio volumen mientras suena una llamada no se entiende, y el
            // microfono ademas se quedaria oyendo el tono de llamada.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                _events.tryEmit(AudioFocusEvent.PAUSE)

            AudioManager.AUDIOFOCUS_GAIN ->
                _events.tryEmit(AudioFocusEvent.RESUME)
        }
    }

    /**
     * Pide el foco, o apunta un interesado mas si ya se tenia.
     *
     * @return `true` si la app tiene el foco al volver. `false` significa que el sistema lo
     *   nego, y quien llama deberia renunciar a hablar en vez de hacerlo por encima.
     */
    fun request(): Boolean {
        if (holders > 0) {
            holders++
            return true
        }

        val focusRequest = AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
            .setAudioAttributes(SPEECH_ATTRIBUTES)
            // El sistema avisa antes de arrebatar el foco en vez de hacerlo de golpe.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(listener)
            .build()

        val granted = audioManager.requestAudioFocus(focusRequest) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        if (granted) {
            request = focusRequest
            holders = 1
        } else {
            Log.w(App.LOG_TAG, "El sistema nego el foco de audio")
        }
        return granted
    }

    /** Suelta un interesado. El foco solo se devuelve cuando no queda ninguno. */
    fun abandon() {
        if (holders == 0) return
        holders--
        if (holders > 0) return

        request?.let { audioManager.abandonAudioFocusRequest(it) }
        request = null
    }

    /**
     * `true` si el telefono esta sonando o en una llamada.
     *
     * Es **el** criterio para pausar la conversacion por voz, y no la simple perdida de foco.
     * El motivo esta explicado en `VoiceCallViewModel.observeAudioFocus`: en esta pantalla el
     * microfono esta abierto a proposito todo el rato, incluso mientras el asistente habla,
     * y el servicio de reconocimiento roba el foco cada vez que abre una sesion. Si la
     * perdida de foco bastara para pausar, la app se cortaria a si misma a mitad de cada
     * respuesta.
     *
     * `MODE_RINGTONE` entra en la cuenta para pausar mientras suena, sin esperar a que el
     * estudiante conteste: si no, el asistente le habla encima al tono de llamada.
     */
    val isPhoneBusy: Boolean
        get() = audioManager.mode == AudioManager.MODE_IN_CALL ||
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audioManager.mode == AudioManager.MODE_RINGTONE

    private companion object {
        /**
         * Voz de asistente, no multimedia.
         *
         * `USAGE_ASSISTANT` es lo que hace que el sistema la trate como habla: la enruta al
         * altavoz correcto con el manos libres del carro conectado y no la mezcla con la
         * musica como si fuera una cancion mas.
         */
        val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
