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
 * "Con red" significa aqui `NET_CAPABILITY_VALIDATED`, no solo `INTERNET`: un wifi de
 * cafeteria con portal cautivo tiene `INTERNET` y no llega a ninguna parte. Es justo el caso
 * en el que el usuario merece ver el aviso.
 *
 * Estar en linea **no** garantiza que el backend responda; para eso esta
 * [RagRepository.health].
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
                trySend(connectivityManager.isValidated())
            }

            override fun onLost(network: Network) {
                trySend(connectivityManager.isValidated())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                trySend(capabilities.isUsable())
            }
        }

        trySend(connectivityManager.isValidated())
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
     * Lectura sincrona del estado. La usa el repositorio en su camino rapido.
     *
     * Ante la duda devuelve `true`: preferimos intentar la peticion y fallar de verdad antes
     * que negar el servicio por una lectura que no pudimos hacer.
     */
    fun isOnlineNow(): Boolean = manager?.isValidated() ?: true
}

/** `true` si la red activa esta validada, es decir, si de verdad se llega a Internet. */
private fun ConnectivityManager.isValidated(): Boolean {
    val active = activeNetwork ?: return false
    return getNetworkCapabilities(active).isUsable()
}

private fun NetworkCapabilities?.isUsable(): Boolean =
    this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
