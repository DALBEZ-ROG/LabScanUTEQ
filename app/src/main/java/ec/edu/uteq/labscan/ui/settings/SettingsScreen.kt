package ec.edu.uteq.labscan.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ec.edu.uteq.labscan.BuildConfig
import ec.edu.uteq.labscan.R
import ec.edu.uteq.labscan.appContainer
import ec.edu.uteq.labscan.data.local.SettingsStore
import ec.edu.uteq.labscan.data.remote.isValidBackendUrl
import java.util.Locale
import kotlinx.coroutines.launch

/** Altura minima de toque recomendada por Material. Nada interactivo baja de aqui. */
private val MIN_TOUCH_TARGET = 48.dp

/**
 * Ajustes de la app.
 *
 * Existe como pantalla propia porque casi todo lo que hay aqui cambia el comportamiento de la
 * app entera, y el estudiante tiene que poder encontrarlo: en un laboratorio compartido, un
 * telefono que empieza a hablar solo puede ser justo lo que no se quiere.
 *
 * Lo que se escribe aqui llega al detector y al cliente HTTP a traves de
 * `AppContainer.observeSettings`, sin reconstruir ninguno de los dos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit = {}
) {
    val context = LocalContext.current
    val container = remember(context) { context.appContainer }
    val settings = remember(container) { container.settingsStore }
    val scope = rememberCoroutineScope()

    val autoRead by settings.autoReadAnswers.collectAsStateWithLifecycle(initialValue = true)
    val cameraBack by settings.defaultCameraBack.collectAsStateWithLifecycle(initialValue = true)
    val storedThreshold by settings
        .confidenceThreshold(container.modelConfig.confidenceThreshold)
        .collectAsStateWithLifecycle(initialValue = container.modelConfig.confidenceThreshold)
    val storedUrl by settings.backendUrl.collectAsStateWithLifecycle(initialValue = "")

    // Direccion anunciada por el backend en la red local. Es informativa: quien manda sigue
    // siendo lo escrito abajo. Ver BaseUrlInterceptor.
    val urlEnRed by container.serverDiscovery.serverUrl
        .collectAsStateWithLifecycle(initialValue = null)

    // El deslizador necesita estado local: arrastrarlo emite decenas de valores por segundo y
    // escribir cada uno en DataStore seria absurdo. Se guarda al soltar.
    var threshold by remember { mutableStateOf(storedThreshold) }
    LaunchedEffect(storedThreshold) { threshold = storedThreshold }

    var urlDraft by remember { mutableStateOf(storedUrl) }
    LaunchedEffect(storedUrl) { urlDraft = storedUrl }
    val urlIsValid = isValidBackendUrl(urlDraft)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ajustes_titulo)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ajustes_volver)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            SectionTitle(stringResource(R.string.ajustes_seccion_deteccion))

            SettingBlock(
                title = stringResource(R.string.ajustes_umbral),
                description = stringResource(
                    R.string.ajustes_umbral_detalle,
                    String.format(Locale.US, "%.2f", threshold)
                )
            ) {
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    // Se persiste al soltar, no en cada pixel del arrastre.
                    onValueChangeFinished = {
                        scope.launch { settings.setConfidenceThreshold(threshold) }
                    },
                    valueRange = SettingsStore.MIN_THRESHOLD..SettingsStore.MAX_THRESHOLD,
                    modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET)
                )
            }

            SettingBlock(
                title = stringResource(R.string.ajustes_camara),
                description = stringResource(R.string.ajustes_camara_detalle)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = cameraBack,
                        onClick = { scope.launch { settings.setDefaultCameraBack(true) } },
                        label = { Text(stringResource(R.string.ajustes_camara_trasera)) }
                    )
                    FilterChip(
                        selected = !cameraBack,
                        onClick = { scope.launch { settings.setDefaultCameraBack(false) } },
                        label = { Text(stringResource(R.string.ajustes_camara_frontal)) }
                    )
                }
            }

            HorizontalDivider()
            SectionTitle(stringResource(R.string.ajustes_seccion_asistente))

            SwitchRow(
                title = stringResource(R.string.ajustes_lectura_automatica),
                description = stringResource(R.string.ajustes_lectura_automatica_detalle),
                checked = autoRead,
                onCheckedChange = { enabled ->
                    scope.launch { settings.setAutoReadAnswers(enabled) }
                }
            )

            SettingBlock(
                title = stringResource(R.string.ajustes_backend),
                description = stringResource(R.string.ajustes_backend_detalle, BuildConfig.BASE_URL)
            ) {
                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !urlIsValid,
                    placeholder = { Text(BuildConfig.BASE_URL) },
                    supportingText = {
                        Text(
                            text = stringResource(
                                if (urlIsValid) R.string.ajustes_backend_ayuda
                                else R.string.ajustes_backend_invalida
                            )
                        )
                    }
                )
                // Se guarda al perder el foco no: al escribir una URL valida. Guardar en cada
                // pulsacion es barato y evita el boton de "aplicar", que se olvida de pulsar.
                LaunchedEffect(urlDraft, urlIsValid) {
                    if (urlIsValid && urlDraft != storedUrl) {
                        settings.setBackendUrl(urlDraft)
                    }
                }

                // Que se vea si el descubrimiento automatico esta encontrando algo. Sin esto,
                // cuando la app va al servidor equivocado no hay forma de saber si es que no
                // encontro ninguno o que encontro uno y lo esta ignorando.
                val encontrado = urlEnRed
                Spacer(Modifier.height(8.dp))
                when {
                    encontrado == null -> Text(
                        text = stringResource(R.string.ajustes_backend_buscado),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    urlDraft.isBlank() -> Text(
                        text = stringResource(R.string.ajustes_backend_encontrado, encontrado),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    else -> Column {
                        Text(
                            text = stringResource(
                                R.string.ajustes_backend_encontrado_ignorado,
                                encontrado
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { urlDraft = "" }) {
                            Text(stringResource(R.string.ajustes_backend_usar_encontrado))
                        }
                    }
                }
            }

            HorizontalDivider()
            SectionTitle(stringResource(R.string.ajustes_seccion_avanzado))

            ListItem(
                headlineContent = { Text(stringResource(R.string.diagnostico_titulo)) },
                supportingContent = { Text(stringResource(R.string.ajustes_diagnostico_detalle)) },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.Insights,
                        contentDescription = null
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null
                    )
                },
                modifier = Modifier
                    .heightIn(min = MIN_TOUCH_TARGET)
                    // ListItem no acepta onClick: el toque va en el modificador.
                    .clickable(onClick = onOpenDiagnostics)
            )

            HorizontalDivider()
            AboutSection()
        }
    }
}

/**
 * "Acerca de": quien hizo esto, para quien y donde.
 *
 * Es un requisito de la actividad academica, no un adorno: el entregable tiene que decir a que
 * asignatura y a que laboratorio pertenece.
 */
@Composable
private fun AboutSection() {
    Column(modifier = Modifier.padding(vertical = 16.dp)) {
        Text(
            text = stringResource(R.string.acerca_titulo),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.acerca_app),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        AboutLine(stringResource(R.string.acerca_universidad))
        AboutLine(stringResource(R.string.acerca_laboratorio))
        AboutLine(stringResource(R.string.acerca_integrantes))
        AboutLine(
            stringResource(
                R.string.acerca_version,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE
            )
        )
    }
}

@Composable
private fun AboutLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

/** Titulo, explicacion y el control debajo. Es el patron de casi todos los ajustes de aqui. */
@Composable
private fun SettingBlock(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
        )
        content()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH_TARGET)
            .padding(vertical = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}
