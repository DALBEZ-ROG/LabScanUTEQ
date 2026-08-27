package ec.edu.uteq.labscan.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import ec.edu.uteq.labscan.App
import ec.edu.uteq.labscan.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * En que punto del dictado estamos.
 *
 * Es un tipo cerrado para que la pantalla no tenga que adivinar combinando banderas. El
 * recorrido normal es:
 *
 * ```
 * Idle --start()--> Listening --habla--> PartialResult* --stop()--> Result --> Idle
 *                       |
 *                       +--fallo--> Error --> Idle
 * ```
 */
sealed interface SttState {
    /** Nadie esta dictando. Estado inicial y estado al que se vuelve siempre. */
    data object Idle : SttState

    /** El microfono esta abierto y todavia no hay ni una palabra reconocida. */
    data object Listening : SttState

    /**
     * Transcripcion provisional. Cambia varias veces por segundo y puede corregirse a si
     * misma: la pantalla la muestra en el campo de texto para que se vea que algo pasa.
     */
    data class PartialResult(val text: String) : SttState

    /** Transcripcion definitiva. Quien escucha esto envia la pregunta. */
    data class Result(val text: String) : SttState

    /** Algo salio mal. [kind] decide el texto que se muestra. */
    data class Error(val kind: SttErrorKind) : SttState
}

/**
 * Motivos de fallo del dictado, ya traducidos desde los enteros de [SpeechRecognizer].
 *
 * Se agrupan a proposito: al estudiante no le sirve distinguir entre `ERROR_CLIENT` y
 * `ERROR_SERVER`, le sirve saber si tiene que repetir, dar permiso o escribir.
 */
enum class SttErrorKind(@param:StringRes val messageRes: Int) {
    /** No se entendio nada, o el estudiante solto el boton sin hablar. */
    NO_MATCH(R.string.voz_error_sin_texto),

    /** El reconocedor necesita red y no la hay. */
    NETWORK(R.string.voz_error_red),

    /** Falta el permiso de microfono. */
    PERMISSION(R.string.voz_error_permiso),

    /** El reconocedor esta ocupado con otra peticion. */
    BUSY(R.string.voz_error_ocupado),

    /** No hay ningun motor de reconocimiento instalado en el dispositivo. */
    UNAVAILABLE(R.string.voz_error_no_disponible),

    /** Cualquier otra cosa. */
    UNKNOWN(R.string.voz_error_desconocido)
}

/**
 * Dictado de la pregunta, sobre `android.speech.SpeechRecognizer`.
 *
 * De plataforma, no de la nube (CLAUDE.md). En la mayoria de dispositivos Android el
 * reconocimiento lo resuelve Google y puede necesitar red, pero la app no manda el audio a
 * ningun servicio propio: entrega el microfono al sistema y recibe texto.
 *
 * ### Hilo principal, obligatorio
 *
 * `SpeechRecognizer` **solo** puede crearse y manejarse desde el hilo principal; desde otro
 * lanza `RuntimeException` sin explicar por que. Por eso los metodos publicos estan marcados
 * con `@MainThread` y se llaman desde los callbacks de Compose, que ya viven ahi.
 *
 * ### Cuando no hay motor
 *
 * [isAvailable] es `false` en dispositivos sin motor de reconocimiento, y en Android 11 o
 * superior tambien si faltara la consulta `android.speech.RecognitionService` del manifiesto.
 * La pantalla entonces deshabilita el microfono y explica por que, en vez de ofrecer un boton
 * que no hace nada.
 */
class SttManager(context: Context) {

    private val appContext = context.applicationContext

    /**
     * `true` si el dispositivo puede transcribir voz.
     *
     * Se consulta en cada acceso y no se cachea: el estudiante puede instalar el motor de voz
     * desde la tienda sin cerrar la app.
     */
    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    private val _state = MutableStateFlow<SttState>(SttState.Idle)

    /** Estado del dictado. La pantalla lo observa para pintar el microfono y el campo. */
    val state: StateFlow<SttState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /**
     * Ultima transcripcion parcial.
     *
     * Existe porque algunos motores entregan `onResults` vacio cuando se les corta pronto,
     * pero ya habian emitido parciales buenos. Sin esto, soltar el boton un poco rapido
     * perderia la frase entera.
     */
    private var lastPartial: String = ""

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = SttState.Listening
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.firstTranscript() ?: return
            if (text.isBlank()) return
            lastPartial = text
            _state.value = SttState.PartialResult(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results.firstTranscript()?.takeIf { it.isNotBlank() }
                ?: lastPartial.takeIf { it.isNotBlank() }

            _state.value = if (text != null) {
                SttState.Result(text)
            } else {
                SttState.Error(SttErrorKind.NO_MATCH)
            }
            lastPartial = ""
        }

        override fun onError(error: Int) {
            // Un ERROR_NO_MATCH despues de haber reconocido algo no es un fallo: es el motor
            // cerrando tarde. Se rescata lo que ya se habia entendido.
            if (error == SpeechRecognizer.ERROR_NO_MATCH && lastPartial.isNotBlank()) {
                _state.value = SttState.Result(lastPartial)
                lastPartial = ""
                return
            }
            Log.d(App.LOG_TAG, "Dictado fallido, codigo $error")
            _state.value = SttState.Error(error.toErrorKind())
            lastPartial = ""
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /**
     * Abre el microfono.
     *
     * Es el gesto de mantener pulsado. No comprueba el permiso: de eso se encarga la pantalla
     * antes de llamar, con la misma experiencia que el permiso de camara de F1. Si se llamara
     * sin permiso, el motor responde `ERROR_INSUFFICIENT_PERMISSIONS` y acaba en
     * [SttErrorKind.PERMISSION], asi que tampoco se rompe nada.
     */
    @MainThread
    fun start() {
        if (!isAvailable) {
            _state.value = SttState.Error(SttErrorKind.UNAVAILABLE)
            return
        }

        // Si quedaba una sesion abierta se cancela: dos escuchas a la vez dan ERROR_BUSY.
        recognizer?.cancel()

        val speech = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }

        lastPartial = ""
        _state.value = SttState.Listening
        try {
            speech.startListening(buildIntent())
        } catch (error: Exception) {
            Log.e(App.LOG_TAG, "No se pudo abrir el microfono", error)
            _state.value = SttState.Error(SttErrorKind.UNKNOWN)
        }
    }

    /**
     * Cierra el microfono y pide la transcripcion definitiva.
     *
     * Es el gesto de soltar. No devuelve el texto: llega por [state] como [SttState.Result],
     * porque el motor tarda unas decimas en decidir.
     */
    @MainThread
    fun stop() {
        recognizer?.stopListening()
    }

    /** Aborta sin transcribir. Para cuando el estudiante se arrepiente a mitad. */
    @MainThread
    fun cancel() {
        recognizer?.cancel()
        lastPartial = ""
        _state.value = SttState.Idle
    }

    /**
     * Vuelve a [SttState.Idle].
     *
     * La pantalla lo llama despues de consumir un [SttState.Result] o un [SttState.Error];
     * si no, el mismo resultado se volveria a procesar en cada recomposicion.
     */
    fun consumed() {
        _state.value = SttState.Idle
    }

    /** Suelta el reconocedor. Lo llama `AppContainer.close()`. */
    @MainThread
    fun release() {
        recognizer?.destroy()
        recognizer = null
        _state.value = SttState.Idle
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // FREE_FORM y no WEB_SEARCH: el estudiante hace preguntas completas, no teclea
            // palabras sueltas en un buscador.
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, SPEECH_LOCALE)
            // PREFERENCE es lo que evita que el motor devuelva ingles en un telefono cuyo
            // sistema esta en ingles pero cuyo dueno habla espanol.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, SPEECH_LOCALE)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            // Parciales en vivo: sin esto el campo de texto se queda vacio hasta el final y
            // parece que la app no esta escuchando.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }

    private companion object {
        /** Espanol de Ecuador. Si el motor no lo tiene, cae solo al espanol que tenga. */
        val SPEECH_LOCALE: String = Locale.forLanguageTag("es-EC").toLanguageTag()
    }
}

/** Primera alternativa de transcripcion, que es la de mayor confianza. */
private fun Bundle?.firstTranscript(): String? =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

/**
 * Agrupa los codigos de [SpeechRecognizer] en los casos que la interfaz sabe explicar.
 *
 * Es `internal` para poder probarlo en la JVM: los codigos son constantes enteras y el mapeo
 * es justo el sitio donde uno se equivoca de numero.
 */
internal fun Int.toErrorKind(): SttErrorKind = when (this) {
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SttErrorKind.NO_MATCH

    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SttErrorKind.NETWORK

    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SttErrorKind.PERMISSION

    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SttErrorKind.BUSY

    else -> SttErrorKind.UNKNOWN
}
