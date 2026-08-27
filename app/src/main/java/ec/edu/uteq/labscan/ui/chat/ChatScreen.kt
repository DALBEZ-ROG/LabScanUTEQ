package ec.edu.uteq.labscan.ui.chat

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ec.edu.uteq.labscan.R
import ec.edu.uteq.labscan.appContainer
import ec.edu.uteq.labscan.ui.common.PermissionStatus
import ec.edu.uteq.labscan.ui.common.openApplicationSettings
import ec.edu.uteq.labscan.ui.common.rememberRuntimePermission
import ec.edu.uteq.labscan.voice.SttErrorKind
import ec.edu.uteq.labscan.voice.SttState
import kotlinx.coroutines.launch

/** Lado del boton de microfono. Grande a proposito: se usa con guantes y sin mirar. */
private val MIC_SIZE = 64.dp

/**
 * Chat con el asistente RAG.
 *
 * El usuario objetivo entra al laboratorio con el telefono en una mano, asi que la via
 * principal es el microfono: se mantiene pulsado, se habla, se suelta y la pregunta se envia
 * sola. Escribir sigue estando, pero como alternativa.
 *
 * @param classId equipo del que se habla, o `null` si se abrio el chat sin contexto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    classId: String?,
    /**
     * ViewModel **compartido** con la conversacion por voz de F8. Llega desde fuera y no se
     * crea aqui a proposito: es lo que hace que las dos pantallas sean la misma conversacion
     * y no dos historiales. Ver `Routes.CONVERSATION`.
     */
    chatViewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenVoice: (String?) -> Unit = {}
) {
    val context = LocalContext.current
    val container = remember(context) { context.appContainer }
    val viewModel: ChatViewModel = chatViewModel
    val sttManager = remember(container) { container.sttManager }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val isVoiceReady by viewModel.isVoiceReady.collectAsStateWithLifecycle()
    val sttState by sttManager.state.collectAsStateWithLifecycle()

    // El microfono no se pide al entrar: el chat funciona escribiendo, y asaltar con un
    // dialogo de permiso nada mas abrir seria grosero. Se pide en el primer intento de dictar.
    val micPermission = rememberRuntimePermission(
        permission = Manifest.permission.RECORD_AUDIO,
        requestOnFirstAppearance = false
    )
    val recognitionAvailable = remember(sttManager) { sttManager.isAvailable }

    var draft by rememberSaveable { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(classId) { viewModel.setEquipmentContext(classId) }

    // Restriccion dura de F6: al salir de la pantalla, la voz calla. Sin esto el asistente
    // sigue hablando sobre la vista de la camara.
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.stopSpeaking()
            sttManager.cancel()
        }
    }

    // Los textos de error se resuelven en composicion: `stringResource` no se puede llamar
    // desde un LaunchedEffect, que es donde hacen falta.
    val micErrorTexts: Map<SttErrorKind, String> =
        SttErrorKind.entries.associateWith { stringResource(it.messageRes) }

    // El dictado llega por el flujo de estados, no por un callback: aqui se consume.
    LaunchedEffect(sttState) {
        when (val state = sttState) {
            is SttState.Result -> {
                draft = ""
                viewModel.send(state.text)
                sttManager.consumed()
            }

            is SttState.Error -> {
                snackbarHostState.showSnackbar(micErrorTexts.getValue(state.kind))
                draft = ""
                sttManager.consumed()
            }

            else -> Unit
        }
    }

    // Cada mensaje nuevo, y el indicador de escritura, arrastran la lista al final.
    LaunchedEffect(uiState.messages.size, uiState.isSending) {
        val lastIndex = uiState.messages.lastIndex
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }

    // El parcial manda mientras se dicta: es la unica senal en pantalla de que se esta oyendo.
    val fieldText = when (val state = sttState) {
        is SttState.PartialResult -> state.text
        is SttState.Listening -> ""
        else -> draft
    }
    val isListening = sttState is SttState.Listening || sttState is SttState.PartialResult

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_titulo)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_volver)
                        )
                    }
                },
                actions = {
                    // Auriculares: pasar de leer a hablar. Se deshabilita, en vez de
                    // desaparecer, cuando el dispositivo no tiene reconocimiento: que el
                    // boton exista y explique por que no funciona es mas util que no verlo.
                    IconButton(
                        onClick = { onOpenVoice(uiState.classId) },
                        enabled = recognitionAvailable
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Headset,
                            contentDescription = stringResource(
                                if (recognitionAvailable) {
                                    R.string.chat_modo_voz
                                } else {
                                    R.string.chat_voz_no_disponible
                                }
                            )
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (uiState.classId != null) {
                EquipmentContextHeader(
                    name = uiState.equipmentName,
                    onClear = viewModel::clearEquipmentContext
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.messages, key = { it.id }) { message ->
                    ChatBubble(
                        message = message,
                        isSpeaking = isSpeaking,
                        canSpeak = isVoiceReady,
                        onToggleSpeak = { viewModel.toggleSpeak(message) }
                    )
                }

                if (uiState.isSending) {
                    item(key = "escribiendo") { TypingIndicator() }
                }

                uiState.error?.let { error ->
                    item(key = "error") {
                        ChatErrorCard(
                            error = error,
                            onRetry = viewModel::retry,
                            onDismiss = viewModel::dismissError
                        )
                    }
                }
            }

            if (uiState.showSuggestions) {
                SuggestionChips(onSuggestionClick = { viewModel.send(it) })
            }

            ChatInputBar(
                text = fieldText,
                onTextChange = { draft = it },
                enabled = !uiState.isSending,
                isListening = isListening,
                recognitionAvailable = recognitionAvailable,
                micPermissionPermanentlyDenied =
                    micPermission.status == PermissionStatus.PERMANENTLY_DENIED,
                onSend = {
                    viewModel.send(draft)
                    draft = ""
                },
                onMicPress = {
                    when {
                        !micPermission.isGranted -> {
                            if (micPermission.status == PermissionStatus.PERMANENTLY_DENIED) {
                                context.openApplicationSettings()
                            } else {
                                micPermission.request()
                            }
                            false
                        }

                        else -> {
                            // Hablarle al asistente mientras el asistente habla no funciona:
                            // el reconocedor se oiria a si mismo.
                            viewModel.stopSpeaking()
                            sttManager.start()
                            true
                        }
                    }
                },
                onMicRelease = { sttManager.stop() },
                onMicUnavailable = {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            micErrorTexts.getValue(SttErrorKind.UNAVAILABLE)
                        )
                    }
                }
            )
        }
    }
}

/**
 * Encabezado con el equipo del que se esta hablando.
 *
 * Se puede quitar: el contrato admite `classId` nulo, y un estudiante puede querer preguntar
 * algo general sin volver a la camara. El historial se conserva al quitarlo.
 */
@Composable
private fun EquipmentContextHeader(name: String, onClear: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_contexto, name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chat_quitar_contexto),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/**
 * Barra inferior: campo de texto, enviar y microfono.
 *
 * @param onMicPress devuelve `true` si el dictado arranco de verdad. Cuando devuelve `false`
 *   (falta el permiso) no se espera a soltar, porque no hay nada que cerrar.
 */
@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    enabled: Boolean,
    isListening: Boolean,
    recognitionAvailable: Boolean,
    micPermissionPermanentlyDenied: Boolean,
    onSend: () -> Unit,
    onMicPress: () -> Boolean,
    onMicRelease: () -> Unit,
    onMicUnavailable: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (!recognitionAvailable) {
                Text(
                    text = stringResource(R.string.voz_error_no_disponible),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp, end = 4.dp)
                )
            } else if (micPermissionPermanentlyDenied) {
                Text(
                    text = stringResource(R.string.voz_permiso_denegado_siempre),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp, end = 4.dp)
                )
            }

            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    enabled = enabled && !isListening,
                    placeholder = {
                        Text(
                            stringResource(
                                if (isListening) R.string.chat_escuchando
                                else R.string.chat_placeholder
                            )
                        )
                    },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp)
                )

                IconButton(
                    onClick = onSend,
                    enabled = enabled && text.isNotBlank() && !isListening,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.chat_enviar)
                    )
                }

                MicButton(
                    isListening = isListening,
                    available = recognitionAvailable,
                    enabled = enabled,
                    onPress = onMicPress,
                    onRelease = onMicRelease,
                    onUnavailable = onMicUnavailable
                )
            }
        }
    }
}

/**
 * Microfono de mantener pulsado.
 *
 * `tryAwaitRelease()` es lo que convierte un boton en un pulsador: la corrutina del gesto se
 * queda esperando en ese punto hasta que el dedo se levanta o el gesto se cancela, y lo que
 * venga despues es el "soltar". Cancelar tambien cuenta, asi que arrastrar el dedo fuera del
 * boton cierra el microfono igual: no se queda grabando.
 */
@Composable
private fun MicButton(
    isListening: Boolean,
    available: Boolean,
    enabled: Boolean,
    onPress: () -> Boolean,
    onRelease: () -> Unit,
    onUnavailable: () -> Unit
) {
    val background = when {
        !available || !enabled -> MaterialTheme.colorScheme.surfaceVariant
        isListening -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val label = stringResource(
        if (isListening) R.string.chat_microfono_escuchando else R.string.chat_microfono
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(start = 4.dp)
            .size(MIC_SIZE)
            .background(background, CircleShape)
            .semantics { contentDescription = label }
            .pointerInput(available, enabled) {
                detectTapGestures(
                    onPress = {
                        if (!available) {
                            onUnavailable()
                            return@detectTapGestures
                        }
                        if (!enabled) return@detectTapGestures
                        val started = onPress()
                        tryAwaitRelease()
                        if (started) onRelease()
                    }
                )
            }
    ) {
        if (isListening) {
            CircularProgressIndicator(
                modifier = Modifier.size(MIC_SIZE - 8.dp),
                color = MaterialTheme.colorScheme.onError,
                strokeWidth = 2.dp
            )
        }
        Icon(
            imageVector = if (available) Icons.Filled.Mic else Icons.Filled.MicOff,
            contentDescription = null,
            tint = when {
                !available || !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                isListening -> MaterialTheme.colorScheme.onError
                else -> MaterialTheme.colorScheme.onPrimary
            },
            modifier = Modifier.size(28.dp)
        )
    }
}

/**
 * Aviso de fallo de red, con reintento.
 *
 * No es una burbuja: un error no lo dijo el asistente. Y **no se lee en voz alta**, por la
 * misma razon por la que no se leen las fuentes.
 */
@Composable
private fun ChatErrorCard(
    error: ChatError,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(error.messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_descartar))
                }
                if (error.retryable) {
                    TextButton(onClick = onRetry) {
                        Text(
                            text = stringResource(R.string.chat_reintentar),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

