package ec.edu.uteq.labscan.ui.scanner

import android.Manifest
import android.content.Context
import android.view.WindowManager
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ec.edu.uteq.labscan.BuildConfig
import ec.edu.uteq.labscan.R
import ec.edu.uteq.labscan.appContainer
import ec.edu.uteq.labscan.camera.CameraBindResult
import ec.edu.uteq.labscan.camera.CameraBinder
import ec.edu.uteq.labscan.camera.FrameAnalyzer
import ec.edu.uteq.labscan.detection.Detection
import ec.edu.uteq.labscan.detection.DetectorStatus
import ec.edu.uteq.labscan.ui.common.PermissionStatus
import ec.edu.uteq.labscan.ui.common.findActivity
import ec.edu.uteq.labscan.ui.common.openApplicationSettings
import ec.edu.uteq.labscan.ui.common.rememberRuntimePermission
import ec.edu.uteq.labscan.ui.sheet.EquipmentSheet
import java.util.Locale

/**
 * Pantalla principal de LabScan UTEQ.
 *
 * Apila, de abajo a arriba: la vista previa de la camara, el [DetectionOverlay] con las
 * cajas, la barra superior y, solo en compilaciones de depuracion, el contador de FPS.
 *
 * La pantalla tiene tres caras segun el permiso de camara: concedido, negado (se puede
 * volver a pedir) y negado para siempre (solo queda ir a los ajustes del sistema).
 */
@Composable
fun ScannerScreen(
    onOpenDiagnostics: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenChat: (String) -> Unit = {},
    onOpenVoice: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val container = remember(context) { context.appContainer }
    val viewModel: ScannerViewModel = viewModel(
        factory = ScannerViewModel.factory(container)
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val detectorStatus by container.detectorStatus.collectAsStateWithLifecycle()
    // La camara si se pide al entrar: sin ella esta pantalla no sirve para nada.
    val permission = rememberRuntimePermission(Manifest.permission.CAMERA)

    when (permission.status) {
        PermissionStatus.GRANTED -> CameraPreviewContent(
            viewModel = viewModel,
            facing = uiState.facing,
            message = uiState.message,
            detectorStatus = detectorStatus,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenSettings = onOpenSettings,
            onOpenChat = onOpenChat,
            onOpenVoice = onOpenVoice
        )

        PermissionStatus.DENIED -> CameraPermissionContent(
            permanentlyDenied = false,
            onRequestPermission = permission.request
        )

        PermissionStatus.PERMANENTLY_DENIED -> CameraPermissionContent(
            permanentlyDenied = true,
            onRequestPermission = permission.request
        )
    }
}

// ---------------------------------------------------------------------------------------
// Vista previa + overlay
// ---------------------------------------------------------------------------------------

/**
 * Vista previa a pantalla completa, con las cajas encima.
 *
 * El [PreviewView] se crea una sola vez con `remember` y se entrega tal cual a
 * `AndroidView`: recrearlo en cada recomposicion provocaria parpadeos.
 */
@Composable
private fun CameraPreviewContent(
    viewModel: ScannerViewModel,
    facing: CameraFacing,
    message: Int?,
    detectorStatus: DetectorStatus,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenVoice: (String) -> Unit
) {
    val context = LocalContext.current
    val container = remember(context) { context.appContainer }
    val lifecycleOwner = LocalLifecycleOwner.current

    val binder = remember(context) { CameraBinder(context, container.analysisExecutor) }
    val analyzer = remember(viewModel) {
        FrameAnalyzer(container.detector) { result -> viewModel.onFrameAnalyzed(result) }
    }

    val previewView = remember(context) {
        PreviewView(context).apply {
            // FILL_CENTER es obligatorio: BoxMapper asume exactamente este recorte.
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // COMPATIBLE usa TextureView. Cuesta algo mas de memoria que PERFORMANCE, pero
            // se comporta bien al superponerle el Canvas del overlay.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // El enganche vive en una corrutina de Compose, no en un scope propio: si la pantalla
    // desaparece a mitad del arranque de la camara, se cancela sola.
    LaunchedEffect(binder, previewView, lifecycleOwner, facing) {
        val selector = facing.toCameraSelector()
        val result = if (binder.isBound) {
            binder.switchCamera(selector)
        } else {
            binder.bind(lifecycleOwner, previewView, selector, analyzer)
        }
        when (result) {
            // Exito: no hay nada que avisar. El aviso pendiente, si lo hay, lo borra
            // onMessageShown() cuando el snackbar termina de mostrarse.
            is CameraBindResult.Success -> Unit
            is CameraBindResult.CameraUnavailable -> viewModel.onCameraUnavailable()
            is CameraBindResult.Failed -> viewModel.onCameraFailed()
        }
    }

    // Soltar la camara y el analizador al salir de composicion.
    DisposableEffect(binder, analyzer) {
        onDispose {
            analyzer.stop()
            binder.unbind()
        }
    }

    // La pantalla no debe apagarse mientras se apunta a un equipo: el usuario esta mirando,
    // no tocando. Se limita a esta pantalla y se retira al salir.
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    // El texto se resuelve aqui y no dentro del efecto: `Context.getString()` desde una
    // corrutina no se entera de los cambios de configuracion.
    val messageText = message?.let { stringResource(it) }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.onMessageShown()
        }
    }

    // Se recogen como State y NO se leen aqui: se pasan al overlay como lambdas para que la
    // lectura ocurra en la fase de dibujo. Leerlas en este composable obligaria a recomponer
    // toda la pantalla en cada frame.
    val detections: State<List<Detection>> = viewModel.detections.collectAsStateWithLifecycle()
    val geometry: State<FrameGeometry?> = viewModel.frameGeometry.collectAsStateWithLifecycle()
    val selected: State<Detection?> = viewModel.selectedDetection.collectAsStateWithLifecycle()

    // La ficha si se lee aqui: aparece y desaparece, no cambia treinta veces por segundo.
    val sheet by viewModel.equipmentSheet.collectAsStateWithLifecycle()
    val sheetLoading by viewModel.sheetLoading.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val isVoiceReady by viewModel.isVoiceReady.collectAsStateWithLifecycle()

    // Al salir del scanner la voz calla: si no, el asistente seguiria leyendo la ficha sobre
    // la pantalla de ajustes o la de diagnostico.
    DisposableEffect(viewModel) {
        onDispose { viewModel.stopSpeaking() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        DetectionOverlay(
            detections = { detections.value },
            geometry = { geometry.value },
            selected = { selected.value },
            modelInputSize = viewModel.modelInputSize,
            onDetectionTapped = viewModel::onDetectionSelected,
            modifier = Modifier.fillMaxSize()
        )

        ScannerTopBar(
            onToggleCamera = viewModel::toggleCamera,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // Los dos avisos se apilan en una sola columna. Antes cada uno se posicionaba por
        // su cuenta y, con los dos a la vez, quedaban uno encima del otro.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (detectorStatus is DetectorStatus.Stub) {
                StubDetectorBanner(reason = detectorStatus.reason)
            }
            if (connection != ConnectionState.ONLINE) {
                ConnectionBanner(connection)
            }
        }

        // Indicador de carga de la ficha. Va en el centro, sobre la caja tocada, porque es
        // ahi donde el estudiante esta mirando cuando espera.
        if (sheetLoading) {
            val loadingLabel = stringResource(R.string.ficha_cargando)
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    // Sin esto el lector de pantalla anuncia un progreso sin nombre: no
                    // dice que se esta cargando ni de que.
                    .semantics { contentDescription = loadingLabel },
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (BuildConfig.DEBUG) {
            DebugHud(
                viewModel = viewModel,
                geometry = geometry,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(16.dp)
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
        )
    }

    // La hoja se declara fuera del Box: ModalBottomSheet se dibuja en su propia ventana y
    // no debe participar en el apilado de la vista previa.
    sheet?.let { sheetState ->
        EquipmentSheet(
            state = sheetState,
            onDismiss = viewModel::onSheetDismissed,
            isSpeaking = isSpeaking,
            canSpeak = isVoiceReady,
            onToggleSpeak = viewModel::onToggleSheetSpeech,
            onAskAssistant = {
                // Cerrar la ficha antes de navegar deja la deteccion en vivo lista para
                // cuando el estudiante vuelva, y calla cualquier lectura en curso.
                val classId = sheetState.equipment.classId
                viewModel.onSheetDismissed()
                onOpenChat(classId)
            },
            onTalkToAssistant = {
                val classId = sheetState.equipment.classId
                viewModel.onSheetDismissed()
                onOpenVoice(classId)
            }
        )
    }
}

/**
 * Barra superior transparente, sobre la imagen de la camara.
 *
 * Lleva detras un degradado oscuro porque, sin el, el titulo blanco desaparece cuando la
 * camara apunta a una pared clara o a una ventana.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScannerTopBar(
    onToggleCamera: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                    )
                )
        )
        TopAppBar(
            title = { Text(text = stringResource(R.string.scanner_titulo)) },
            actions = {
                IconButton(onClick = onToggleCamera) {
                    Icon(
                        imageVector = Icons.Filled.Cameraswitch,
                        contentDescription = stringResource(R.string.scanner_cambiar_camara)
                    )
                }
                IconButton(onClick = onOpenDiagnostics) {
                    Icon(
                        imageVector = Icons.Filled.Insights,
                        contentDescription = stringResource(R.string.diagnostico_abrir)
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.ajustes_abrir)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
                titleContentColor = Color.White,
                actionIconContentColor = Color.White
            )
        )
    }
}

/**
 * Aviso de que las cajas son de prueba.
 *
 * Lo exige la regla 2 de CLAUDE.md: la app funciona sin `model.tflite`, pero el usuario
 * tiene que saber que lo que ve no son detecciones reales.
 */
@Composable
private fun StubDetectorBanner(reason: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(reason),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onTertiary,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.92f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/**
 * Aviso de conexion.
 *
 * Solo aparece cuando hay algo que decir. Distingue los dos casos porque las consecuencias
 * son distintas: sin red la ficha local sigue sirviendo, y con el backend caido lo unico
 * que falta es el asistente.
 */
@Composable
private fun ConnectionBanner(state: ConnectionState, modifier: Modifier = Modifier) {
    val text = when (state) {
        ConnectionState.OFFLINE -> stringResource(R.string.conexion_sin_red)
        ConnectionState.BACKEND_UNREACHABLE -> stringResource(R.string.conexion_sin_backend)
        ConnectionState.ONLINE -> return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = stringResource(R.string.conexion_icono),
            tint = Color.White,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White
        )
    }
}

/**
 * Contador de FPS, latencia media y geometria del frame.
 *
 * Solo se compone si `BuildConfig.DEBUG`. Es la primera version de lo que en F3 se
 * convierte en la pantalla de Diagnostico completa.
 */
@Composable
private fun DebugHud(
    viewModel: ScannerViewModel,
    geometry: State<FrameGeometry?>,
    modifier: Modifier = Modifier
) {
    val stats by viewModel.performance.collectAsStateWithLifecycle()
    val frame = geometry.value ?: return

    Text(
        text = stringResource(
            R.string.scanner_hud_rendimiento,
            String.format(Locale.US, "%.1f", stats.fps),
            stats.averageLatencyMs.toInt(),
            stats.averageInferenceMs.toInt(),
            frame.width,
            frame.height,
            frame.rotationDegrees
        ),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

// ---------------------------------------------------------------------------------------
// Pantalla explicativa del permiso
// ---------------------------------------------------------------------------------------

/**
 * Pantalla que se muestra mientras no hay permiso de camara.
 *
 * @param permanentlyDenied cuando es `true` se agrega, bajo el mismo texto, el aviso y el
 *   boton que lleva a los ajustes del sistema, porque el dialogo del sistema ya no vuelve
 *   a aparecer.
 */
@Composable
private fun CameraPermissionContent(
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PhotoCamera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = stringResource(R.string.permiso_camara_titulo),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp)
            )
            Text(
                text = stringResource(R.string.permiso_camara_explicacion),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp)
            )
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.padding(top = 28.dp)
            ) {
                Text(text = stringResource(R.string.permiso_camara_boton_permitir))
            }

            if (permanentlyDenied) {
                Text(
                    text = stringResource(R.string.permiso_camara_denegado_siempre),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp)
                )
                OutlinedButton(
                    onClick = { context.openApplicationSettings() },
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(text = stringResource(R.string.permiso_camara_boton_ajustes))
                }
            }
        }
    }
}

