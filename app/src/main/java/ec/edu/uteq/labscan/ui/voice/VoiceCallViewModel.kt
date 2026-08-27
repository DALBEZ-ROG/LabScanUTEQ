package ec.edu.uteq.labscan.ui.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import ec.edu.uteq.labscan.App
import ec.edu.uteq.labscan.data.remote.ConnectivityObserver
import ec.edu.uteq.labscan.di.AppContainer
import ec.edu.uteq.labscan.voice.AudioFocusController
import ec.edu.uteq.labscan.voice.AudioFocusEvent
import ec.edu.uteq.labscan.voice.ContinuousSttManager
import ec.edu.uteq.labscan.voice.EchoControl
import ec.edu.uteq.labscan.voice.ListenMode
import ec.edu.uteq.labscan.voice.TtsManager
import ec.edu.uteq.labscan.voice.VoiceTurnEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Maquina de estados de la conversacion por voz manos libres (F8).
 *
 * ### Que hace y que no
 *
 * Este ViewModel **no habla con el backend**. Solo decide cuando se escucha, cuando se calla
 * y cuando se lee, y publica el turno terminado por [questions]. Quien lo envia es la
 * pantalla, llamando al `ChatViewModel` compartido. Es lo que hace que la conversacion
 * hablada y la escrita sean la misma y no dos historiales paralelos: el unico sitio donde
 * viven los mensajes sigue siendo `ChatViewModel`.
 *
 * La pantalla devuelve el resultado por [onThinking], [onAnswer] y [onError].
 *
 * ### La cascada
 *
 * El backend usa un LLM de texto: no hay ninguna API de voz a voz. La cadena completa es
 * reconocimiento en el dispositivo, texto, backend, texto, sintesis en el dispositivo. Todo
 * el audio se queda en el telefono; lo unico que sale es la misma cadena de texto que
 * enviaria el chat escrito.
 *
 * ### El eco, que es el problema de verdad
 *
 * Con el altavoz al maximo el microfono se oye a si mismo. Aqui se ataca con tres medidas a
 * la vez, porque ninguna basta sola (ver [EchoControl] sobre por que no se puede usar el
 * cancelador de eco del sistema con `SpeechRecognizer`):
 *
 * 1. Mientras se habla, el reconocedor pasa a [ListenMode.WATCH] y **su texto se descarta
 *    entero**. Aunque transcriba al altavoz, ese texto no llega a ninguna parte.
 * 2. El umbral de amplitud para dar por buena una interrupcion sube a
 *    [BARGE_IN_THRESHOLD_SPEAKING] mientras habla el asistente.
 * 3. La interrupcion exige [BARGE_IN_SUSTAIN_MS] seguidos por encima del umbral. Una silaba
 *    devuelta por el altavoz no llega; una frase del estudiante, si.
 */
class VoiceCallViewModel(
    context: Context,
    private val ttsManager: TtsManager,
    private val audioFocus: AudioFocusController,
    private val connectivity: ConnectivityObserver
) : ViewModel() {

    private val sttManager = ContinuousSttManager(context) { audioFocus.isPhoneBusy }
    private val echoControl = EchoControl()

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Connecting)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** Amplitud del microfono, `0f..1f`. La usa la forma animada de la pantalla. */
    val amplitude: StateFlow<Float> = sttManager.amplitude

    private val _partialText = MutableStateFlow("")

    /** Lo que se va entendiendo mientras el estudiante habla. Se muestra en vivo. */
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _lastAnswer = MutableStateFlow("")

    /** Resumen de una linea de lo ultimo que dijo el asistente. */
    val lastAnswer: StateFlow<String> = _lastAnswer.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _questions = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Turnos cerrados, listos para enviarse. Los consume la pantalla. */
    val questions: SharedFlow<String> = _questions.asSharedFlow()

    private val _finished = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Se emite cuando hay que salir del modo de voz. La pantalla navega hacia atras. */
    val finished: SharedFlow<Unit> = _finished.asSharedFlow()

    /** `true` entre [begin] y [end]. Evita que un evento tardio reviva una sesion cerrada. */
    private var active = false

    /** `true` mientras una llamada telefonica u otra app tiene el audio. */
    private var pausedBySystem = false

    /** Desde cuando la amplitud esta por encima del umbral. `0` si esta por debajo. */
    private var loudSinceMs = 0L

    /**
     * Pausa aplazada por perdida de foco. Ver [observeAudioFocus].
     */
    private var pendingPause: Job? = null

    /** El aviso de "no hay conexion" se dice una sola vez por sesion, como pide F8. */
    private var announcedOffline = false

    // --- Medicion de latencia -------------------------------------------------------------

    private var endOfSpeechAtMs = 0L
    private var turnDetectedAtMs = 0L
    private var requestSentAtMs = 0L
    private var answerAtMs = 0L

    init {
        echoControl.logDeviceSupport()
        observeRecognizer()
        observeAmplitude()
        observeSpeechCompleted()
        observeAudioFocus()
    }

    // ---------------------------------------------------------------------------------------
    // Ciclo de la llamada
    // ---------------------------------------------------------------------------------------

    /**
     * Entra al modo de conversacion.
     *
     * Comprueba primero lo que puede impedirlo, en el orden en que le importa al estudiante:
     * si no hay motor de voz no hay nada que hacer, y si no hay red la pregunta no llegaria
     * al backend aunque se transcribiera perfectamente.
     */
    fun begin() {
        if (active) return

        if (!sttManager.isAvailable) {
            _state.value = VoiceState.Error(VoiceErrorKind.SIN_RECONOCEDOR)
            return
        }

        active = true
        announcedOffline = false
        _state.value = VoiceState.Connecting
        // Aqui NO se pide el foco de audio, aunque parezca lo natural para una llamada.
        // Ver la nota sobre el bucle de auto-interrupcion en [observeAudioFocus].

        viewModelScope.launch {
            if (!connectivity.isOnlineNow()) {
                announceOfflineAndLeave()
                return@launch
            }
            openMicrophone()
        }
    }

    /** Cuelga. Cierra el microfono, calla al asistente y suelta el audio. */
    fun end() {
        if (!active) return
        active = false
        pendingPause?.cancel()
        pendingPause = null
        sttManager.stop()
        ttsManager.stop()
        _partialText.value = ""
        _state.value = VoiceState.Connecting
    }

    /**
     * Suspende todo sin cerrar la sesion.
     *
     * Lo llaman el ciclo de vida (la app se minimiza) y la perdida transitoria de foco (entra
     * una llamada). El microfono se cierra de verdad: dejarlo abierto en segundo plano es
     * justo lo que F8 prohibe.
     */
    fun pause() {
        if (!active) return
        pausedBySystem = true
        sttManager.stop()
        ttsManager.stop()
        _partialText.value = ""
        _state.value = VoiceState.Connecting
    }

    /** Reanuda tras la interrupcion, salvo que el telefono siga en una llamada. */
    fun resume() {
        if (!active || !pausedBySystem) return
        if (audioFocus.isPhoneBusy) return
        pausedBySystem = false
        openMicrophone()
    }

    /** Silencia o reactiva el microfono desde el boton de la pantalla. */
    fun toggleMute() {
        val next = !_isMuted.value
        _isMuted.value = next
        sttManager.setMuted(next)
        if (!next && active && _state.value !is VoiceState.Speaking) {
            _state.value = VoiceState.Listening
        }
    }

    // ---------------------------------------------------------------------------------------
    // Enganche con el ChatViewModel, desde la pantalla
    // ---------------------------------------------------------------------------------------

    /** La pregunta salio al backend. */
    fun onThinking() {
        if (!active || _state.value is VoiceState.Thinking) return
        requestSentAtMs = SystemClock.elapsedRealtime()
        _state.value = VoiceState.Thinking
    }

    /**
     * Llego la respuesta: se lee en voz alta, partida en frases.
     *
     * **Las fuentes no se leen nunca**, ni siquiera en modo de voz: se muestran en pantalla.
     * Es la regla 6 de CLAUDE.md y se repite en F8. Una lista de titulos y numeros de pagina
     * dicha en voz alta no ayuda a nadie y ademas alarga el turno.
     */
    fun onAnswer(text: String) {
        if (!active) return
        answerAtMs = SystemClock.elapsedRealtime()
        _lastAnswer.value = text
        _partialText.value = ""

        // Vigilancia antes de abrir la boca: si se dejara en captura, el primer parcial del
        // propio altavoz cerraria un turno falso.
        sttManager.setMode(ListenMode.WATCH)
        loudSinceMs = 0L

        val queued = ttsManager.speakSentences(text)
        if (queued) {
            _state.value = VoiceState.Speaking
        } else {
            // Respuesta vacia: no hay nada que decir, se vuelve a escuchar.
            backToListening()
        }
    }

    /** El backend fallo. No se lee el error en voz alta: se muestra y se vuelve a escuchar. */
    fun onError(kind: VoiceErrorKind) {
        if (!active) return
        _state.value = VoiceState.Error(kind)
        _partialText.value = ""
        // Se sigue escuchando: el estudiante puede repetir la pregunta sin tocar nada, que es
        // el punto entero de este modo.
        sttManager.setMode(ListenMode.CAPTURE)
    }

    // ---------------------------------------------------------------------------------------
    // Interior
    // ---------------------------------------------------------------------------------------

    private fun openMicrophone() {
        _state.value = VoiceState.Connecting
        sttManager.start()
        _state.value = VoiceState.Listening
    }

    private fun backToListening() {
        if (!active) return
        _partialText.value = ""
        sttManager.setMode(ListenMode.CAPTURE)
        _state.value = VoiceState.Listening
    }

    private fun observeRecognizer() {
        viewModelScope.launch {
            sttManager.events.collect { event ->
                if (!active) return@collect
                when (event) {
                    is VoiceTurnEvent.Partial -> {
                        _partialText.value = event.text
                        if (_state.value !is VoiceState.Capturing) {
                            _state.value = VoiceState.Capturing
                        }
                    }

                    is VoiceTurnEvent.Final -> {
                        endOfSpeechAtMs = event.endOfSpeechAtMs
                        turnDetectedAtMs = SystemClock.elapsedRealtime()
                        _partialText.value = event.text
                        _questions.tryEmit(event.text)
                        // El estado pasa a Thinking cuando la pantalla confirme el envio, no
                        // aqui: si la pregunta se descartara por estar ya enviando otra, el
                        // indicador se quedaria girando para siempre.
                    }

                    is VoiceTurnEvent.Failed -> {
                        val kind = event.kind.toVoiceErrorKind()
                        Log.w(App.LOG_TAG, "Conversacion por voz detenida: $kind")
                        _state.value = VoiceState.Error(kind)
                    }
                }
            }
        }
    }

    /**
     * Vigila el volumen para detectar que el estudiante interrumpe.
     *
     * Solo actua en [VoiceState.Speaking]: en los demas estados el turno lo abre el texto
     * reconocido, no el volumen. Detectar por volumen fuera de ahi convertiria cualquier
     * ruido del laboratorio en el principio de una pregunta.
     */
    private fun observeAmplitude() {
        viewModelScope.launch {
            sttManager.amplitude.collect { level ->
                if (!active || _state.value !is VoiceState.Speaking) {
                    loudSinceMs = 0L
                    return@collect
                }

                val now = SystemClock.elapsedRealtime()
                if (level < BARGE_IN_THRESHOLD_SPEAKING) {
                    loudSinceMs = 0L
                    return@collect
                }

                if (loudSinceMs == 0L) {
                    loudSinceMs = now
                    return@collect
                }

                val sustained = now - loudSinceMs
                if (sustained >= BARGE_IN_SUSTAIN_MS) {
                    interrupt(sustained)
                }
            }
        }
    }

    /**
     * Corta al asistente porque el estudiante empezo a hablar.
     *
     * `stop()` va lo primero de todo, antes de tocar ningun estado: es lo unico que el
     * estudiante oye, y cada milisegundo de mas suena a que la app no le hace caso.
     */
    private fun interrupt(sustainedMs: Long) {
        ttsManager.stop()
        loudSinceMs = 0L
        _lastAnswer.value = ""
        _partialText.value = ""
        _state.value = VoiceState.Capturing
        // Sesion limpia: la anterior estaba en vigilancia y arrastra lo que el motor entendio
        // del propio altavoz.
        sttManager.setMode(ListenMode.CAPTURE)
        Log.i(App.LOG_TAG, "Interrupcion del asistente confirmada tras $sustainedMs ms")
    }

    private fun observeSpeechCompleted() {
        viewModelScope.launch {
            ttsManager.speechCompleted.collect {
                if (!active) return@collect
                if (_state.value is VoiceState.Speaking) backToListening()
            }
        }
    }

    /**
     * Mide el momento en que suena la primera palabra, y vigila el foco de audio.
     *
     * ### El bucle de auto-interrupcion, que costo la mitad de esta fase
     *
     * La primera version pedia el foco de audio al entrar y lo mantenia durante toda la
     * llamada, que es lo que parece correcto para una conversacion. En el telefono, la app
     * se apagaba y se encendia sola varias veces por segundo. El log lo explico:
     *
     * ```
     * openMicrophone(): active=true pausado=false
     * Foco de audio: cambio=-2   <- AUDIOFOCUS_LOSS_TRANSIENT
     * Foco de audio: cambio=1    <- AUDIOFOCUS_GAIN, 3 ms despues
     * openMicrophone(): active=true pausado=false
     * ```
     *
     * **El servicio de reconocimiento de voz pide el foco de audio para grabar.** Al
     * pedirlo, nos lo quitaba; nosotros lo leiamos como "entro una llamada" y cerrabamos el
     * microfono; el servicio lo soltaba, lo recuperabamos, reabriamos el microfono, y vuelta
     * a empezar. La app se estaba interrumpiendo a si misma.
     *
     * Dos cambios lo resuelven, y los dos son de diseno, no parches:
     *
     * 1. **No se pide foco durante la escucha.** El foco de audio es para reproducir, no
     *    para grabar. Lo pide [TtsManager] mientras lee, que es cuando de verdad hay algo
     *    sonando que puede molestar a otra app.
     * 2. **Perder el foco no basta para pausar: el telefono tiene que estar ocupado.**
     *    Con solo el cambio anterior, la app seguia cortandose a si misma a mitad de cada
     *    respuesta, ahora a los 2,5 s en vez de a los 40 ms:
     *
     *    ```
     *    17:40:01  Latencia de voz: total 846 ms ...
     *    17:40:03  Foco perdido de verdad: se pausa la conversacion
     *    ```
     *
     *    Mientras el asistente habla, el microfono sigue abierto para poder interrumpirlo,
     *    y esa sesion de reconocimiento retiene el foco varios segundos seguidos: mas que
     *    cualquier periodo de gracia razonable. La pregunta correcta no es "cuanto llevo
     *    sin foco" sino **"por que lo perdi"**, y para eso la unica senal fiable sin
     *    permisos es [AudioFocusController.isPhoneBusy]. La espera de
     *    [FOCUS_LOSS_GRACE_MS] se conserva porque el modo de audio del sistema tarda unas
     *    decimas en reflejar una llamada entrante.
     *
     * Queda anotado en docs/DECISIONES.md (D-026).
     */
    private fun observeAudioFocus() {
        viewModelScope.launch {
            ttsManager.isSpeaking.collect { speaking ->
                if (speaking && answerAtMs > 0L) {
                    reportLatency(SystemClock.elapsedRealtime())
                    answerAtMs = 0L
                }
            }
        }
        viewModelScope.launch {
            audioFocus.events.collect { event ->
                if (!active) return@collect
                when (event) {
                    // La pausa se aplaza [FOCUS_LOSS_GRACE_MS]. Ver la nota de la funcion:
                    // la mayoria de las perdidas de foco aqui las provoca nuestro propio
                    // reconocedor y se recuperan en milisegundos.
                    AudioFocusEvent.PAUSE -> {
                        pendingPause?.cancel()
                        pendingPause = viewModelScope.launch {
                            delay(FOCUS_LOSS_GRACE_MS)
                            if (!audioFocus.isPhoneBusy) {
                                // Casi siempre es nuestro propio reconocedor abriendo una
                                // sesion. No es una interrupcion.
                                return@launch
                            }
                            Log.i(App.LOG_TAG, "El telefono esta ocupado: se pausa la conversacion")
                            pause()
                        }
                    }

                    AudioFocusEvent.RESUME -> {
                        if (pendingPause?.isActive == true) {
                            // Volvio antes de que se cumpliera la gracia: era el reconocedor
                            // pidiendo el microfono, no una interrupcion.
                            pendingPause?.cancel()
                            pendingPause = null
                        } else {
                            resume()
                        }
                    }

                    // Perdida definitiva: otra app se quedo con el audio. Insistir seria
                    // pelearse con ella, asi que se sale del modo de voz.
                    AudioFocusEvent.STOP -> {
                        pendingPause?.cancel()
                        end()
                        _finished.tryEmit(Unit)
                    }
                }
            }
        }
    }

    /**
     * Deja la latencia del turno en el log, desglosada.
     *
     * Se imprime siempre, tambien en release: es la medida que sostiene el criterio (d) de
     * F8 y tiene que poder comprobarse en el telefono de la entrega, no solo en depuracion.
     */
    private fun reportLatency(firstWordAtMs: Long) {
        if (endOfSpeechAtMs == 0L) return

        val detection = turnDetectedAtMs - endOfSpeechAtMs
        val network = if (requestSentAtMs > 0L && answerAtMs > 0L) answerAtMs - requestSentAtMs else -1
        val synthesis = if (answerAtMs > 0L) firstWordAtMs - answerAtMs else -1
        val total = firstWordAtMs - endOfSpeechAtMs

        Log.i(
            App.LOG_TAG,
            "Latencia de voz: total $total ms " +
                "(fin de turno $detection ms + red $network ms + sintesis $synthesis ms)"
        )
        endOfSpeechAtMs = 0L
    }

    /**
     * Avisa de que no hay conexion y sale.
     *
     * Este es el **unico** error que se dice en voz alta, y solo una vez por sesion. F6 dejo
     * escrito que los errores de red no se leen nunca, y sigue siendo la regla en el chat;
     * pero aqui el estudiante tiene las manos ocupadas y puede no estar mirando la pantalla,
     * asi que callarse equivaldria a dejarlo hablandole a una app muerta. La contradiccion es
     * deliberada y esta anotada en docs/DECISIONES.md (D-025).
     */
    private fun announceOfflineAndLeave() {
        _state.value = VoiceState.Error(VoiceErrorKind.SIN_CONEXION)
        if (!announcedOffline) {
            announcedOffline = true
            ttsManager.speak(OFFLINE_SPOKEN_NOTICE)
        }
        active = false
        sttManager.stop()
        _finished.tryEmit(Unit)
    }

    override fun onCleared() {
        // El reconocedor se suelta aqui y no en AppContainer: el microfono no puede quedar
        // reservado fuera de esta pantalla (restriccion de F8).
        sttManager.release()
        echoControl.release()
        active = false
        pendingPause?.cancel()
        ttsManager.stop()
    }

    companion object {
        /**
         * Amplitud minima para considerar que alguien esta hablando **por encima** del
         * asistente.
         *
         * Es deliberadamente alta. El altavoz devuelve al microfono un nivel que en el
         * Samsung A56 al volumen maximo se queda alrededor de 0,3-0,4 normalizado; poner el
         * umbral en 0,55 deja margen sin exigirle al estudiante que grite.
         */
        const val BARGE_IN_THRESHOLD_SPEAKING = 0.55f

        /**
         * Cuanto tiene que sostenerse ese volumen para dar la interrupcion por buena.
         *
         * Los 300 ms son de la especificacion de F8 y hacen doble trabajo: descartan el
         * golpe corto (una puerta, una silla) y descartan las silabas fuertes del propio
         * asistente, que nunca se sostienen tanto.
         */
        const val BARGE_IN_SUSTAIN_MS = 300L

        /**
         * Cuanto se espera, tras perder el foco, antes de mirar si el telefono esta ocupado.
         *
         * El modo de audio del sistema tarda unas decimas en pasar a `MODE_RINGTONE` cuando
         * entra una llamada; preguntarlo en el mismo instante de la perdida daria `false` y no
         * se pausaria nunca.
         */
        const val FOCUS_LOSS_GRACE_MS = 500L

        /** Lo unico que la app dice en voz alta cuando no hay red. */
        const val OFFLINE_SPOKEN_NOTICE =
            "No hay conexion con el asistente. Saliendo del modo de voz."

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras
                ): T = VoiceCallViewModel(
                    context = container.applicationContext,
                    ttsManager = container.ttsManager,
                    audioFocus = container.audioFocusController,
                    connectivity = container.connectivityObserver
                ) as T
            }
    }
}
