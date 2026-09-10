package ec.edu.uteq.labscan.ui.sheet

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec.edu.uteq.labscan.R
import ec.edu.uteq.labscan.data.remote.dto.EquipmentDto
import ec.edu.uteq.labscan.data.remote.dto.SourceDto
import ec.edu.uteq.labscan.ui.scanner.EquipmentSheetUiState
import kotlin.math.roundToInt

/** Altura minima de toque recomendada por Material. Nada interactivo baja de aqui. */
private val MIN_TOUCH_TARGET = 48.dp

/**
 * Ficha tecnica del equipo detectado.
 *
 * Los datos salen de `assets/catalog.json` y tienen exactamente los campos de
 * `GET /api/equipment/{classId}` (docs/CONTRATO_API.md). Cuando F5 conecte el backend, esta
 * pantalla no cambia: cambia de donde viene el [EquipmentDto].
 *
 * Toda seccion cuyo texto o lista venga vacia se oculta sola, porque el contrato permite
 * campos vacios y una seccion con un titulo y nada debajo es peor que no mostrarla.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EquipmentSheet(
    state: EquipmentSheetUiState,
    onDismiss: () -> Unit,
    isSpeaking: Boolean = false,
    canSpeak: Boolean = false,
    onToggleSpeak: () -> Unit = {},
    onAskAssistant: () -> Unit = {},
    onTalkToAssistant: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(
        // Se abre a media altura a proposito: la vista de la camara sigue viendose detras,
        // con la caja resaltada, y el estudiante puede relacionar la ficha con el equipo.
        skipPartiallyExpanded = false
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Sin esto la ficha se corta por donde termine la pantalla y no hay forma
                // de llegar a lo de abajo: riesgos, fuentes y los botones de accion quedan
                // fuera de alcance. Comprobado en un SM-A566E el 2026-08-27.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SheetHeader(state)

            // El orden importa: primero se dice que no hay ficha, y solo despues de donde
            // salio lo poco que hay. Al reves parece que la conexion es el problema.
            if (!state.fromCatalog) {
                UnavailableNotice()
            }

            if (state.fromCache) {
                OfflineNotice()
            }

            val equipment = state.equipment

            if (equipment.shortDescription.isNotBlank()) {
                Text(
                    text = equipment.shortDescription,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (equipment.function.isNotBlank()) {
                CollapsibleSection(stringResource(R.string.ficha_funcion)) {
                    Text(
                        text = equipment.function,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (equipment.components.isNotEmpty()) {
                CollapsibleSection(stringResource(R.string.ficha_componentes)) {
                    BulletList(equipment.components)
                }
            }

            if (equipment.basicProcedure.isNotEmpty()) {
                CollapsibleSection(stringResource(R.string.ficha_procedimiento)) {
                    NumberedList(equipment.basicProcedure)
                }
            }

            if (equipment.ppe.isNotEmpty()) {
                CollapsibleSection(stringResource(R.string.ficha_epp)) {
                    PpeChips(equipment.ppe)
                }
            }

            if (equipment.risks.isNotEmpty()) {
                CollapsibleSection(stringResource(R.string.ficha_riesgos)) {
                    RiskList(equipment.risks)
                }
            }

            if (equipment.relatedPractices.isNotEmpty()) {
                CollapsibleSection(stringResource(R.string.ficha_practicas)) {
                    BulletList(equipment.relatedPractices)
                }
            }

            if (equipment.sources.isNotEmpty()) {
                SourcesFooter(equipment.sources)
            }

            SheetActions(
                isSpeaking = isSpeaking,
                canSpeak = canSpeak,
                onToggleSpeak = onToggleSpeak,
                onAskAssistant = onAskAssistant,
                onTalkToAssistant = onTalkToAssistant
            )
        }
    }
}

@Composable
private fun SheetHeader(state: EquipmentSheetUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = state.equipment.displayName,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false)
        )
        SuggestionChip(
            onClick = {},
            enabled = false,
            label = {
                Text(
                    text = stringResource(
                        R.string.ficha_confianza,
                        (state.score * 100f).roundToInt()
                    )
                )
            },
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun UnavailableNotice() {
    Text(
        text = stringResource(R.string.ficha_no_disponible),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    )
}

/**
 * Aviso de que la ficha salio del catalogo guardado en el APK y no del backend.
 *
 * Discreto a proposito: el contenido es correcto y util, solo puede estar mas desactualizado
 * que el del servidor. No es un error, es una procedencia.
 */
@Composable
private fun OfflineNotice() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(end = 6.dp)
                .size(16.dp)
        )
        Text(
            text = stringResource(R.string.ficha_sin_conexion),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Seccion que se puede plegar.
 *
 * Empieza desplegada: el estudiante entra a leer, no a navegar. Plegarla sirve para llegar
 * antes a las secciones de abajo en equipos con fichas largas.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    content: @Composable () -> Unit
) {
    var expanded by remember(title) { mutableStateOf(true) }

    Column(modifier = Modifier.animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 48 dp de alto: es toda la fila la que responde al toque, no solo el icono.
                .heightIn(min = MIN_TOUCH_TARGET)
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.ficha_contraer else R.string.ficha_expandir
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun BulletList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Row {
                Text(
                    text = "\u2022",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    // El punto es decorativo: el lector de pantalla ya lee el texto.
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clearAndSetSemantics {}
                )
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NumberedList(steps: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { index, step ->
            Row {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clearAndSetSemantics {}
                )
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PpeChips(ppe: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ppe.forEach { item ->
            AssistChip(
                onClick = {},
                label = { Text(item) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.HealthAndSafety,
                        contentDescription = stringResource(R.string.ficha_icono_epp),
                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                    )
                }
            )
        }
    }
}

/**
 * Riesgos, sobre fondo de advertencia.
 *
 * Es la unica seccion que se resalta con color: en un laboratorio, saltarse un riesgo tiene
 * consecuencias distintas a saltarse la lista de componentes.
 */
@Composable
private fun RiskList(risks: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        risks.forEach { risk ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = stringResource(R.string.ficha_icono_riesgo),
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(20.dp)
                )
                Text(
                    text = risk,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * Fuentes consultadas.
 *
 * CLAUDE.md, regla 6: toda informacion muestra de donde sale. La ficha tecnica no es una
 * excepcion, aunque venga del catalogo local.
 */
@Composable
private fun SourcesFooter(sources: List<SourceDto>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.ficha_fuentes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            sources.forEach { source ->
                // Una fuente de internet no puede parecerse a un manual del laboratorio. El
                // icono es lo primero que se ve, antes de leer el titulo.
                val esWeb = source.documentId.startsWith("http")
                val page = source.page

                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = if (esWeb) Icons.Filled.Public else Icons.Filled.Description,
                        contentDescription = stringResource(
                            if (esWeb) R.string.ficha_fuente_web else R.string.ficha_fuente_manual
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 10.dp, top = 2.dp)
                            .size(16.dp)
                    )
                    Column {
                        Text(
                            text = source.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        // La pagina va en su propia linea y mas pequena. Antes se pegaba al
                        // titulo, y con el mismo manual citado once veces la pantalla eran
                        // once lineas casi identicas que solo cambiaban en el numero final.
                        if (page != null) {
                            Text(
                                text = stringResource(R.string.ficha_fuente_solo_pagina, page),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Botones de accion. Los dos funcionan desde F6.
 *
 * "Escuchar" lee la descripcion corta y el procedimiento basico, que es lo que sirve con las
 * manos ocupadas. **No lee las fuentes ni los riesgos**: las fuentes porque una lista de
 * titulos y paginas dicha en voz alta no ayuda, y los riesgos porque merecen leerse mirando,
 * no de fondo mientras se manipula el equipo.
 *
 * "Preguntar al asistente" abre el chat con este `classId` ya en contexto.
 *
 * @param canSpeak `false` si el dispositivo no tiene motor de voz. El boton se deshabilita
 *   en vez de no hacer nada al pulsarlo.
 */
@Composable
private fun SheetActions(
    isSpeaking: Boolean,
    canSpeak: Boolean,
    onToggleSpeak: () -> Unit,
    onAskAssistant: () -> Unit,
    onTalkToAssistant: () -> Unit
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onToggleSpeak,
                enabled = canSpeak,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MIN_TOUCH_TARGET)
            ) {
                Icon(
                    imageVector = if (isSpeaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(
                        if (isSpeaking) R.string.ficha_detener else R.string.ficha_escuchar
                    ),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Button(
                onClick = onAskAssistant,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MIN_TOUCH_TARGET)
            ) {
                Icon(
                    imageVector = Icons.Filled.QuestionAnswer,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.ficha_preguntar),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        // "Hablar" va en su propia fila y a todo el ancho: es la via principal para el
        // estudiante con guantes, y compartir fila con los otros dos la dejaria del tamano de
        // un boton cualquiera.
        Button(
            onClick = onTalkToAssistant,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MIN_TOUCH_TARGET)
        ) {
            Icon(
                imageVector = Icons.Filled.RecordVoiceOver,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(R.string.ficha_hablar),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (!canSpeak) {
            Text(
                text = stringResource(R.string.ficha_sin_voz),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
