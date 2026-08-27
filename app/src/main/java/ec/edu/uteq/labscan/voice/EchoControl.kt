package ec.edu.uteq.labscan.voice

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import ec.edu.uteq.labscan.App

/**
 * Cancelacion de eco y supresion de ruido sobre la captura del microfono.
 *
 * ### El problema que intenta resolver
 *
 * En una conversacion manos libres el altavoz alimenta al microfono. Si nadie lo evita, el
 * reconocedor transcribe lo que el propio asistente acaba de decir, eso se manda al backend
 * como si fuera una pregunta, el backend responde, y la app se queda hablando sola en un
 * bucle. Con el altavoz al maximo pasa en el primer turno.
 *
 * ### Lo que esta clase puede y lo que no
 *
 * Hay que decirlo claro porque condiciona todo el diseno del modo de voz:
 * **`AcousticEchoCanceler` no se puede aplicar a `SpeechRecognizer`.**
 *
 * Los dos efectos de `android.media.audiofx` se enganchan a una sesion de audio concreta, la
 * del `AudioRecord` que captura. `SpeechRecognizer` crea y gestiona su `AudioRecord` por
 * dentro, en el proceso del motor de reconocimiento, y **no expone el identificador de esa
 * sesion** por ninguna via publica. Sin ese identificador, `create()` no tiene a que
 * engancharse.
 *
 * Aplicarlos de verdad exigiria abandonar `SpeechRecognizer` y capturar con `AudioRecord`
 * propio, lo que a su vez significaria reconocimiento de voz propio o en la nube. Las dos
 * cosas estan prohibidas en este proyecto (CLAUDE.md: voz con APIs de plataforma; F8: nada
 * de servicios de voz en la nube).
 *
 * Por eso esta clase existe pero **no es la defensa principal contra el eco**. La defensa
 * real, la que se verifico con el altavoz al maximo, son otras tres cosas, y estan en
 * [ec.edu.uteq.labscan.ui.voice.VoiceCallViewModel]:
 *
 * 1. Mientras el asistente habla, el reconocedor pasa a [ListenMode.WATCH] y su texto se
 *    **descarta entero**. Aunque transcriba al altavoz, ese texto no llega a ninguna parte.
 * 2. El umbral de amplitud para dar por buena una interrupcion **sube** mientras habla el
 *    asistente, por encima de lo que el altavoz devuelve al microfono.
 * 3. El foco de audio con `TRANSIENT_MAY_DUCK` baja el volumen de lo demas, y la sesion de
 *    reconocimiento se reinicia limpia en cuanto se confirma la interrupcion.
 *
 * Lo que si hace esta clase es engancharse cuando **si** hay un identificador de sesion, y
 * dejar constancia en el log de si el dispositivo trae los efectos. En un Samsung A56 los
 * dos estan disponibles y el propio motor de reconocimiento ya los aplica por su cuenta al
 * capturar de `VOICE_RECOGNITION`, que es la razon de que el eco se comporte razonablemente
 * bien antes incluso de las tres medidas de arriba.
 *
 * Queda anotado en `docs/DECISIONES.md` (D-024).
 */
class EchoControl {

    private var canceler: AcousticEchoCanceler? = null
    private var suppressor: NoiseSuppressor? = null

    /** `true` si el dispositivo trae cancelador de eco. */
    val isEchoCancelerAvailable: Boolean
        get() = AcousticEchoCanceler.isAvailable()

    /** `true` si el dispositivo trae supresor de ruido. */
    val isNoiseSuppressorAvailable: Boolean
        get() = NoiseSuppressor.isAvailable()

    /**
     * Engancha los efectos a una sesion de captura.
     *
     * @param audioSessionId identificador de la sesion del `AudioRecord`. Con
     *   `SpeechRecognizer` no hay ninguno disponible; ver la nota de la clase.
     * @return `true` si quedo enganchado al menos el cancelador de eco.
     */
    fun attach(audioSessionId: Int): Boolean {
        release()

        if (isEchoCancelerAvailable) {
            canceler = runCatching { AcousticEchoCanceler.create(audioSessionId) }
                .onFailure { Log.w(App.LOG_TAG, "No se pudo crear el cancelador de eco", it) }
                .getOrNull()
                ?.apply { enabled = true }
        }

        if (isNoiseSuppressorAvailable) {
            suppressor = runCatching { NoiseSuppressor.create(audioSessionId) }
                .onFailure { Log.w(App.LOG_TAG, "No se pudo crear el supresor de ruido", it) }
                .getOrNull()
                ?.apply { enabled = true }
        }

        val attached = canceler?.enabled == true
        Log.i(
            App.LOG_TAG,
            "Control de eco: cancelador=${canceler?.enabled == true}, " +
                "supresor=${suppressor?.enabled == true}, sesion=$audioSessionId"
        )
        return attached
    }

    /**
     * Deja constancia de lo que trae el dispositivo.
     *
     * Se llama al entrar al modo de voz. No cambia el comportamiento: sirve para que, si un
     * telefono se comporta mal con el eco, el log diga si es que le faltan los efectos o es
     * otra cosa.
     */
    fun logDeviceSupport() {
        Log.i(
            App.LOG_TAG,
            "Efectos de audio del dispositivo: eco=$isEchoCancelerAvailable, " +
                "ruido=$isNoiseSuppressorAvailable. No se enganchan a SpeechRecognizer, " +
                "que no expone su sesion de captura (ver EchoControl)."
        )
    }

    /** Suelta los efectos. Obligatorio: son recursos del sistema, limitados y compartidos. */
    fun release() {
        canceler?.release()
        canceler = null
        suppressor?.release()
        suppressor = null
    }
}
