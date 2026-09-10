package ec.edu.uteq.labscan.data.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import ec.edu.uteq.labscan.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encuentra el backend en la red local por mDNS, sin que nadie escriba una IP.
 *
 * ### El problema
 *
 * El backend corre en el portatil de Mario, y ese portatil recibe una IP distinta en el
 * laboratorio de la universidad y otra en su casa. Hasta ahora cada cambio de red obligaba
 * a mirar `ipconfig` y teclear la direccion en Ajustes. El 2026-09-09 se perdio una sesion
 * entera de pruebas por eso (D-034).
 *
 * El servidor se anuncia como `_labscan._tcp` (ver `app/discovery.py` del backend) y aqui se
 * escucha. Es el mismo mecanismo con el que el telefono encuentra una impresora.
 *
 * ### Lo que este descubrimiento NO hace
 *
 * **No pisa lo que el estudiante haya escrito en Ajustes.** La direccion encontrada es la
 * tercera en orden de prioridad, detras del ajuste manual. Ver [BaseUrlInterceptor].
 *
 * Y no siempre va a funcionar: **mDNS no atraviesa routers**, asi que el telefono y el PC
 * tienen que estar en la misma red, y muchas redes de campus bloquean el trafico multicast
 * o aislan a los clientes entre si. Cuando eso pase, esto no encuentra nada y hay que
 * escribir la IP a mano, que es exactamente el camino que ya existia. Por eso es una
 * comodidad y no un reemplazo.
 */
class ServerDiscovery(context: Context) {

    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _serverUrl = MutableStateFlow<String?>(null)

    /** URL del backend encontrado, o `null` mientras no se haya encontrado ninguno. */
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    private val running = AtomicBoolean(false)

    /**
     * `resolveService` solo admite una resolucion a la vez en las versiones antiguas y
     * responde `FAILURE_ALREADY_ACTIVE` si se le encadenan dos. Con este semaforo se
     * resuelve de uno en uno.
     */
    private val resolving = AtomicBoolean(false)
    private val pending = ArrayDeque<NsdServiceInfo>()

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        val manager = nsdManager
        if (manager == null) {
            Log.w(App.LOG_TAG, "El dispositivo no expone NsdManager; sin descubrimiento")
            return
        }
        if (!running.compareAndSet(false, true)) return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(App.LOG_TAG, "Buscando el backend en la red ($SERVICE_TYPE)")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
                Log.i(App.LOG_TAG, "Servicio encontrado: ${serviceInfo.serviceName}")
                enqueue(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // No se borra la URL encontrada. El servidor puede desaparecer del anuncio
                // por un corte de multicast de unos segundos y seguir estando ahi; olvidar
                // la direccion dejaria a la app sin backend por un parpadeo de la red.
                Log.i(App.LOG_TAG, "Servicio perdido: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                running.set(false)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(App.LOG_TAG, "No se pudo iniciar la busqueda del backend ($errorCode)")
                running.set(false)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                running.set(false)
            }
        }

        discoveryListener = listener
        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            Log.w(App.LOG_TAG, "Fallo al arrancar el descubrimiento", it)
            running.set(false)
        }
    }

    fun stop() {
        val manager = nsdManager ?: return
        val listener = discoveryListener ?: return
        runCatching { manager.stopServiceDiscovery(listener) }
        discoveryListener = null
        running.set(false)
    }

    private fun enqueue(serviceInfo: NsdServiceInfo) {
        synchronized(pending) { pending.addLast(serviceInfo) }
        drain()
    }

    private fun drain() {
        val manager = nsdManager ?: return
        if (!resolving.compareAndSet(false, true)) return

        val next = synchronized(pending) { pending.removeFirstOrNull() }
        if (next == null) {
            resolving.set(false)
            return
        }

        @Suppress("DEPRECATION") // registerServiceInfoCallback pide API 34; minSdk es 26.
        runCatching {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(App.LOG_TAG, "No se pudo resolver ${serviceInfo.serviceName} ($errorCode)")
                    resolving.set(false)
                    drain()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    onResolved(serviceInfo)
                    resolving.set(false)
                    drain()
                }
            })
        }.onFailure {
            resolving.set(false)
        }
    }

    private fun onResolved(serviceInfo: NsdServiceInfo) {
        @Suppress("DEPRECATION") // `host` sigue siendo la unica via por debajo de API 34.
        val host = serviceInfo.host

        // Solo IPv4. Una IPv6 de enlace local llega con sufijo de zona (`%wlan0`) que no
        // cabe en una URL y la peticion fallaria con una excepcion de host desconocido.
        if (host !is Inet4Address) {
            Log.w(App.LOG_TAG, "Se ignora una direccion no IPv4: $host")
            return
        }

        val url = "http://${host.hostAddress}:${serviceInfo.port}/"
        if (_serverUrl.value != url) {
            Log.i(App.LOG_TAG, "Backend encontrado en la red: $url")
            _serverUrl.value = url
        }
    }

    companion object {
        /** Tiene que coincidir con SERVICE_TYPE de `app/discovery.py` en el backend. */
        const val SERVICE_TYPE = "_labscan._tcp"
    }
}
