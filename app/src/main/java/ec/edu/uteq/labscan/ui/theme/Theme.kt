package ec.edu.uteq.labscan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Esquema oscuro. Es el que usa la app en la practica.
 *
 * `primary` es el verde institucional exacto (#009B4C). Para texto fino y bordes sobre
 * el fondo oscuro se usa [UteqGreenLight], que si alcanza el contraste necesario.
 */
private val LabScanDarkColors = darkColorScheme(
    primary = UteqGreen,
    onPrimary = OnDark,
    primaryContainer = UteqGreenDark,
    onPrimaryContainer = OnDark,
    secondary = UteqGreenLight,
    onSecondary = ScannerBackground,
    secondaryContainer = ScannerSurfaceVariant,
    onSecondaryContainer = OnDark,
    tertiary = WarningAmber,
    onTertiary = ScannerBackground,
    background = ScannerBackground,
    onBackground = OnDark,
    surface = ScannerSurface,
    onSurface = OnDark,
    surfaceVariant = ScannerSurfaceVariant,
    onSurfaceVariant = OnDarkMuted,
    outline = OnDarkMuted,
    error = ErrorRed,
    onError = ScannerBackground
)

/**
 * Esquema claro. Existe solo por completitud de Material 3; la app no lo activa sola.
 * Ver [LabScanTheme].
 */
private val LabScanLightColors = lightColorScheme(
    primary = UteqGreenDark,
    onPrimary = OnDark,
    secondary = UteqGreen,
    tertiary = WarningAmber
)

/**
 * Tema raiz de la app.
 *
 * [darkTheme] vale `true` siempre por defecto, a proposito: la pantalla principal es una
 * vista de camara a pantalla completa y un fondo claro deslumbra al usuario y resta
 * contraste a los cuadros de deteccion. Se deja el parametro para poder forzar el tema
 * claro en las previsualizaciones y en las capturas del informe.
 *
 * @param darkTheme `true` fuerza la paleta oscura. Pasar [isSystemInDarkTheme] solo si en
 *   alguna fase futura se decide respetar el ajuste del sistema.
 */
@Composable
fun LabScanTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) LabScanDarkColors else LabScanLightColors,
        typography = LabScanTypography,
        content = content
    )
}
