package ec.edu.uteq.labscan.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ec.edu.uteq.labscan.R
import ec.edu.uteq.labscan.data.remote.dto.SourceDto
import ec.edu.uteq.labscan.ui.theme.WarningAmber

/** Ancho maximo de una burbuja, en fraccion del ancho disponible. */
private const val BUBBLE_MAX_WIDTH = 0.86f

/**
 * Una burbuja de la conversacion.
 *
 * Tres aspectos distintos y deliberados:
 *
 * - **Usuario**: alineada a la derecha, color primario. Es lo que dijo el estudiante.
 * - **Asistente con contexto**: a la izquierda, color de superficie, con sus fuentes debajo.
 * - **Asistente sin contexto suficiente**: a la izquierda pero en **ambar** y sin fuentes,
 *   como exige docs/CONTRATO_API.md. El color no es decorativo: avisa de que eso no salio de
 *   los manuales del laboratorio y hay que consultar al docente.
 */
@Composable
fun ChatBubble(
    message: ChatMessage,
    isSpeaking: Boolean,
    canSpeak: Boolean,
    onToggleSpeak: () -> Unit
) {
    val isUser = message.author == Author.USER
    val insufficient = !isUser && !message.hasSufficientContext

    val background = when {
        isUser -> MaterialTheme.colorScheme.primary
        insufficient -> WarningAmber
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when {
        isUser -> MaterialTheme.colorScheme.onPrimary
        // Sobre ambar el texto claro no se lee: se fuerza oscuro pase lo que pase con el tema.
        insufficient -> Color.Black
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = background,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.fillMaxWidth(BUBBLE_MAX_WIDTH)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (insufficient) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = foreground,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.chat_sin_contexto_titulo),
                            style = MaterialTheme.typography.labelLarge,
                            color = foreground
                        )
                    }
                }

                // Una respuesta salida de internet tiene que distinguirse de una salida del
                // manual del laboratorio ANTES de leerla, no despues de mirar las fuentes.
                // Quien pregunta es un estudiante de primer semestre delante del equipo.
                if (!isUser && message.fromWeb) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Public,
                            contentDescription = null,
                            tint = foreground,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.chat_desde_web_titulo),
                            style = MaterialTheme.typography.labelLarge,
                            color = foreground
                        )
                    }
                }

                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = foreground
                )

                if (!isUser) {
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = onToggleSpeak,
                            enabled = canSpeak,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isSpeaking) Icons.Filled.Stop
                                else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = stringResource(
                                    if (isSpeaking) R.string.chat_detener_lectura
                                    else R.string.chat_escuchar_respuesta
                                ),
                                tint = foreground,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // Regla 6 de CLAUDE.md: toda respuesta muestra su fuente. Y su contrapartida: cuando
        // el backend admite que no tenia contexto, no hay fuentes que ensenar, asi que la
        // fila no se dibuja en absoluto.
        if (!isUser && message.hasSufficientContext && message.sources.isNotEmpty()) {
            SourceChips(message.sources)
        }
    }
}

/**
 * Fuentes citadas, en una fila de chips pequenos.
 *
 * Se desplaza en horizontal en lugar de envolver: con tres o cuatro fuentes largas, envolver
 * empujaria la conversacion hacia abajo y el estudiante perderia de vista la respuesta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceChips(sources: List<SourceDto>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth(BUBBLE_MAX_WIDTH)
            .horizontalScroll(rememberScrollState())
            .padding(top = 6.dp)
    ) {
        sources.forEach { source ->
            val page = source.page
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        text = if (page != null) {
                            stringResource(R.string.ficha_fuente_pagina, source.title, page)
                        } else {
                            source.title
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                leadingIcon = {
                    // El icono es lo que se ve antes de leer el texto del chip, asi que es
                    // donde tiene que notarse que una fuente es de internet.
                    val esWeb = source.documentId.startsWith("http")
                    Icon(
                        imageVector = if (esWeb) Icons.Filled.Public else Icons.Filled.Description,
                        contentDescription = stringResource(
                            if (esWeb) R.string.chat_icono_fuente_web
                            else R.string.chat_icono_fuente
                        ),
                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                    )
                }
            )
        }
    }
}

/**
 * Tres puntos que laten mientras se espera la respuesta.
 *
 * Con el mock la espera es de unas decimas, pero contra el backend real el RAG puede tardar
 * varios segundos. Sin esto, la pantalla se queda quieta y parece que la pregunta se perdio.
 */
@Composable
fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "escribiendo")

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(3) { index ->
                val alpha by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        // El desfase entre puntos es lo que produce la onda; sin el, los tres
                        // parpadearian a la vez y pareceria un fallo de dibujo.
                        animation = tween(durationMillis = 600, delayMillis = index * 160),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "punto$index"
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                            CircleShape
                        )
                )
            }
        }
    }
}

/**
 * Preguntas sugeridas.
 *
 * Un estudiante de primer semestre frente a un cuadro de texto vacio no sabe que preguntar.
 * Estas cuatro son las que de verdad se hacen en un laboratorio, y tocar una la envia tal
 * cual: es el camino mas corto entre "no se que hacer" y una respuesta con su fuente.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionChips(onSuggestionClick: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.chat_sugerencias_titulo),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatViewModel.SUGGESTIONS.forEach { suggestionRes ->
                val text = stringResource(suggestionRes)
                SuggestionChip(
                    onClick = { onSuggestionClick(text) },
                    label = {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                )
            }
        }
    }
}
