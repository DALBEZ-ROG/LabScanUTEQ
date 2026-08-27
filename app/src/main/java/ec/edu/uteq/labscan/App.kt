package ec.edu.uteq.labscan

import android.app.Application
import android.content.Context
import ec.edu.uteq.labscan.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Clase [Application] de LabScan UTEQ.
 *
 * Su unica responsabilidad es crear el [AppContainer], que es el grafo de dependencias
 * manual del proyecto. No hace trabajo pesado en [onCreate]: cargar el modelo o abrir la
 * camara aqui retrasaria el arranque en frio. Esas dependencias se construyen de forma
 * perezosa dentro del contenedor.
 */
class App : Application() {

    /** Grafo de dependencias de la app. Valido desde [onCreate] hasta que muere el proceso. */
    lateinit var container: AppContainer
        private set

    /**
     * Vive lo que vive el proceso, igual que el contenedor. No se cancela en ningun sitio a
     * proposito: si se cancelara, los ajustes dejarian de aplicarse en caliente.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Empieza a obedecer los ajustes guardados: umbral de confianza y URL del backend.
        container.observeSettings(appScope)
    }

    companion object {
        /** Etiqueta unica de logs del proyecto. Se filtra con `adb logcat -s LabScan`. */
        const val LOG_TAG = "LabScan"
    }
}

/**
 * Atajo para llegar al [AppContainer] desde cualquier `Context`.
 *
 * Lo usaran las factorias de ViewModel a partir de F2, que reciben un `Context` pero no
 * pueden recibir el contenedor por constructor.
 */
val Context.appContainer: AppContainer
    get() = (applicationContext as App).container
