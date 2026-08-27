package ec.edu.uteq.labscan.data.remote

import android.content.Context
import android.util.Log
import ec.edu.uteq.labscan.App
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Backend RAG de mentira, leido de `assets/mock/`.
 *
 * Existe porque el backend de Mario todavia no esta y el resto de la app (la ficha en linea,
 * el chat de F6, la voz) no puede quedarse parado esperandolo. Se instala en el cliente
 * OkHttp solo cuando `BuildConfig.USE_MOCK_API` es `true`, asi que la compilacion de release
 * jamas lo ve.
 *
 * Devuelve respuestas **del contrato**, no inventadas: los JSON de `assets/mock/` se validan
 * contra los mismos DTO en `MockAssetsTest`. Si el mock deja de encajar con
 * docs/CONTRATO_API.md, la prueba falla antes que el dispositivo.
 *
 * ### Que se puede probar con esto
 *
 * - `/api/health` responde `ok`.
 * - `/api/equipment/{classId}` responde 200 para las clases que tienen archivo y **404 con
 *   el cuerpo de error del contrato** para las que no. Falta `camara_electroforesis` a
 *   proposito: es la unica forma de ejercitar la caida al catalogo local sin apagar el wifi.
 * - `/api/chat` **alterna** entre una respuesta con contexto suficiente y otra sin el, para
 *   que F6 pueda ver los dos estilos de la interfaz sin tocar codigo.
 *
 * @param latencyMillis retardo artificial. Sin el, la respuesta llega tan rapido que los
 *   indicadores de carga no se ven nunca y no hay forma de comprobar que funcionan.
 */
class MockInterceptor(
    context: Context,
    private val latencyMillis: Long = DEFAULT_LATENCY_MS
) : Interceptor {

    private val assets = context.applicationContext.assets

    /** Cuenta las peticiones a `/api/chat` para alternar entre los dos casos. */
    private val chatCalls = AtomicInteger(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath

        // Dormir el hilo de la peticion es correcto aqui: OkHttp llama a los interceptores
        // en el hilo de la llamada, que en esta app es siempre uno de Dispatchers.IO.
        if (latencyMillis > 0) {
            try {
                Thread.sleep(latencyMillis)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Peticion simulada interrumpida", interrupted)
            }
        }

        val (code, asset) = route(path, request.isVoiceMode())
        Log.d(App.LOG_TAG, "MOCK $path -> $code ($asset)")
        return respond(request, code, readAsset(asset))
    }

    /**
     * Decide que archivo responde a cada ruta, y con que codigo.
     *
     * @param voiceMode `true` si la peticion venia del modo de conversacion por voz. El
     *   contrato dice que entonces el backend responde en registro hablado (tres oraciones
     *   como maximo, sin listas ni markdown), asi que el mock tiene sus propios archivos:
     *   si devolviera los mismos que el chat escrito, la prueba de la voz seria una
     *   mentira y no se notaria que el campo ni siquiera esta llegando.
     */
    private fun route(path: String, voiceMode: Boolean): Pair<Int, String> = when {
        path.endsWith("/api/health") -> HTTP_OK to "$MOCK_DIR/health.json"

        path.contains("/api/equipment/") -> {
            val classId = path.substringAfterLast('/')
            val asset = "$MOCK_DIR/equipment_$classId.json"
            if (assetExists(asset)) HTTP_OK to asset else HTTP_NOT_FOUND to NOT_FOUND_ASSET
        }

        path.endsWith("/api/chat") -> {
            // Par -> con contexto, impar -> sin contexto. La primera pregunta de una sesion
            // siempre se responde bien, que es lo que se quiere al enseniar la app.
            val withContext = chatCalls.getAndIncrement() % 2 == 0
            val name = when {
                voiceMode && withContext -> "chat_voz"
                voiceMode -> "chat_voz_sin_contexto"
                withContext -> "chat"
                else -> "chat_sin_contexto"
            }
            HTTP_OK to "$MOCK_DIR/$name.json"
        }

        else -> HTTP_NOT_FOUND to NOT_FOUND_ASSET
    }

    private fun assetExists(path: String): Boolean = try {
        assets.open(path).close()
        true
    } catch (missing: IOException) {
        false
    }

    /**
     * Lee un JSON de `assets/`.
     *
     * Si el archivo no esta, no se lanza: se devuelve el sobre de error del contrato con
     * `INTERNAL_ERROR`. Un mock roto debe parecerse a un backend roto, no a un crash.
     */
    private fun readAsset(path: String): String = try {
        assets.open(path).bufferedReader().use { it.readText() }
    } catch (error: IOException) {
        Log.e(App.LOG_TAG, "Falta el asset de mock: $path", error)
        FALLBACK_ERROR_JSON
    }

    private fun respond(
        request: okhttp3.Request,
        code: Int,
        body: String
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == HTTP_OK) "OK" else "Not Found")
        .body(body.toResponseBody(JSON_MEDIA_TYPE))
        .addHeader("X-LabScan-Mock", "true")
        .build()

    private companion object {
        const val MOCK_DIR = "mock"
        const val NOT_FOUND_ASSET = "$MOCK_DIR/error_not_found.json"
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404

        /** Suficiente para ver el indicador de carga; no tanto como para molestar. */
        const val DEFAULT_LATENCY_MS = 350L

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val FALLBACK_ERROR_JSON =
            """{"error":{"code":"INTERNAL_ERROR","message":"Falta un archivo de mock."}}"""
    }
}

/**
 * Mira si el cuerpo de la peticion trae `voiceMode` en `true`.
 *
 * Se lee con una expresion regular y no deserializando el DTO a proposito: el mock imita a
 * un servidor, y un servidor no comparte las clases del cliente. Ademas, si alguien renombra
 * el campo en el DTO sin tocar el contrato, esta funcion deja de encontrarlo y la respuesta
 * de voz deja de salir, que es exactamente el aviso que se quiere.
 *
 * El cuerpo se copia con `peek` porque leer un `RequestBody` lo consume, y despues tiene que
 * poder enviarse.
 */
private fun okhttp3.Request.isVoiceMode(): Boolean {
    val body = body ?: return false
    return try {
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        VOICE_MODE_FIELD.containsMatchIn(buffer.readUtf8())
    } catch (error: IOException) {
        false
    }
}

private val VOICE_MODE_FIELD = Regex("\"voiceMode\"\\s*:\\s*true")
