package ec.edu.uteq.labscan.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import ec.edu.uteq.labscan.App
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

/**
 * Resultado de enganchar la camara. Se devuelve en lugar de lanzar, porque ninguno de
 * estos casos debe tumbar la app (CLAUDE.md, regla 2).
 */
sealed interface CameraBindResult {
    /** La camara quedo enganchada y la vista previa deberia empezar a llegar. */
    data object Success : CameraBindResult

    /** El dispositivo no tiene ese sensor. Tipico al pedir la frontal en una tablet barata. */
    data object CameraUnavailable : CameraBindResult

    /** Fallo de inicializacion o de enganche. La causa ya quedo en el log. */
    data class Failed(val cause: Throwable) : CameraBindResult
}

/**
 * Envoltorio de CameraX. Es el unico sitio de la app que conoce [ProcessCameraProvider].
 *
 * En F1 solo engancha el caso de uso [Preview]. F2 agrega `ImageAnalysis` aqui mismo,
 * compartiendo la [ASPECT_RATIO_STRATEGY] de abajo.
 *
 * No guarda ningun `Context` de Activity: se queda con el de aplicacion. Las referencias
 * al [LifecycleOwner] y al [PreviewView] se sueltan en [unbind], que el composable llama
 * desde su `DisposableEffect`.
 *
 * Todos los metodos publicos que tocan CameraX se ejecutan en el hilo principal: el
 * proveedor lo exige.
 */
class CameraBinder(
    context: Context,
    private val analysisExecutor: Executor
) {

    private val appContext: Context = context.applicationContext

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null

    private var boundOwner: LifecycleOwner? = null
    private var boundPreviewView: PreviewView? = null
    private var boundSelector: CameraSelector? = null
    private var boundAnalyzer: FrameAnalyzer? = null

    /** `true` si hay una camara enganchada ahora mismo. */
    val isBound: Boolean get() = camera != null

    /**
     * Engancha la vista previa al ciclo de vida.
     *
     * Antes de soltar lo que hubiera enganchado comprueba que el sensor pedido exista: si
     * no, no toca la sesion actual y devuelve [CameraBindResult.CameraUnavailable]. Asi,
     * pedir una camara que no esta nunca deja la pantalla en negro.
     */
    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        cameraSelector: CameraSelector,
        frameAnalyzer: FrameAnalyzer? = null
    ): CameraBindResult = withContext(Dispatchers.Main.immediate) {
        try {
            val provider = cameraProvider
                ?: ProcessCameraProvider.awaitInstance(appContext).also { cameraProvider = it }

            // Ya esta enganchado ese mismo sensor sobre la misma vista: no se toca nada.
            // Evita el parpadeo de soltar y volver a enganchar cuando el estado del
            // ViewModel revierte al sensor que ya estaba activo. La comparacion es por
            // identidad a proposito: los unicos selectores que circulan por la app son
            // las constantes DEFAULT_BACK_CAMERA y DEFAULT_FRONT_CAMERA, y CameraSelector
            // no implementa equals().
            if (isBound &&
                boundSelector === cameraSelector &&
                boundPreviewView === previewView &&
                boundAnalyzer === frameAnalyzer
            ) {
                return@withContext CameraBindResult.Success
            }

            if (!provider.hasCamera(cameraSelector)) {
                Log.w(App.LOG_TAG, "El dispositivo no tiene el sensor solicitado")
                return@withContext CameraBindResult.CameraUnavailable
            }

            // Reengancharse sin soltar lo anterior lanza IllegalArgumentException.
            provider.unbindAll()

            // Preview e ImageAnalysis comparten ResolutionSelector, y por tanto la misma
            // relacion de aspecto. Es la condicion para que CameraX recorte igual en los
            // dos y las cajas coincidan con lo que se ve (CLAUDE.md, seccion critica).
            val previewSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(ASPECT_RATIO_STRATEGY)
                .build()

            // El analisis comparte la relacion de aspecto con la vista previa (que es lo que
            // hace que el recorte coincida) pero pide MENOS resolucion. Ver D-008 y D-019:
            // copiar un frame de 1280x720 en RGBA_8888 son 3,7 MB por cada uno, y el modelo
            // lo va a reducir a 640x640 de todas formas.
            val analysisSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(ASPECT_RATIO_STRATEGY)
                .setResolutionStrategy(ANALYSIS_RESOLUTION_STRATEGY)
                .build()

            val previewUseCase = Preview.Builder()
                .setResolutionSelector(previewSelector)
                .build()
                .apply { surfaceProvider = previewView.surfaceProvider }

            val analysisUseCase = frameAnalyzer?.let { analyzer ->
                analyzer.isFrontCamera = cameraSelector === CameraSelector.DEFAULT_FRONT_CAMERA
                ImageAnalysis.Builder()
                    .setResolutionSelector(analysisSelector)
                    // Descartar los frames atrasados en lugar de encolarlos: preferimos
                    // saltarnos frames antes que dibujar cajas de hace medio segundo.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // RGBA_8888 permite usar ImageProxy.toBitmap() directamente, sin
                    // escribir a mano la conversion de YUV_420_888.
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    // CameraX gira el fotograma ANTES de entregarlo, para que el modelo lo
                    // vea derecho pase lo que pase con el telefono.
                    //
                    // Sin esto la app solo detectaba en horizontal, y el motivo tardo en
                    // verse porque no parecia un problema de orientacion: el sensor entrega
                    // siempre un bufer apaisado, asi que en horizontal coincide con el mundo
                    // y acierta, y en vertical el modelo recibe la escena tumbada 90 grados.
                    // Fue entrenado con fotos derechas, asi que una autoclave de lado es para
                    // el otra imagen y no la reconoce.
                    //
                    // Con esto, `imageInfo.rotationDegrees` llega ya en 0 y el paso de
                    // rotacion de BoxMapper se vuelve la identidad, que es lo correcto porque
                    // la imagen ya viene girada. La Activity se recrea al girar el telefono
                    // (no hay android:configChanges en el manifiesto), asi que CameraX
                    // reengancha con la rotacion de destino correcta en cada vuelta.
                    .setOutputImageRotationEnabled(true)
                    .build()
                    .apply { setAnalyzer(analysisExecutor, analyzer) }
            }

            camera = if (analysisUseCase != null) {
                provider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, previewUseCase, analysisUseCase
                )
            } else {
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, previewUseCase)
            }

            preview = previewUseCase
            imageAnalysis = analysisUseCase
            boundOwner = lifecycleOwner
            boundPreviewView = previewView
            boundSelector = cameraSelector
            boundAnalyzer = frameAnalyzer

            Log.d(App.LOG_TAG, "Camara enganchada, analisis=${analysisUseCase != null}")
            CameraBindResult.Success
        } catch (cancellation: CancellationException) {
            // La corrutina del composable se cancelo (cambio de sensor o salida de
            // pantalla). No es un error: hay que dejarla propagar.
            throw cancellation
        } catch (error: Exception) {
            Log.e(App.LOG_TAG, "No se pudo enganchar la camara", error)
            unbind()
            CameraBindResult.Failed(error)
        }
    }

    /**
     * Reengancha con otro sensor, reutilizando el [LifecycleOwner] y el [PreviewView] del
     * ultimo [bind]. Si nunca se engancho, devuelve [CameraBindResult.Failed] en lugar de
     * lanzar.
     */
    suspend fun switchCamera(cameraSelector: CameraSelector): CameraBindResult {
        val owner = boundOwner
        val previewView = boundPreviewView
        if (owner == null || previewView == null) {
            return CameraBindResult.Failed(IllegalStateException("switchCamera() antes de bind()"))
        }
        return bind(owner, previewView, cameraSelector, boundAnalyzer)
    }

    /**
     * Suelta la camara y todas las referencias.
     *
     * Se llama desde `onDispose`. Quitar el `surfaceProvider` es lo que evita que el
     * [PreviewView] siga referenciado despues de que el composable desaparece.
     */
    fun unbind() {
        cameraProvider?.unbindAll()
        // Quitar el analizador libera el hilo de analisis y suelta la referencia al
        // Detector; si no, el ejecutor podria seguir entregando frames de un caso de uso
        // ya desenganchado.
        imageAnalysis?.clearAnalyzer()
        preview?.surfaceProvider = null
        preview = null
        imageAnalysis = null
        camera = null
        boundOwner = null
        boundPreviewView = null
        boundSelector = null
        boundAnalyzer = null
        Log.d(App.LOG_TAG, "Camara soltada")
    }

    companion object {
        /**
         * Relacion de aspecto pedida a CameraX.
         *
         * IMPORTANTE PARA F2: `ImageAnalysis` debe construirse con esta misma estrategia.
         * Si `Preview` y `ImageAnalysis` piden relaciones distintas, CameraX recorta cada
         * uno por su lado y las cajas del overlay quedan desplazadas respecto a lo que se
         * ve en pantalla (CLAUDE.md, seccion critica).
         */
        val ASPECT_RATIO_STRATEGY: AspectRatioStrategy =
            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY

        /**
         * Resolucion pedida para `ImageAnalysis`, **solo** para el analisis.
         *
         * 960x540 mantiene 16:9, asi que el recorte sigue coincidiendo con el de `Preview`, y
         * reduce a la mitad los pixeles que hay que copiar en cada frame. No degrada la
         * deteccion: el letterbox lleva el frame a 640x640 de todos modos, y 960 sigue estando
         * por encima de ese lado.
         *
         * La vista previa NO lleva esta restriccion: lo que se ve en pantalla se queda a la
         * resolucion que el dispositivo prefiera.
         */
        val ANALYSIS_RESOLUTION_STRATEGY: ResolutionStrategy = ResolutionStrategy(
            Size(960, 540),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
        )
    }
}
