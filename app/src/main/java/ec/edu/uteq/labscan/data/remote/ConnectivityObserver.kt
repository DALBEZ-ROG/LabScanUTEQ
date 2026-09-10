package ec.edu.uteq.labscan.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import ec.edu.uteq.labscan.App
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Estado de la red del dispositivo.
 *
 * Sirve para dos cosas distintas:
 *
 * 1. [isOnline], que la interfaz observa para mostrar el aviso de "sin conexion";
 * 2. [isOnlineNow], que [RagRepository] consulta antes de cada peticion para no gastar
 *    quince segundos de timeout cuando ya se sabe que no hay red.
 *
 ### Por que NO se exige `NET_CAPABILITY_VALIDATED`
 *
 * Se exigia, y el 2026-09-10 dejo la app inutil en una demostracion: el telefono estaba en
 * la MISMA wifi que el portatil del backend, el descubrimiento mDNS ya habia encontrado el
 * servidor y lo mostraba en Ajustes, y aun asi cada pregunta moria en "No hay conexion a
 * internet" sin llegar a salir del telefono.
 *
 * `VALIDATED` significa que **Android** consiguio alcanzar sus propios servidores de
 * comprobacion. Eso es otra cosa distinta de si se puede alcanzar el backend:
 *
 * - una wifi de laboratorio sin salida a internet no valida, y el backend esta en esa
 *   misma wifi, a un salto de distancia;
 * - una red universitaria puede tardar en validar, o no validar nunca, y funcionar;
 * - el aviso resultante ademas MIENTE, porque dice "no hay internet" cuando lo que pasa es
 *   que Android no ha podido confirmarlo.
 *
 * Asi que aqui solo se comprueba lo unico que hace imposible cualquier peticion: que no haya
 * ninguna red activa. Airplane mode, wifi apagado y sin datos. Todo lo demas se intenta, y
 * si falla, falla con el error de verdad.
 *
 * Quien dice si el backend responde es [RagRepository.health], que es una comprobacion de
 * extremo a extremo contra el servidor real y no una suposicion del sistema operativo.
 */
class ConnectivityObserver(context: Context) {

    private val manager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /**
     * Emite el estado actual al suscribirse y despues cada cambio.
     *
     * `distinctUntilChanged` es imprescindible: el sistema dispara varias llamadas por
     * transicion (una por capacidad que cambia) y sin el la interfaz parpadearia.
     *
     * `conflate` porque a la UI solo le interesa el ultimo valor: si llegan tres cambios
     * mientras se recompone, los dos primeros ya no importan.
     */
    val isOnline: Flow<Boolean> = callbackFlow {
        val connectivityManager = manager
        if (connectivityManager == null) {
            // Sin ConnectivityManager no se puede saber. Se asume que hay red y que sera la
            // peticion la que falle: es preferible a bloquear la app con un aviso falso.
            Log.w(App.LOG_TAG, "No hay ConnectivityManager; se asume que hay red")
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(connectivityManager.hayRed())
            }

            override fun onLost(network: Network) {
                trySend(connectivityManager.hayRed())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                trySend(capabilities != null)
            }
        }

        trySend(connectivityManager.hayRed())
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (error: SecurityException) {
            // Falta ACCESS_NETWORK_STATE. No es motivo para tumbar la pantalla.
            Log.e(App.LOG_TAG, "No se pudo observar la red", error)
            trySend(true)
        }

        awaitClose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (ignored: IllegalArgumentException) {
                // Ya estaba dado de baja. Ocurre si el registro de arriba fallo.
            }
        }
    }.distinctUntilChanged().conflate()

    /**
     * Lectura sincrona del estado. La usa el repositorio antes de cada peticion.
     *
     * Ante la duda devuelve `true`: es preferible intentar la peticion y fallar de verdad
     * que negar el servicio por una lectura que no pudimos hacer.
     */
    fun isOnlineNow(): Boolean = manager?.hayRed() ?: true
}

/**
 * `true` si hay ALGUNA red activa, validada o no.
 *
 * Deliberadamente permisivo. Lo unico que descarta es el caso en el que ninguna peticion
 * puede salir del telefono: modo avion, wifi apagado y sin datos moviles. Ver la explicacion
 * en la cabecera de [ConnectivityObserver].
 */
private fun ConnectivityManager.hayRed(): Boolean {
    val active = activeNetwork ?: return false
    return getNetworkCapabilities(active) != null
}
