package ec.edu.uteq.labscan.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta de LabScan UTEQ.
 *
 * La app se usa con la camara apuntando a un equipo, asi que el fondo es oscuro siempre:
 * un fondo claro deslumbra en el laboratorio y compite con la imagen de la camara.
 * El acento es el verde institucional de la UTEQ.
 */

/** Verde institucional UTEQ. Acento principal de la app. */
val UteqGreen = Color(0xFF009B4C)

/** Variante clara del verde. Para texto y bordes finos sobre fondo oscuro, donde
 *  [UteqGreen] no alcanza la relacion de contraste 4.5:1. */
val UteqGreenLight = Color(0xFF3ED184)

/** Variante oscura. Contenedores y estados presionados. */
val UteqGreenDark = Color(0xFF00602F)

/** Fondo de la app. Casi negro, no negro puro, para que las sombras sigan siendo visibles. */
val ScannerBackground = Color(0xFF0E1013)

/** Superficies elevadas: barras, tarjetas, bottom sheet de la ficha tecnica. */
val ScannerSurface = Color(0xFF1A1D21)

/** Superficie secundaria: campos de texto, chips, separadores. */
val ScannerSurfaceVariant = Color(0xFF272B30)

/** Texto principal sobre fondo oscuro. */
val OnDark = Color(0xFFECEFF1)

/** Texto secundario y etiquetas. */
val OnDarkMuted = Color(0xFFB0B6BD)

/**
 * Ambar de advertencia. Lo exige docs/CONTRATO_API.md: cuando el backend responde
 * `hasSufficientContext: false`, la respuesta se muestra con este fondo (F6).
 */
val WarningAmber = Color(0xFFFFB300)

/**
 * Ambar de seleccion. Es el color de la caja que el estudiante toco: contrasta con el
 * verde de las demas detecciones sin confundirse con el ambar de advertencia del chat.
 */
val SelectionAmber = Color(0xFFFFC64D)

/** Rojo de error sobre fondo oscuro. */
val ErrorRed = Color(0xFFCF6679)
