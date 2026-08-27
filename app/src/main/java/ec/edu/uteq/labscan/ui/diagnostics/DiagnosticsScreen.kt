package ec.edu.uteq.labscan.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ec.edu.uteq.labscan.R
import ec.edu.uteq.labscan.appContainer
import ec.edu.uteq.labscan.detection.DetectorStatus
import ec.edu.uteq.labscan.detection.DiagnosticsProvider
import ec.edu.uteq.labscan.detection.ModelDiagnostics
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Pantalla de diagnostico del modelo.
 *
 * Existe para que quien entrena el modelo pueda verificar su `.tflite` **sin programar**:
 * copia los tres archivos a `assets/`, compila, abre esta pantalla y compara con la tabla
 * de docs/INTEGRACION_MODELO.md seccion 4. El boton de copiar deja todo en el portapapeles
 * para pegarlo en un chat.
 *
 * Lee directamente del `AppContainer`, sin ViewModel propio: no tiene estado que gestionar,
 * solo observa dos flujos que ya existen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) { context.appContainer }
    val detectorStatus by container.detectorStatus.collectAsStateWithLifecycle()
    val performance by container.performanceTracker.stats.collectAsStateWithLifecycle()

    // El detector real se describe a si mismo; el de prueba no implementa la interfaz.
    val provider = container.detector as? DiagnosticsProvider
    val diagnostics = provider?.diagnostics?.collectAsStateWithLifecycle()?.value

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.diagnostico_copiado)
    val report = buildReport(context, detectorStatus, diagnostics, performance)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostico_titulo)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.diagnostico_volver)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DetectorSection(detectorStatus)

            if (diagnostics != null) {
                TensorSections(diagnostics)
            } else {
                Section(stringResource(R.string.diagnostico_tensor_entrada)) {
                    Text(
                        text = stringResource(R.string.diagnostico_sin_modelo),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Section(stringResource(R.string.diagnostico_rendimiento)) {
                Text(
                    text = stringResource(
                        R.string.diagnostico_rendimiento_detalle,
                        String.format(Locale.US, "%.1f", performance.fps),
                        performance.averageLatencyMs.toInt(),
                        performance.averageInferenceMs.toInt(),
                        performance.overheadMs.toInt()
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Button(
                onClick = {
                    context.copyToClipboard(report)
                    scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Text(
                    text = stringResource(R.string.diagnostico_copiar),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun DetectorSection(status: DetectorStatus) {
    Section(stringResource(R.string.diagnostico_detector)) {
        when (status) {
            is DetectorStatus.Real -> Text(
                text = stringResource(R.string.diagnostico_detector_real),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )

            is DetectorStatus.Stub -> {
                Text(
                    text = stringResource(R.string.diagnostico_detector_stub),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Text(
                    text = stringResource(R.string.diagnostico_motivo) + ": " +
                        stringResource(status.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                status.detail?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DetectorStatus.Unknown -> Text(
                text = "-",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun TensorSections(diagnostics: ModelDiagnostics) {
    Section(stringResource(R.string.diagnostico_tensor_entrada)) {
        Row(stringResource(R.string.diagnostico_forma), diagnostics.input.shapeText)
        Row(stringResource(R.string.diagnostico_tipo), diagnostics.input.dataType)
        Row(stringResource(R.string.diagnostico_cuantizacion), diagnostics.input.quantizationText)
    }

    Section(stringResource(R.string.diagnostico_tensor_salida)) {
        Row(stringResource(R.string.diagnostico_forma), diagnostics.output.shapeText)
        Row(stringResource(R.string.diagnostico_tipo), diagnostics.output.dataType)
        Row(stringResource(R.string.diagnostico_cuantizacion), diagnostics.output.quantizationText)
        Row(stringResource(R.string.diagnostico_disposicion), diagnostics.layout.name)
        if (diagnostics.layoutOverridden) {
            Text(
                text = stringResource(R.string.diagnostico_disposicion_corregida),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        Row(
            stringResource(R.string.diagnostico_candidatos),
            diagnostics.numCandidates.toString()
        )
    }

    Section(stringResource(R.string.diagnostico_clases)) {
        Text(
            text = stringResource(
                R.string.diagnostico_clases_detalle,
                diagnostics.labelCount,
                diagnostics.inferredClassCount
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        val matches = diagnostics.labelCount == diagnostics.inferredClassCount
        Text(
            text = stringResource(
                if (matches) R.string.diagnostico_clases_ok else R.string.diagnostico_clases_error
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (matches) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }

    Section(stringResource(R.string.diagnostico_rango)) {
        Text(
            text = stringResource(
                R.string.diagnostico_rango_detalle,
                String.format(Locale.US, "%.4f", diagnostics.lastOutputMin),
                String.format(Locale.US, "%.4f", diagnostics.lastOutputMax),
                diagnostics.lastCandidatesOverThreshold
            ),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = stringResource(R.string.diagnostico_rango_aviso),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Section(stringResource(R.string.diagnostico_ejecucion)) {
        Text(
            text = stringResource(
                R.string.diagnostico_ejecucion_detalle,
                diagnostics.threads,
                stringResource(
                    if (diagnostics.usingGpuDelegate) R.string.diagnostico_si
                    else R.string.diagnostico_no
                )
            ),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun Row(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace
    )
}

/**
 * Arma el informe de texto plano que copia el boton.
 *
 * Se genera aparte de la interfaz a proposito: lo que se pega en un chat debe poder leerse
 * sin la app delante, asi que lleva las etiquetas completas y no depende del formato visual.
 * Tambien es lo que se registra en el log al arrancar el detector.
 */
private fun buildReport(
    context: Context,
    status: DetectorStatus,
    diagnostics: ModelDiagnostics?,
    performance: ec.edu.uteq.labscan.di.PerformanceStats
): String = buildString {
    appendLine("=== Diagnostico LabScan UTEQ ===")
    when (status) {
        is DetectorStatus.Real -> {
            appendLine("Detector: TFLite Interpreter")
            appendLine("Modelo: ${status.modelAsset}")
        }

        is DetectorStatus.Stub -> {
            appendLine("Detector: StubDetector (cajas de prueba)")
            appendLine("Motivo: ${context.getString(status.reason)}")
            status.detail?.let { appendLine("Detalle: $it") }
        }

        DetectorStatus.Unknown -> appendLine("Detector: sin inicializar")
    }

    if (diagnostics != null) {
        appendLine()
        appendLine("Entrada: ${diagnostics.input.shapeText} ${diagnostics.input.dataType}")
        appendLine("  ${diagnostics.input.quantizationText}")
        appendLine("Salida:  ${diagnostics.output.shapeText} ${diagnostics.output.dataType}")
        appendLine("  ${diagnostics.output.quantizationText}")
        appendLine("Disposicion: ${diagnostics.layout}")
        if (diagnostics.layoutOverridden) {
            appendLine("  (corregida: model_config.json declaraba la contraria)")
        }
        appendLine("Candidatos: ${diagnostics.numCandidates}")
        appendLine("Clases labels.txt: ${diagnostics.labelCount}")
        appendLine("Clases del tensor: ${diagnostics.inferredClassCount}")
        appendLine("Rango salida ultimo frame: ${diagnostics.lastOutputMin} .. ${diagnostics.lastOutputMax}")
        appendLine("Candidatos sobre el umbral: ${diagnostics.lastCandidatesOverThreshold}")
        appendLine("Hilos: ${diagnostics.threads} · GPU: ${diagnostics.usingGpuDelegate}")
    }

    appendLine()
    appendLine("FPS: ${String.format(Locale.US, "%.1f", performance.fps)}")
    appendLine("Latencia media total: ${performance.averageLatencyMs} ms (${performance.sampleCount} frames)")
    appendLine("  de la cual inferencia: ${performance.averageInferenceMs} ms")
    appendLine("  preparacion del frame: ${performance.overheadMs} ms")
}

private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostico LabScan", text))
}
