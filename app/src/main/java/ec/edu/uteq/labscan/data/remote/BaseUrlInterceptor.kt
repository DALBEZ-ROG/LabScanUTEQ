package ec.edu.uteq.labscan.data.remote

import android.util.Log
import ec.edu.uteq.labscan.App
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Reemplaza el servidor de cada peticion por el que diga Ajustes.
 *
 * ### Por que un interceptor y no reconstruir Retrofit
 *
 * `Retrofit.baseUrl` se fija al construir el cliente y no se puede cambiar despues. La
 * alternativa seria rehacer el `Retrofit`, el `OkHttpClient` y la implementacion de
 * [LabScanApi] cada vez que alguien edita la direccion, tirando el pool de conexiones y la
 * cache de DNS. Aqui solo se reescribe el esquema, el host y el puerto de la URL ya
 * construida; la ruta y los parametros los sigue armando Retrofit como siempre, asi que el
 * contrato de docs/CONTRATO_API.md no se ve afectado.
 *
 * ### Como se lee el valor
 *
 * [override] es una lectura sin bloqueo que mantiene actualizada quien corresponda; el
 * interceptor corre en un hilo de OkHttp y no puede suspenderse para consultar DataStore.
 * Vacio o mal formado significa "usa la URL de la variante de compilacion", que es lo que
 * hace que la app siga funcionando exactamente igual si nadie toca el ajuste.
 */
class BaseUrlInterceptor : Interceptor {

    /**
     * URL elegida por el estudiante, o cadena vacia para usar la de fabrica.
     *
     * `@Volatile` porque la escribe el hilo principal desde Ajustes y la leen los hilos de
     * OkHttp. Se guarda el texto crudo y se interpreta en cada peticion: son cuatro
     * comparaciones, y asi una direccion invalida escrita a medias nunca deja la app sin red.
     */
    @Volatile
    var override: String = ""

    /**
     * URL del backend encontrado solo en la red, o vacia. La escribe [ServerDiscovery].
     *
     * Va **detras** de [override] a proposito. Si el estudiante se tomo la molestia de
     * escribir una direccion en Ajustes, esa gana: puede estar apuntando adrede a otro PC,
     * o a uno que el anuncio mDNS no alcanza. Un descubrimiento automatico que pisa lo que
     * alguien escribio a mano es imposible de depurar, porque la app deja de ir al sitio
     * que el propio Ajustes esta mostrando en pantalla.
     */
    @Volatile
    var discovered: String = ""

    /** De donde salio la direccion que se esta usando. Lo muestra la pantalla de Ajustes. */
    enum class Origen { MANUAL, RED, FABRICA }

    /**
     * Orden de preferencia: lo escrito en Ajustes, luego lo encontrado en la red, y si no,
     * la URL de la variante de compilacion.
     */
    fun origenActual(): Origen = when {
        override.isNotBlank() && override.toHttpUrlOrNull() != null -> Origen.MANUAL
        discovered.isNotBlank() -> Origen.RED
        else -> Origen.FABRICA
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val manual = override.takeIf { it.isNotBlank() }?.toHttpUrlOrNull()
        if (manual == null && override.isNotBlank()) {
            Log.w(App.LOG_TAG, "URL de backend invalida en Ajustes; se busca otra")
        }

        val target = manual ?: discovered.takeIf { it.isNotBlank() }?.toHttpUrlOrNull()

        if (target == null) {
            return chain.proceed(request)
        }

        val rewritten = request.url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .build()

        return chain.proceed(request.newBuilder().url(rewritten).build())
    }
}

/** `true` si el texto sirve como direccion de backend. La usa la pantalla de Ajustes. */
fun isValidBackendUrl(url: String): Boolean {
    if (url.isBlank()) return true // Vacio es valido: significa "la de fabrica".
    val parsed: HttpUrl = url.toHttpUrlOrNull() ?: return false
    return parsed.host.isNotBlank()
}
