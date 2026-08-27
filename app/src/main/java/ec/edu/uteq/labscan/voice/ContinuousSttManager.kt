package ec.edu.uteq.labscan.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.MainThread
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
 * Que hace el microfono en este instante.
 *
 * No es lo mismo tener el microfono abierto que estar recogiendo la pregunta. Durante
 * [WATCH] el microfono sigue abierto, pero solo para medir cuanto ruido hay: es lo que
 * permite detectar que el estudiante interrumpe al asistente sin transcribir de paso lo que
 * el propio asistente esta diciendo por el altavoz.
 */
enum class ListenMode {
    /** Se transcribe y se detecta el fin del turno. Es el modo normal. */
    CAPTURE,

    /** Solo se mide amplitud. El texto reconocido se tira. Se usa mientras habla el TTS. */
    WATCH
}

/** Lo que le pasa al reconocedor, ya interpretado. */
sealed interface VoiceTurnEvent {
    /** Transcripcion provisional del turno en curso. */
    data class Partial(val text: String) : VoiceTurnEvent

    /**
     * El estudiante termino de hablar. Este es el texto definitivo del turno.
     *
     * @param endOfSpeechAtMs `SystemClock.elapsedRealtime()` del ultimo parcial, es decir el momento
     *   real en que el estudiante dejo de hablar. No es el momento en que se detecto: entre
     *   los dos hay [ContinuousSttManager.SILENCE_TIMEOUT_MS] de silencio, y esa espera forma
     *   parte de lo que el estudiante percibe como demora. Medir desde aqui es lo unico
     *   honesto para el presupuesto de latencia de F8.
     */
    data class Final(val text: String, val endOfSpeechAtMs: Long) : VoiceTurnEvent

    /** Fallo que la pantalla tiene que explicar. Los fallos recuperables no llegan aqui. */
    data class Failed(val kind: SttErrorKind) : VoiceTurnEvent
}

/**
 * Escucha continua sobre `android.speech.SpeechRecognizer`.
 *
 * Es el motor del modo de conversacion por voz de F8. La diferencia con [SttManager], que
 * sigue sirviendo al chat escrito de F6, es que aqui **nadie pulsa nada**: el microfono se
 * mantiene abierto turno tras turno y la app decide sola cuando el estudiante termino de
 * hablar.
 *
 * ### La limitacion de la plataforma, que es el problema central de esta clase
 *
 * `SpeechRecognizer` **no tiene modo continuo**. Esta pensado para una locucion y se cierra
 * solo: llama a `onEndOfSpeech` y despues a `onResults` o a `onError`, y a partir de ahi el
 * microfono esta muerto hasta que alguien vuelva a llamar a `startListening`. No hay ninguna
 * bandera que lo cambie.
 *
 * La escucha continua, por tanto, no existe: se **simula** reiniciando la sesion cada vez que
 * el sistema la cierra. Ese reinicio es lo que hace casi todo el trabajo de esta clase, y
 * tiene dos problemas que hay que tratar:
 *
 * 1. **Reiniciar demasiado rapido da `ERROR_RECOGNIZER_BUSY`**, porque el motor todavia esta
 *    soltando la sesion anterior. Por eso el reinicio va con espera creciente
 *    ([RESTART_BACKOFF_MS]) y con un tope de intentos seguidos: si el motor falla tres veces
 *    seguidas no es un cierre normal, es que algo va mal de verdad, y se avisa en vez de
 *    quedarse reintentando en un bucle invisible.
 * 2. **Cada reinicio abre un hueco de unos 200 ms** en el que el microfono no oye. Es la
 *    razon por la que el fin de turno no se delega en el sistema. Ver mas abajo.
 *
 * Queda anotado en `docs/DECISIONES.md` (D-023).
 *
 * ### Por que no se espera a `onResults` para cerrar el turno
 *
 * `onResults` es correcto pero lento: el motor espera su propio silencio, decide, y entrega.
 * Entre que el estudiante calla y llega `onResults` pasan facilmente mas de dos segundos, que
 * es casi todo el presupuesto de latencia de la fase.
 *
 * Aqui se cierra el turno antes, por cuenta propia: [SILENCE_TIMEOUT_MS] sin que llegue un
 * parcial nuevo **y** con texto ya capturado significa que la frase termino. El texto que se
 * entrega es el ultimo parcial, que en la practica coincide con el resultado final salvo en
 * la puntuacion.
 *
 * ### Hilo principal, obligatorio
 *
 * `SpeechRecognizer` solo funciona desde el hilo principal, y desde otro lanza una excepcion
 * que no explica nada. Todo lo publico esta marcado `@MainThread`, y los reinicios y el
 * temporizador de silencio van por un [Handler] del `Looper` principal por el mismo motivo.
 */
class ContinuousSttManager(
    context: Context,
    /**
     * Devuelve `true` cuando no se debe abrir el microfono aunque toque.
     *
     * Lo usa el modo de voz para no pelearse con una llamada telefonica en curso: el
     * reconocedor no conseguiria el microfono y quemaria los tres reinicios permitidos en
     * medio segundo, dejando la conversacion muerta justo cuando el estudiante cuelga.
     */
    private val isAudioBlocked: () -> Boolean = { false }
) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    /** `true` si el dispositivo puede transcribir. Se consulta en cada acceso, no se cachea. */
    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    private val _events = MutableSharedFlow<VoiceTurnEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        // Un evento perdido es peor que uno viejo: si el consumidor se retrasa, se descarta
        // el mas antiguo y se conserva el ultimo estado, que es el que importa.
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Parciales, finales y fallos del turno. */
    val events: SharedFlow<VoiceTurnEvent> = _events.asSharedFlow()

    private val _amplitude = MutableStateFlow(0f)

    /**
     * Volumen del microfono normalizado a `0f..1f`, para la animacion y para el barge-in.
     *
     * Sale de `onRmsChanged`, que entrega decibelios en una escala que la documentacion situa
     * entre -2 y 10. No es una medida absoluta ni comparable entre dispositivos; sirve para
     * lo que se usa aqui, que es distinguir silencio de voz en el mismo telefono.
     */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _isOpen = MutableStateFlow(false)

    /** `true` mientras el microfono esta realmente abierto. */
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /** Modo actual. Lo cambia el ViewModel al entrar y salir de "hablando". */
    private var mode: ListenMode = ListenMode.CAPTURE

    /** `true` entre [start] y [stop]. Si es `false`, ningun reinicio debe ocurrir. */
    private var running = false

    /** `true` mientras el estudiante silencia el microfono desde el boton. */
    private var muted = false

    /** Texto acumulado del turno en curso. */
    private var captured: String = ""

    /** Cuando llego el ultimo parcial. Es el instante en que el estudiante dejo de hablar. */
    private var lastPartialAtMs: Long = 0L

    /** Reinicios seguidos sin ningun reconocimiento en medio. Ver [RESTART_BACKOFF_MS]. */
    private var consecutiveRestarts = 0

    /**
     * `true` mientras se prefiere el reconocimiento del propio dispositivo.
     *
     * Sin conexion es la unica forma de que funcione, y con conexion sigue siendo mas rapido
     * porque no hay ida y vuelta a un servidor. Pero muchos telefonos no traen el paquete de
     * espanol descargado, asi que si el motor se queja del idioma se pasa a en linea y no se
     * vuelve a intentar sin conexion en esta sesion.
     */
    private var preferOffline = offlineSupported

    /**
     * Idioma que se esta probando, como indice de [SPEECH_LOCALES].
     *
     * Cuando pasa del ultimo elemento, se deja de pedir idioma y se usa el del sistema.
     */
    private var localeIndex = 0

    /**
     * Traga el proximo `ERROR_CLIENT`, porque lo hemos provocado nosotros.
     *
     * `SpeechRecognizer.cancel()` entrega `ERROR_CLIENT` (5) por el mismo callback que un fallo de
     * verdad. Sin distinguirlos, cada reinicio deliberado se leia como una averia: en el
     * telefono salia una rafaga de "conversacion detenida" en menos de un segundo, con la
     * pantalla en rojo y el microfono cerrado, y sin que hubiera pasado nada malo.
     */
    private var suppressClientError = false

    private val silenceRunnable = Runnable { closeTurnBySilence() }
    private val restartRunnable = Runnable { restartNow() }
    private val watchdogRunnable = Runnable { onSessionStalled() }

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            _isOpen.value = true
            // El motor acepto la sesion y abrio el microfono: eso es un reconocedor sano.
            // Si no se pusiera a cero aqui, una sala en silencio agotaria el tope de
            // reinicios en menos de medio minuto y la escucha continua se apagaria sola
            // sin que hubiera fallado nada.
            consecutiveRestarts = 0
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            _amplitude.value = normalizeRms(rmsdB)
            // Latido: mientras lleguen medidas de volumen, la sesion esta viva.
            armWatchdog()
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            // El sistema cierra la sesion aqui. No se toca el turno: puede que el estudiante
            // solo hiciera una pausa, y el temporizador de silencio es quien decide.
            _isOpen.value = false
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.firstTranscript()?.trim().orEmpty()
            if (text.isEmpty()) return

            // Haber reconocido algo demuestra que el motor esta sano: se perdona el historial
            // de reinicios para que el proximo cierre normal vuelva a esperar solo 200 ms.
            consecutiveRestarts = 0

            if (mode == ListenMode.WATCH) {
                // Vigilancia: es casi seguro el propio altavoz. Se tira.
                return
            }

            captured = text
            lastPartialAtMs = SystemClock.elapsedRealtime()
            _events.tryEmit(VoiceTurnEvent.Partial(text))
            armSilenceTimer()
        }

        override fun onResults(results: Bundle?) {
            cancelWatchdog()
            _isOpen.value = false
            consecutiveRestarts = 0

            val text = results.firstTranscript()?.trim().orEmpty()
            if (mode == ListenMode.CAPTURE && text.isNotEmpty()) {
                captured = text
                // Si el sistema llega antes que el temporizador, se aprovecha: su
                // transcripcion viene puntuada y es algo mejor que el ultimo parcial.
                closeTurn(text)
                return
            }
            scheduleRestart()
        }

        override fun onError(error: Int) {
            cancelWatchdog()
            _isOpen.value = false
            // Diagnostico: sin el codigo crudo, distinguir un cierre normal de una averia
            // obliga a adivinar. Cuesta una linea de log por turno.
            Log.d(App.LOG_TAG, "Reconocedor: codigo $error (modo $mode, idioma $localeIndex, offline $preferOffline)")

            // El motor devuelve NO_MATCH y SPEECH_TIMEOUT constantemente en escucha continua:
            // son el silencio entre frases, no un fallo. Se reinicia y ya.
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                // Si habia texto a medias, esto es el final del turno.
                if (mode == ListenMode.CAPTURE && captured.isNotBlank()) {
                    closeTurn(captured)
                    return
                }
                scheduleRestart()
                return
            }

            // Cancelacion nuestra, no averia. Ver [suppressClientError].
            if (error == SpeechRecognizer.ERROR_CLIENT && suppressClientError) {
                suppressClientError = false
                return
            }

            if (error in OFFLINE_UNSUPPORTED_ERRORS) {
                degradeLanguageSupport()
                return
            }

            // Un ERROR_CLIENT que no provocamos nosotros suele ser el motor cerrando la
            // sesion de mala manera. Se reintenta: si de verdad esta roto, el tope de
            // reinicios lo cazara en menos de dos segundos.
            if (error == SpeechRecognizer.ERROR_CLIENT) {
                scheduleRestart()
                return
            }

            val kind = error.toErrorKind()
            if (kind == SttErrorKind.BUSY) {
                // El motor todavia soltaba la sesion anterior. Es exactamente el caso para el
                // que existe la espera creciente.
                scheduleRestart()
                return
            }

            Log.w(App.LOG_TAG, "Escucha continua interrumpida, codigo $error")
            _events.tryEmit(VoiceTurnEvent.Failed(kind))
            running = false
            _amplitude.value = 0f
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    // ---------------------------------------------------------------------------------------
    // API publica
    // ---------------------------------------------------------------------------------------

    /**
     * Abre el microfono y lo mantiene abierto hasta [stop].
     *
     * No comprueba el permiso: lo hace la pantalla antes de llamar. Si faltara, el motor
     * responde `ERROR_INSUFFICIENT_PERMISSIONS` y sale por [VoiceTurnEvent.Failed].
     */
    @MainThread
    fun start() {
        if (!isAvailable) {
            _events.tryEmit(VoiceTurnEvent.Failed(SttErrorKind.UNAVAILABLE))
            return
        }
        if (running) return

        running = true
        muted = false
        mode = ListenMode.CAPTURE
        captured = ""
        consecutiveRestarts = 0
        startSession()
    }

    /**
     * Cambia entre transcribir y solo vigilar.
     *
     * Pasar a [ListenMode.WATCH] no cierra el microfono: hay que seguir midiendo amplitud
     * para detectar que el estudiante interrumpe. Lo que se descarta es el texto.
     */
    @MainThread
    fun setMode(next: ListenMode) {
        if (mode == next) return
        mode = next
        cancelSilenceTimer()
        captured = ""
        if (next == ListenMode.CAPTURE && running && !muted) {
            // Sesion limpia al volver a capturar: si se reaprovechara la de vigilancia, los
            // parciales seguirian arrastrando lo que el motor entendio del propio altavoz.
            restartNow()
        }
    }

    /**
     * Silencia o reactiva el microfono desde el boton de la pantalla.
     *
     * Silenciar cierra el reconocedor de verdad, no se limita a ignorar lo que llega: el
     * estudiante que pulsa "silenciar" espera que el microfono deje de estar abierto.
     */
    @MainThread
    fun setMuted(value: Boolean) {
        if (muted == value) return
        muted = value
        if (value) {
            cancelTimers()
            cancelQuietly()
            _isOpen.value = false
            _amplitude.value = 0f
        } else if (running) {
            startSession()
        }
    }

    /** Descarta el turno a medias y vuelve a escuchar desde cero. */
    @MainThread
    fun resetTurn() {
        captured = ""
        cancelSilenceTimer()
    }

    /** Cierra el microfono. Deja de haber reinicios. */
    @MainThread
    fun stop() {
        running = false
        cancelTimers()
        cancelQuietly()
        _isOpen.value = false
        _amplitude.value = 0f
        captured = ""
    }

    /**
     * Suelta el reconocedor.
     *
     * Lo llama el `onDispose` de la pantalla, no `AppContainer.close()`: el microfono no debe
     * quedar reservado fuera de la pantalla de conversacion (restriccion de F8).
     */
    @MainThread
    fun release() {
        stop()
        recognizer?.destroy()
        recognizer = null
    }

    // ---------------------------------------------------------------------------------------
    // Interior
    // ---------------------------------------------------------------------------------------

    private fun startSession() {
        if (!running || muted) return

        if (isAudioBlocked()) {
            // Se reintenta con la espera mas larga y sin gastar un reinicio: no ha fallado
            // nada, simplemente no es el momento.
            handler.removeCallbacks(restartRunnable)
            handler.postDelayed(restartRunnable, RESTART_BACKOFF_MS.last())
            return
        }

        val speech = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }

        try {
            speech.startListening(buildIntent())
            armWatchdog()
        } catch (error: Exception) {
            Log.e(App.LOG_TAG, "No se pudo abrir el microfono en modo continuo", error)
            _events.tryEmit(VoiceTurnEvent.Failed(SttErrorKind.UNKNOWN))
            running = false
        }
    }

    /**
     * Programa el reinicio con espera creciente.
     *
     * El tope de [MAX_CONSECUTIVE_RESTARTS] no es una optimizacion: sin el, un motor que
     * falla siempre deja la app reintentando para siempre, con la pantalla diciendo
     * "Escuchando" y el microfono muerto. Es el peor fallo posible aqui, porque no se ve.
     */
    private fun scheduleRestart() {
        if (!running || muted) return

        if (consecutiveRestarts >= MAX_CONSECUTIVE_RESTARTS) {
            Log.w(App.LOG_TAG, "El reconocedor no se recupera tras $consecutiveRestarts intentos")
            _events.tryEmit(VoiceTurnEvent.Failed(SttErrorKind.BUSY))
            running = false
            return
        }

        val delay = RESTART_BACKOFF_MS[consecutiveRestarts]
        consecutiveRestarts++
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delay)
    }

    private fun restartNow() {
        handler.removeCallbacks(restartRunnable)
        cancelQuietly()
        startSession()
    }

    /**
     * Cancela la sesion avisando de que el `ERROR_CLIENT` que vendra es nuestro.
     *
     * Toda cancelacion deliberada pasa por aqui. La que no pase se leera como una averia.
     */
    private fun cancelQuietly() {
        cancelWatchdog()
        if (recognizer == null) return
        suppressClientError = true
        recognizer?.cancel()
    }

    /**
     * El motor dice que no tiene este idioma. Se prueba lo siguiente, en orden.
     *
     * Primero se renuncia al reconocimiento sin conexion, que es lo mas probable que falte;
     * si aun asi no lo acepta, se baja por [SPEECH_LOCALES] hasta quedarse sin pedir idioma
     * y dejar el del sistema. Solo cuando se agotan todas se da por imposible.
     *
     * En el Samsung A56 de prueba, es-EC devuelve ERROR_LANGUAGE_NOT_SUPPORTED tanto sin
     * conexion como en linea, y la conversacion arranca con es-ES. Sin esta cadena, el modo
     * de voz simplemente no abria.
     */
    private fun degradeLanguageSupport() {
        // Cada intento de degradar es un arranque limpio: no debe contar como un reinicio
        // fallido, o el tope se agotaria antes de haber probado todos los idiomas.
        consecutiveRestarts = 0

        if (preferOffline) {
            Log.i(App.LOG_TAG, "Reconocimiento sin conexion no disponible, se pasa a en linea")
            preferOffline = false
            scheduleRestart()
            return
        }

        if (localeIndex <= SPEECH_LOCALES.lastIndex) {
            localeIndex++
            val siguiente = SPEECH_LOCALES.getOrNull(localeIndex) ?: "el del sistema"
            Log.i(App.LOG_TAG, "Idioma no soportado por el reconocedor, se prueba $siguiente")
            scheduleRestart()
            return
        }

        Log.w(App.LOG_TAG, "El reconocedor no acepta ningun idioma espanol (probados: $SPEECH_LOCALES)")
        _events.tryEmit(VoiceTurnEvent.Failed(SttErrorKind.UNAVAILABLE))
        running = false
    }

    /**
     * Reinicia la cuenta atras que detecta una sesion muerta.
     *
     * ### Por que hace falta un vigilante
     *
     * `SpeechRecognizer` puede quedarse mudo: ni resultado, ni error, ni nada. En el Samsung
     * A56 de prueba pasa siempre en la primera sesion con `EXTRA_PREFER_OFFLINE` activo y sin
     * el paquete de espanol descargado: abre el microfono, lo cierra 400 ms despues y **no
     * vuelve a llamar a ningun callback**. Medido en el log:
     *
     * ```
     * 17:28:34.260  STT startSession running=true muted=false bloqueado=false
     * 17:28:34.802  rec stop ... VOICE_RECOGNITION      <- dumpsys audio
     * (35 segundos de silencio absoluto)
     * ```
     *
     * El reintento en linea que pide F8 se dispara con `onError`, asi que sin vigilante no se
     * disparaba nunca y la pantalla se quedaba en "Escuchando" para siempre, con el
     * microfono cerrado y sin una sola linea de log que lo delatara. Es el peor fallo
     * posible en esta pantalla, porque desde fuera es indistinguible de estar funcionando.
     *
     * El latido son las medidas de volumen de `onRmsChanged`, que un reconocedor sano entrega
     * varias veces por segundo incluso en una sala en silencio.
     */
    private fun armWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, SESSION_WATCHDOG_MS)
    }

    private fun cancelWatchdog() = handler.removeCallbacks(watchdogRunnable)

    /**
     * La sesion dejo de dar senales de vida. Se trata como un fallo silencioso.
     *
     * Si todavia se estaba intentando el reconocimiento sin conexion, esa es la causa mas
     * probable con diferencia, y se renuncia a el **para todo el proceso**: no tiene sentido
     * volver a pagar esta espera cada vez que el estudiante entra al modo de voz.
     */
    private fun onSessionStalled() {
        if (!running || muted) return

        if (preferOffline) {
            Log.i(
                App.LOG_TAG,
                "El reconocedor sin conexion no responde: se pasa a en linea para el resto de la sesion"
            )
            preferOffline = false
            offlineSupported = false
            consecutiveRestarts = 0
            restartNow()
            return
        }

        // Nivel de depuracion y no aviso: en una sala en silencio esto es el ciclo normal de
        // la escucha continua y ocurre cada pocos segundos. Como aviso inundaba el log y
        // escondia los fallos de verdad.
        Log.d(App.LOG_TAG, "Sesion sin actividad, se reinicia la escucha")
        scheduleRestart()
    }

    private fun armSilenceTimer() {
        handler.removeCallbacks(silenceRunnable)
        handler.postDelayed(silenceRunnable, SILENCE_TIMEOUT_MS)
    }

    private fun cancelSilenceTimer() = handler.removeCallbacks(silenceRunnable)

    private fun cancelTimers() {
        handler.removeCallbacks(silenceRunnable)
        handler.removeCallbacks(restartRunnable)
        handler.removeCallbacks(watchdogRunnable)
    }

    private fun closeTurnBySilence() {
        if (!running || mode != ListenMode.CAPTURE) return
        val text = captured
        if (text.isBlank()) return
        closeTurn(text)
    }

    /**
     * Entrega el turno y deja el microfono listo para el siguiente.
     *
     * Si el texto no supera el filtro de [isUsableUtterance] no se entrega nada: se descarta
     * en silencio y se sigue escuchando. Mandar "eh" al backend gasta un turno del historial
     * y devuelve una respuesta que nadie pidio.
     */
    private fun closeTurn(text: String) {
        cancelSilenceTimer()
        captured = ""

        if (!isUsableUtterance(text)) {
            Log.d(App.LOG_TAG, "Descartado por muletilla o texto corto: \"$text\"")
            scheduleRestart()
            return
        }

        _events.tryEmit(
            VoiceTurnEvent.Final(
                text = text,
                // Si el sistema entrego el resultado sin que hubiera un parcial previo, no
                // hay mejor referencia que ahora mismo.
                endOfSpeechAtMs = lastPartialAtMs.takeIf { it > 0L }
                    ?: SystemClock.elapsedRealtime()
            )
        )
        // El microfono se detiene aqui. Quien manda el turno decide cuando volver a abrirlo,
        // porque entre medias hay que hablar y no se puede escuchar al mismo tiempo.
        cancelQuietly()
        _isOpen.value = false
        _amplitude.value = 0f
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // Sin idioma explicito cuando se agotaron los candidatos: el motor usa el del
            // sistema, que es mejor que no reconocer nada.
            SPEECH_LOCALES.getOrNull(localeIndex)?.let { tag ->
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            // Sin conexion mientras se pueda: es mas rapido y funciona en un laboratorio con
            // mala cobertura. Se apaga solo si el motor dice que le falta el idioma.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            // Estos dos no los respetan todos los motores, pero donde se respetan acortan la
            // espera del sistema, que es justo lo que aqui sobra.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_TIMEOUT_MS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_TIMEOUT_MS
            )
        }

    companion object {
        /**
         * Idiomas a probar, en orden.
         *
         * Ecuador primero porque es como se habla aqui; Espana como respaldo realista; y
         * "es" a secas al final, que es lo que aceptan los motores que no distinguen
         * variantes. Mismo criterio que [TtsManager], y por el mismo motivo: reconocer con
         * otro acento es peor que reconocer con el de aqui, pero infinitamente mejor que no
         * reconocer.
         */
        val SPEECH_LOCALES: List<String> = listOf("es-EC", "es-ES", "es")
            .map { Locale.forLanguageTag(it).toLanguageTag() }

        /**
         * Silencio que cierra el turno.
         *
         * 900 ms es el numero de la especificacion de F8 y aguanta bien la pausa natural de
         * quien duda a mitad de frase. Bajarlo corta al estudiante; subirlo se come el
         * presupuesto de latencia.
         */
        const val SILENCE_TIMEOUT_MS = 900L

        /**
         * Espera antes de cada reinicio, en milisegundos.
         *
         * El primer reinicio es el caso normal (el motor cerro tras una pausa) y tiene que
         * ser casi inmediato o se nota el hueco. Los siguientes suben porque, si el primero
         * no basto, lo mas probable es que el motor siga ocupado.
         */
        val RESTART_BACKOFF_MS = longArrayOf(200L, 400L, 800L)

        /**
         * Silencio absoluto de callbacks que se considera sesion muerta.
         *
         * Tiene que ser mayor que el hueco normal entre medidas de volumen (decimas de
         * segundo) y menor que la paciencia de una persona esperando a que la escuchen.
         */
        const val SESSION_WATCHDOG_MS = 2500L

        /**
         * Si el reconocimiento sin conexion sirve en este dispositivo.
         *
         * Se recuerda a nivel de proceso, no de instancia: la comprobacion cuesta una espera
         * de [SESSION_WATCHDOG_MS] y no hay motivo para repetirla cada vez que el estudiante
         * entra al modo de voz. Vuelve a `true` al reiniciar la app, que es cuando podria
         * haber cambiado (el estudiante descargo el idioma desde los ajustes del sistema).
         */
        @Volatile
        private var offlineSupported: Boolean = true

        /** Tope de reinicios seguidos sin haber reconocido nada. */
        const val MAX_CONSECUTIVE_RESTARTS = 3

        /** Codigos que significan "no tengo este idioma sin conexion". Son de API 33. */
        private val OFFLINE_UNSUPPORTED_ERRORS = setOf(
            12, // ERROR_LANGUAGE_NOT_SUPPORTED
            13, // ERROR_LANGUAGE_UNAVAILABLE
            14  // ERROR_CANNOT_CHECK_SUPPORT
        )
    }
}

/** Primera alternativa de transcripcion, que es la de mayor confianza. */
private fun Bundle?.firstTranscript(): String? =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

/**
 * Lleva los decibelios de `onRmsChanged` a `0f..1f`.
 *
 * La escala de `SpeechRecognizer` va de -2 dB (silencio) a 10 dB (voz cerca del microfono).
 * No es absoluta y varia entre dispositivos, pero es estable dentro del mismo telefono, que
 * es todo lo que necesitan la animacion y el umbral de interrupcion.
 *
 * Es `internal` para poder probarla en la JVM.
 */
internal fun normalizeRms(rmsdB: Float): Float =
    ((rmsdB - RMS_FLOOR_DB) / (RMS_CEILING_DB - RMS_FLOOR_DB)).coerceIn(0f, 1f)

private const val RMS_FLOOR_DB = -2f
private const val RMS_CEILING_DB = 10f

/**
 * Decide si una transcripcion merece convertirse en una pregunta al backend.
 *
 * Con el microfono abierto todo el rato entra de todo: toses, la mitad de una palabra, y
 * sobre todo muletillas. Cada una de esas gastaria un turno del historial, una llamada al
 * backend y una respuesta leida en voz alta que nadie pidio.
 *
 * Se descarta lo que tenga menos de [MIN_UTTERANCE_LENGTH] caracteres utiles y lo que, una
 * vez quitadas las muletillas, no deje nada.
 *
 * Es `internal` para poder probarla en la JVM sin dispositivo.
 */
internal fun isUsableUtterance(raw: String): Boolean {
    val text = raw.trim()
    if (text.length < MIN_UTTERANCE_LENGTH) return false

    val words = text.lowercase(Locale.ROOT)
        .split(WORD_SEPARATOR)
        .filter { it.isNotBlank() }

    if (words.isEmpty()) return false
    return words.any { it !in FILLER_WORDS }
}

/** Minimo de caracteres. Por debajo de tres no hay ninguna pregunta posible en espanol. */
private const val MIN_UTTERANCE_LENGTH = 3

private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

/**
 * Muletillas que el reconocedor entrega como si fueran frases.
 *
 * Es una lista corta a proposito: cada palabra que se agrega aqui es una palabra que el
 * estudiante ya no puede decir sola. "Si" y "no" no estan en la lista, aunque en la practica
 * los descarta igual el minimo de tres caracteres de [MIN_UTTERANCE_LENGTH]. Es una
 * consecuencia asumida de la regla de F8 y esta documentada en la prueba correspondiente.
 */
private val FILLER_WORDS = setOf(
    "eh", "ehh", "em", "emm", "mm", "mmm", "hm", "hmm", "ah", "ahh", "uh", "uhm",
    "este", "esto", "bueno", "pues", "aja", "ajá", "ya", "o", "sea"
)
