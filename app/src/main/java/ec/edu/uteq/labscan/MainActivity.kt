package ec.edu.uteq.labscan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import ec.edu.uteq.labscan.ui.chat.ChatScreen
import ec.edu.uteq.labscan.ui.chat.ChatViewModel
import ec.edu.uteq.labscan.ui.voice.VoiceCallScreen
import ec.edu.uteq.labscan.ui.diagnostics.DiagnosticsScreen
import ec.edu.uteq.labscan.ui.scanner.ScannerScreen
import ec.edu.uteq.labscan.ui.settings.SettingsScreen
import ec.edu.uteq.labscan.ui.theme.LabScanTheme

/**
 * Rutas de navegacion de la app. Se declaran como constantes para que no haya cadenas
 * sueltas repartidas por el codigo.
 */
object Routes {
    /** Pantalla principal: camara en vivo + overlay de detecciones. Se implementa en F1-F2. */
    const val SCANNER = "scanner"

    /** Forma del tensor, cuantizacion y FPS, para verificar el modelo sin programar. */
    const val DIAGNOSTICS = "diagnostics"

    /** Ajustes del estudiante. Hoy solo la lectura automatica de las respuestas. */
    const val SETTINGS = "settings"

    /** Nombre del argumento opcional de [CHAT]. */
    const val ARG_CLASS_ID = "classId"

    /**
     * Chat con el asistente RAG.
     *
     * El `classId` es un argumento **opcional de consulta** y no parte de la ruta, porque el
     * contrato admite `classId` nulo: se puede preguntar sin haber tocado ningun equipo. Con
     * un argumento de ruta habria que inventarse un valor centinela para ese caso.
     */
    const val CHAT = "chat?$ARG_CLASS_ID={$ARG_CLASS_ID}"

    /** Construye la ruta concreta del chat para un equipo, o sin equipo si es `null`. */
    fun chat(classId: String? = null): String =
        if (classId.isNullOrBlank()) "chat" else "chat?$ARG_CLASS_ID=$classId"

    /**
     * Conversacion por voz manos libres (F8). Mismo argumento opcional que [CHAT].
     */
    const val VOICE = "voz?$ARG_CLASS_ID={$ARG_CLASS_ID}"

    /** Construye la ruta concreta de la conversacion por voz. */
    fun voice(classId: String? = null): String =
        if (classId.isNullOrBlank()) "voz" else "voz?$ARG_CLASS_ID=$classId"

    /**
     * Subgrafo que contiene el chat escrito y la conversacion por voz.
     *
     * Existe **solo** para darles un ambito comun de ViewModel. El `ChatViewModel` se resuelve
     * contra la entrada de este grafo, asi que las dos pantallas reciben exactamente la misma
     * instancia y comparten el historial: pasar de voz a texto no es cambiar de conversacion,
     * es cambiar de vista. Sin este subgrafo, cada `composable` tendria su propio ambito y al
     * colgar la conversacion hablada aparecerian dos historiales distintos.
     *
     * Al salir de las dos pantallas el grafo se desapila y el ViewModel se limpia, que es lo
     * que se quiere: la conversacion no debe sobrevivir a volver al escaner.
     */
    const val CONVERSATION = "conversacion"

    // La ficha tecnica no tiene ruta propia: es un ModalBottomSheet dentro del scanner, para
    // que la caja resaltada siga viendose detras mientras se lee (F4).
}

/**
 * Unica Activity de la app. Hospeda todo Compose y la navegacion.
 *
 * Es `ComponentActivity` y no `AppCompatActivity` porque el proyecto no usa vistas XML ni
 * AppCompat. La orientacion es libre desde F2: `BoxMapper` resuelve la rotacion, y girar el
 * telefono es uno de los tres casos que hay que verificar (CLAUDE.md, seccion critica).
 * Al girar, la Activity se recrea y CameraX se reengancha; el estado sobrevive en el
 * ScannerViewModel.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // ANTES de super.onCreate: es lo que instala la pantalla de arranque. Llamarlo
        // despues no tiene efecto y no avisa de nada.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // La vista previa de la camara ocupa toda la pantalla, incluidas las barras del sistema.
        enableEdgeToEdge()
        setContent {
            LabScanTheme {
                LabScanApp()
            }
        }
    }
}

/**
 * Grafo de navegacion. Hoy tiene una sola ruta; las demas se agregan en sus fases.
 */
@Composable
fun LabScanApp(navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = Routes.SCANNER
    ) {
        composable(Routes.SCANNER) {
            ScannerScreen(
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenChat = { classId -> navController.navigate(Routes.chat(classId)) },
                onOpenVoice = { classId -> navController.navigate(Routes.voice(classId)) }
            )
        }
        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(onBack = { navController.volverAtras() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.volverAtras() },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) }
            )
        }
        // Chat escrito y conversacion por voz viven en el mismo subgrafo para compartir el
        // ChatViewModel, y con el, el historial. Ver Routes.CONVERSATION.
        navigation(startDestination = Routes.CHAT, route = Routes.CONVERSATION) {
            composable(
                route = Routes.CHAT,
                arguments = listOf(optionalClassId())
            ) { entry ->
                ChatScreen(
                    classId = entry.arguments?.getString(Routes.ARG_CLASS_ID),
                    chatViewModel = navController.conversationViewModel(entry),
                    onBack = { navController.volverAtras() },
                    onOpenVoice = { classId ->
                        navController.navigate(Routes.voice(classId))
                    }
                )
            }
            composable(
                route = Routes.VOICE,
                arguments = listOf(optionalClassId())
            ) { entry ->
                VoiceCallScreen(
                    classId = entry.arguments?.getString(Routes.ARG_CLASS_ID),
                    chatViewModel = navController.conversationViewModel(entry),
                    // Ver la transcripcion saca la pantalla de voz de la pila en lugar de
                    // apilar el chat encima. Es lo coherente con lo que pasa de todos modos:
                    // al dejar de estar compuesta, la pantalla de voz cierra el microfono y
                    // la llamada termina. Dejarla debajo permitiria "volver" a una llamada
                    // que ya no existe.
                    onOpenTranscript = {
                        navController.navigate(
                            Routes.chat(entry.arguments?.getString(Routes.ARG_CLASS_ID))
                        ) {
                            popUpTo(Routes.VOICE) { inclusive = true }
                        }
                    },
                    onHangUp = { navController.volverAtras() }
                )
            }
        }
    }
}

/**
 * Vuelve atras, y si no hay nada a lo que volver, va al escaner.
 *
 * `popBackStack()` a secas devuelve `false` y **no hace nada** cuando la pila esta vacia. Lo
 * que se ve entonces es que la flecha de atras no responde, o que el gesto del sistema cierra
 * la app desde una pantalla interior. Reportado el 2026-09-10 desde el chat.
 *
 * La pila puede quedarse vacia por caminos que no son evidentes: el sistema mata el proceso
 * en segundo plano y lo restaura en la pantalla en la que estaba, o una navegacion con
 * `popUpTo` inclusivo se lleva por delante lo que habia debajo, como hace la transcripcion de
 * la llamada de voz.
 *
 * Desde el escaner, que es la pantalla inicial, atras SI cierra la app, que es lo que Android
 * espera.
 */
private fun NavHostController.volverAtras() {
    if (popBackStack()) return

    // `popUpTo(graph.id) { inclusive = true }` era lo que habia aqui y es una forma
    // peligrosa: vacia el grafo RAIZ, incluida la propia entrada que lo sostiene, y deja al
    // controlador sin nada. Apuntar al escaner es equivalente para lo que se quiere, que es
    // "quedate solo con el escaner", y no puede dejar la pila vacia.
    navigate(Routes.SCANNER) {
        popUpTo(Routes.SCANNER) { inclusive = true }
        launchSingleTop = true
    }
}

/** El `classId` es opcional en las dos pantallas de conversacion. */
private fun optionalClassId() = navArgument(Routes.ARG_CLASS_ID) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

/**
 * El [ChatViewModel] del subgrafo de conversacion.
 *
 * Se resuelve contra la entrada de [Routes.CONVERSATION] y no contra la pantalla actual: es
 * justo lo que hace que el chat escrito y la conversacion por voz compartan instancia, y por
 * tanto historial.
 */
@Composable
private fun NavHostController.conversationViewModel(entry: NavBackStackEntry): ChatViewModel {
    val context = LocalContext.current
    val container = remember(context) { context.appContainer }

    // Se recuerda contra la entrada de ESTA pantalla, no contra `currentBackStackEntry`.
    //
    // Con `currentBackStackEntry` la app se cerraba al colgar la llamada de voz y al volver
    // atras desde el chat, que eran el mismo fallo visto dos veces. Al desapilar la ultima
    // pantalla del subgrafo, el subgrafo se desapila con ella; `currentBackStackEntry` cambia,
    // el `remember` se reevalua, y `getBackStackEntry` lanza IllegalArgumentException porque
    // la ruta ya no esta en la pila. Se ve como "se cerro la aplicacion", no como un error de
    // navegacion, y por eso costo encontrarlo.
    //
    // La entrada propia de la pantalla es estable mientras la pantalla existe, asi que la
    // busqueda se hace una sola vez y no se repite durante el desapilado.
    val parentEntry = remember(entry) { getBackStackEntry(Routes.CONVERSATION) }

    return viewModel(
        viewModelStoreOwner = parentEntry,
        factory = ChatViewModel.factory(container)
    )
}
