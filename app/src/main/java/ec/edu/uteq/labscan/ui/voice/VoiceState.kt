package ec.edu.uteq.labscan.ui.voice

import androidx.annotation.StringRes
import ec.edu.uteq.labscan.R
import ec.edu.uteq.labscan.voice.SttErrorKind

/**
 * En que punto de la conversacion por voz esta la app.
 *
 * Son exactamente los seis estados de F8, ni uno mas. El ciclo normal no necesita que el
 * estudiante toque nada:
 *
 * ```
 *                     +---------------------------------------------------+
 *                     |                                                   |
 *                     v                                                   |
 *  [Connecting] --> [Listening] --voz--> [Capturing] --silencio 900 ms--> [Thinking]
 *       |               ^  ^                  ^                              |
 *       |               |  |                  |                              | respuesta
 *       |               |  |                  | interrupcion                 v
 *       |               |  +------------------+---------------------------[Speaking]
 *       |               |         (voz sostenida > 300 ms sobre el umbral)   |
 *       |               |                                                    |
 *       |               +----------------------------------------------------+
 *       |                              (termina de hablar)
 *       v
 *   [Error(kind)] --> el estudiante reintenta, o cuelga
 * ```
 *
 * Las tres transiciones que no son obvias:
 *
 * - **Capturing no vuelve a Listening.** Una vez que hay voz, o se cierra el turno o se
 *   descarta por muletilla, y en ese segundo caso el reconocedor reinicia y se vuelve a
 *   Listening por el camino de siempre.
 * - **Speaking a Capturing es la interrupcion**, y es la unica flecha que se dispara por
 *   amplitud y no por texto.
 * - **Error no es terminal.** Se puede volver a Listening; por eso la especificacion lo llama
 *   "fallo recuperable".
 */
sealed interface VoiceState {

    /** Preparando los motores de voz. Es el estado inicial y dura decimas de segundo. */
    data object Connecting : VoiceState

    /** Microfono abierto, todavia sin nada que transcribir. */
    data object Listening : VoiceState

    /** Se detecto voz y hay transcripcion parcial en curso. */
    data object Capturing : VoiceState

    /** La pregunta salio al backend y se espera la respuesta. */
    data object Thinking : VoiceState

    /** El asistente esta leyendo la respuesta. El microfono sigue abierto, vigilando. */
    data object Speaking : VoiceState

    /** Fallo recuperable, con un mensaje en espanol que la pantalla muestra tal cual. */
    data class Error(val kind: VoiceErrorKind) : VoiceState
}

/**
 * Motivos por los que la conversacion por voz se detiene.
 *
 * Se agrupan por lo que el estudiante puede hacer al respecto, no por la causa tecnica: con
 * bata y guantes, saber si fallo el reconocedor o el servidor no le sirve de nada; saber si
 * tiene que repetir, dar un permiso o escribir, si.
 */
enum class VoiceErrorKind(@param:StringRes val messageRes: Int) {
    /** El dispositivo no tiene motor de reconocimiento. No se entra siquiera al modo. */
    SIN_RECONOCEDOR(R.string.vozcall_error_sin_reconocedor),

    /** Falta el permiso de microfono. */
    SIN_PERMISO(R.string.vozcall_error_sin_permiso),

    /** El microfono no se pudo mantener abierto. */
    MICROFONO(R.string.vozcall_error_microfono),

    /** No hay red. Es el unico error que ademas se dice en voz alta, y una sola vez. */
    SIN_CONEXION(R.string.vozcall_error_sin_conexion),

    /** El backend fallo o no respondio. */
    BACKEND(R.string.vozcall_error_backend),

    /** Cualquier otra cosa. */
    DESCONOCIDO(R.string.vozcall_error_desconocido)
}

/**
 * Traduce un fallo del reconocedor al vocabulario de la conversacion por voz.
 *
 * Es `internal` para poder probarlo en la JVM: es justo el sitio donde uno se equivoca de
 * caso y el error acaba mostrando el mensaje de otra cosa.
 */
internal fun SttErrorKind.toVoiceErrorKind(): VoiceErrorKind = when (this) {
    SttErrorKind.UNAVAILABLE -> VoiceErrorKind.SIN_RECONOCEDOR
    SttErrorKind.PERMISSION -> VoiceErrorKind.SIN_PERMISO
    SttErrorKind.NETWORK -> VoiceErrorKind.SIN_CONEXION
    // BUSY llega cuando el reconocedor no se recupero tras los reinicios: para el estudiante
    // eso es "el microfono no funciona", no "el motor esta ocupado".
    SttErrorKind.BUSY -> VoiceErrorKind.MICROFONO
    SttErrorKind.NO_MATCH -> VoiceErrorKind.MICROFONO
    SttErrorKind.UNKNOWN -> VoiceErrorKind.DESCONOCIDO
}
