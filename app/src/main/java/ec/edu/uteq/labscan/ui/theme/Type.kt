package ec.edu.uteq.labscan.ui.theme

import androidx.compose.material3.Typography

/**
 * Tipografia de la app: la escala por defecto de Material 3 (Roboto del sistema).
 *
 * No se empaqueta ninguna fuente propia a proposito, para no inflar el APK: el peso
 * disponible se reserva para el modelo `.tflite` (ver docs/PLAN_FASES.md, seccion 3).
 * Si en F7 hace falta una fuente institucional, se cambia solo este archivo.
 */
val LabScanTypography = Typography()
