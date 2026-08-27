package ec.edu.uteq.labscan.ui.voice

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ec.edu.uteq.labscan.R
import ec.edu.uteq.labscan.appContainer
import ec.edu.uteq.labscan.ui.chat.Author
import ec.edu.uteq.labscan.ui.chat.ChatViewModel
import ec.edu.uteq.labscan.ui.common.PermissionStatus
import ec.edu.uteq.labscan.ui.common.openApplicationSettings
import ec.edu.uteq.labscan.ui.common.rememberRuntimePermission
import ec.edu.uteq.labscan.ui.theme.ErrorRed
import ec.edu.uteq.labscan.ui.theme.OnDark
import ec.edu.uteq.labscan.ui.theme.OnDarkMuted
import ec.edu.uteq.labscan.ui.theme.ScannerBackground
import ec.edu.uteq.labscan.ui.theme.ScannerSurface
import ec.edu.uteq.labscan.ui.theme.UteqGreen

/** Lado de los botones de accion. F8 pide 56 dp como minimo; se usan 64 para guantes. */
private val ACTION_BUTTON_SIZE = 64.dp

/** El de colgar es mayor y va separado: es el unico irreversible de los tres. */
private val HANG_UP_SIZE = 72.dp

/**
 * Conversacion por voz manos libres.
 *
 * El estudiante entra, habla, escucha, vuelve a hablar y sale, **sin tocar la pantalla en
 * ningun momento intermedio**. Los tres botones de abajo son para lo excepcional: silenciar,
 * leer lo dicho, y colgar.
 *
 * ### Como se conecta con el chat escrito
 *
 * Recibe el **mismo** [ChatViewModel] que la pantalla de chat, resuelto en el grafo de
 * navegacion. Esta pantalla no guarda mensajes: los turnos que cierra el reconocedor se
 * mandan por [ChatViewModel.send] con `voiceMode = true`, y las respuestas se leen de su
 * `uiState`. Por eso, al colgar, la conversacion aparece entera en el chat: nunca hubo dos
 * historiales, solo dos formas de mirar el mismo.
 *
 * El enganche entre las dos maquinas ([VoiceCallViewModel] y [ChatViewModel]) vive aqui, en
 * la pantalla, y no dentro de un ViewModel llamando al otro. Un ViewModel que sostiene a
 * otro se lleva mal con los ambitos de navegacion y es imposible de probar por separado;
 * hacerlo con efectos de composicion mantiene los dos independientes.
 *
 * @param classId equipo del que se habla, o `null` si se entro sin contexto.
 */
@Composable
fun VoiceCallScreen(
    classId: String?,
    chatViewModel: ChatViewModel,
    onOpenTranscript: () -> Unit,
    onHangUp: () -> Unit
) {
    val context = LocalContext.current
    val container = remember(context) { context.appContainer }
    val voiceViewModel: VoiceCallViewModel =
        viewModel(factory = VoiceCallViewModel.factory(container))

    val voiceState by voiceViewModel.state.collectAsStateWithLifecycle()
    val amplitude by voiceViewModel.amplitude.collectAsStateWithLifecycle()
    val partial by voiceViewModel.partialText.collectAsStateWithLifecycle()
    val lastAnswer by voiceViewModel.lastAnswer.collectAsStateWithLifecycle()
    val isMuted by voiceViewModel.isMuted.collectAsStateWithLifecycle()
    val chatState by chatViewModel.uiState.collectAsStateWithLifecycle()

    // El micrófono si se pide al entrar, al reves que en el chat escrito: aqui no hay ninguna
    // alternativa a hablar, asi que el permiso no es opcional y pedirlo de entrada evita que
    // el estudiante se quede mirando una pantalla que no reacciona.
    val micPermission = rememberRuntimePermission(
        permission = Manifest.permission.RECORD_AUDIO,
        requestOnFirstAppearance = true
    )

    LaunchedEffect(classId) { chatViewModel.setEquipmentContext(classId) }

    // La conversacion no arranca hasta tener el permiso concedido.
    LaunchedEffect(micPermission.isGranted) {
        if (micPermission.isGranted) voiceViewModel.begin()
    }

    // Turno cerrado -> pregunta al backend, por el ViewModel compartido.
    LaunchedEffect(voiceViewModel) {
        voiceViewModel.questions.collect { question ->
            chatViewModel.send(question, voiceMode = true)
        }
    }

    // Salidas forzadas: sin conexion, o el audio se lo quedo otra app.
    LaunchedEffect(voiceViewModel) {
        voiceViewModel.finished.collect { onHangUp() }
    }

    // Respuesta del backend -> a la maquina de voz. Se lleva la cuenta del ultimo mensaje ya
    // tratado para no releer la misma respuesta en cada recomposicion.
    var handledMessageId by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(chatState.isSending, chatState.messages.size, chatState.error) {
        when {
            chatState.isSending -> voiceViewModel.onThinking()

            chatState.error != null -> {
                val kind = if (chatState.error?.messageRes == R.string.error_red_sin_conexion) {
                    VoiceErrorKind.SIN_CONEXION
                } else {
                    VoiceErrorKind.BACKEND
                }
                voiceViewModel.onError(kind)
            }

            else -> {
                val last = chatState.messages.lastOrNull()
                if (last != null && last.author == Author.ASSISTANT && last.id != handledMessageId) {
                    handledMessageId = last.id
                    voiceViewModel.onAnswer(last.text)
                }
            }
        }
    }

    // Minimizar cierra el microfono y calla al asistente (requisito 8 de F8). Volver lo
    // reanuda, salvo que el telefono siga en una llamada.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, voiceViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> voiceViewModel.pause()
                Lifecycle.Event.ON_START -> voiceViewModel.resume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Al salir de la pantalla el microfono se cierra siempre, pase lo que pase.
            voiceViewModel.end()
        }
    }

    VoiceCallContent(
        voiceState = voiceState,
        amplitude = amplitude,
        partial = partial,
        lastAnswer = lastAnswer,
        equipmentName = chatState.equipmentName,
        hasContext = chatState.classId != null,
        isMuted = isMuted,
        permissionGranted = micPermission.isGranted,
        permissionPermanentlyDenied =
            micPermission.status == PermissionStatus.PERMANENTLY_DENIED,
        onRequestPermission = {
            if (micPermission.status == PermissionStatus.PERMANENTLY_DENIED) {
                context.openApplicationSettings()
            } else {
                micPermission.request()
            }
        },
        onToggleMute = voiceViewModel::toggleMute,
        onOpenTranscript = onOpenTranscript,
        onHangUp = onHangUp
    )
}

/**
 * Parte visual, sin dependencias de ViewModel.
 *
 * Va aparte para poder verla en una vista previa y para que la logica de arriba se lea de un
 * tiron sin tener el `Column` en medio.
 */
@Composable
private fun VoiceCallContent(
    voiceState: VoiceState,
    amplitude: Float,
    partial: String,
    lastAnswer: String,
    equipmentName: String,
    hasContext: Boolean,
    isMuted: Boolean,
    permissionGranted: Boolean,
    permissionPermanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenTranscript: () -> Unit,
    onHangUp: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                // Degradado y no color plano: da profundidad y separa la forma central del
                // fondo sin necesidad de dibujarle un borde.
                Brush.verticalGradient(
                    colors = listOf(
                        ScannerSurface,
                        ScannerBackground,
                        Color.Black
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasContext && equipmentName.isNotBlank()) {
                Text(
                    text = stringResource(R.string.chat_contexto, equipmentName),
                    style = MaterialTheme.typography.titleMedium,
                    color = UteqGreen,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }

            Text(
                text = statusText(voiceState, isMuted),
                style = MaterialTheme.typography.headlineSmall,
                color = OnDark,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (permissionGranted) {
                    // La forma es la realimentacion principal de esta pantalla, asi que para
                    // un lector de pantalla tiene que decir el estado, no quedarse muda.
                    val estado = statusText(voiceState, isMuted)
                    val descripcionForma = stringResource(R.string.vozcall_forma)
                    VoiceOrb(
                        state = voiceState,
                        amplitude = amplitude,
                        modifier = Modifier
                            .fillMaxWidth(0.82f)
                            .aspectRatio(1f)
                            .semantics { contentDescription = "$descripcionForma: $estado" }
                    )
                } else {
                    PermissionNotice(
                        permanentlyDenied = permissionPermanentlyDenied,
                        onRequest = onRequestPermission
                    )
                }
            }

            TranscriptArea(
                voiceState = voiceState,
                partial = partial,
                lastAnswer = lastAnswer,
                isMuted = isMuted
            )

            ActionRow(
                isMuted = isMuted,
                enabled = permissionGranted,
                onToggleMute = onToggleMute,
                onOpenTranscript = onOpenTranscript,
                onHangUp = onHangUp
            )
        }
    }
}

/**
 * Zona de texto de la llamada.
 *
 * **No son burbujas de chat a proposito.** Esto es una llamada: se ve lo que se esta diciendo
 * ahora, no el historial. El historial esta a un boton de distancia, en el chat escrito.
 *
 * La altura es fija para que la forma animada de arriba no salte cada vez que el texto crece
 * o se vacia, que en una pantalla que se mira de reojo es muy molesto.
 */
@Composable
private fun TranscriptArea(
    voiceState: VoiceState,
    partial: String,
    lastAnswer: String,
    isMuted: Boolean
) {
    val text = when {
        isMuted -> stringResource(R.string.vozcall_silenciado)
        voiceState is VoiceState.Error -> stringResource(voiceState.kind.messageRes)
        partial.isNotBlank() -> partial
        lastAnswer.isNotBlank() -> lastAnswer.firstLine()
        voiceState is VoiceState.Listening -> stringResource(R.string.vozcall_invitacion)
        else -> ""
    }

    // El texto del estudiante se ve en blanco y el del asistente apagado: es la unica pista
    // de quien esta hablando, ya que no hay burbujas que lo distingan.
    val color = if (partial.isNotBlank() && !isMuted) OnDark else OnDarkMuted

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 3
        )
    }
}

/** Los tres botones. Colgar va separado y en rojo. */
@Composable
private fun ActionRow(
    isMuted: Boolean,
    enabled: Boolean,
    onToggleMute: () -> Unit,
    onOpenTranscript: () -> Unit,
    onHangUp: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularAction(
            icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
            description = stringResource(
                if (isMuted) R.string.vozcall_activar else R.string.vozcall_silenciar
            ),
            container = ScannerSurface,
            content = if (isMuted) ErrorRed else OnDark,
            size = ACTION_BUTTON_SIZE,
            enabled = enabled,
            onClick = onToggleMute
        )

        Box(modifier = Modifier.width(20.dp))

        CircularAction(
            icon = Icons.AutoMirrored.Filled.Chat,
            description = stringResource(R.string.vozcall_transcripcion),
            container = ScannerSurface,
            content = OnDark,
            size = ACTION_BUTTON_SIZE,
            enabled = true,
            onClick = onOpenTranscript
        )

        // Hueco doble antes de colgar: es el unico de los tres que no tiene vuelta atras, y
        // se pulsa sin mirar. Separarlo es mas eficaz que cambiarle solo el color.
        Box(modifier = Modifier.width(44.dp))

        CircularAction(
            icon = Icons.Filled.CallEnd,
            description = stringResource(R.string.vozcall_colgar),
            container = ErrorRed,
            content = Color.White,
            size = HANG_UP_SIZE,
            enabled = true,
            onClick = onHangUp
        )
    }
}

@Composable
private fun CircularAction(
    icon: ImageVector,
    description: String,
    container: Color,
    content: Color,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(size / 2.4f)
            )
        }
    }
}

/** Explicacion cuando falta el permiso, en lugar de una pantalla que no reacciona. */
@Composable
private fun PermissionNotice(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.vozcall_permiso_titulo),
            style = MaterialTheme.typography.titleLarge,
            color = OnDark,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.vozcall_permiso_detalle),
            style = MaterialTheme.typography.bodyMedium,
            color = OnDarkMuted,
            textAlign = TextAlign.Center
        )
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(containerColor = UteqGreen)
        ) {
            Text(
                stringResource(
                    if (permanentlyDenied) {
                        R.string.vozcall_permiso_ajustes
                    } else {
                        R.string.vozcall_permiso_conceder
                    }
                )
            )
        }
    }
}

/** Texto de estado. Es lo que se lee de un vistazo; la forma animada lo refuerza. */
@Composable
private fun statusText(state: VoiceState, isMuted: Boolean): String = when {
    isMuted -> stringResource(R.string.vozcall_activar)
    state is VoiceState.Connecting -> stringResource(R.string.vozcall_estado_conectando)
    state is VoiceState.Listening -> stringResource(R.string.vozcall_estado_escuchando)
    state is VoiceState.Capturing -> stringResource(R.string.vozcall_estado_capturando)
    state is VoiceState.Thinking -> stringResource(R.string.vozcall_estado_pensando)
    state is VoiceState.Speaking -> stringResource(R.string.vozcall_estado_hablando)
    else -> stringResource(R.string.vozcall_estado_escuchando)
}

/**
 * Primera frase de la respuesta, para el resumen de una linea.
 *
 * La respuesta entera se esta oyendo; en pantalla solo hace falta la referencia de por donde
 * va. La respuesta completa, con sus fuentes, esta en el chat escrito.
 */
private fun String.firstLine(): String {
    val end = indexOfFirst { it == '.' || it == '?' || it == '!' }
    return if (end > 0) substring(0, end + 1).trim() else trim()
}
