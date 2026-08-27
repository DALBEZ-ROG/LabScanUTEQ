package ec.edu.uteq.labscan.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Los tres estados en los que puede estar un permiso desde el punto de vista de la UI. */
enum class PermissionStatus { GRANTED, DENIED, PERMANENTLY_DENIED }

/** Estado de un permiso mas la accion para volver a pedirlo. */
class RuntimePermission(
    val status: PermissionStatus,
    val request: () -> Unit
) {
    val isGranted: Boolean get() = status == PermissionStatus.GRANTED
}

/**
 * Sigue el estado de un permiso en tiempo de ejecucion.
 *
 * Nacio en F1 dentro de `ScannerScreen` para la camara y se extrajo aqui en F6, cuando el
 * microfono necesito exactamente el mismo comportamiento. Es una sola implementacion a
 * proposito: el permiso de audio se comporta igual que el de camara, y tener dos copias
 * garantizaria que una de las dos se quedara sin arreglar.
 *
 * Dos detalles que suelen hacerse mal:
 *
 * 1. `shouldShowRequestPermissionRationale` devuelve `false` tanto cuando nunca se pregunto
 *    como cuando el usuario denego para siempre. Por eso solo se clasifica como denegado
 *    permanente **despues** de haber preguntado al menos una vez.
 * 2. El usuario puede conceder el permiso desde los ajustes del sistema y volver. Como en ese
 *    camino no hay callback, se vuelve a consultar en cada `ON_RESUME`.
 *
 * @param permission constante de `android.Manifest.permission`.
 * @param requestOnFirstAppearance si se pide solo al entrar. La camara lo quiere, porque sin
 *   ella la pantalla no sirve de nada; el microfono no, porque el chat funciona escribiendo y
 *   asaltar con un dialogo al abrirlo seria grosero.
 */
@Composable
fun rememberRuntimePermission(
    permission: String,
    requestOnFirstAppearance: Boolean = true
): RuntimePermission {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // rememberSaveable: sin esto, un cambio de configuracion o la muerte del proceso borrarian
    // el hecho de que ya preguntamos, y la pantalla volveria a ofrecer un boton que el sistema
    // ya no atiende.
    var alreadyAsked by rememberSaveable(permission) { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable(permission) { mutableStateOf(false) }
    var granted by remember(permission) { mutableStateOf(context.isPermissionGranted(permission)) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        alreadyAsked = true
        granted = isGranted
        permanentlyDenied = !isGranted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    LaunchedEffect(permission, requestOnFirstAppearance) {
        if (requestOnFirstAppearance && !granted && !alreadyAsked) {
            alreadyAsked = true
            launcher.launch(permission)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, permission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val nowGranted = context.isPermissionGranted(permission)
                granted = nowGranted
                if (nowGranted) permanentlyDenied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val status = when {
        granted -> PermissionStatus.GRANTED
        permanentlyDenied -> PermissionStatus.PERMANENTLY_DENIED
        else -> PermissionStatus.DENIED
    }

    return remember(status, permission) {
        RuntimePermission(status) {
            alreadyAsked = true
            launcher.launch(permission)
        }
    }
}

fun Context.isPermissionGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Desenvuelve la Activity de una cadena de `ContextWrapper`. Devuelve null en previews. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Abre la ficha de la app en los ajustes del sistema, que es el unico sitio donde se puede
 * reactivar un permiso denegado para siempre.
 */
fun Context.openApplicationSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}
