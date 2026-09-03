package ec.edu.uteq.labscan.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ec.edu.uteq.labscan.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/** El modelo no encaja con `labels.txt` o con lo que la app sabe interpretar. */
class ModelMismatchException(message: String) : Exception(message)

/**
 * Inferencia real con el `Interpreter` crudo de TensorFlow Lite.
 *
 * No usa la Task Library ni MediaPipe: no leen los metadatos de un export de Ultralytics y
 * esperan la salida de un SSD, no la de YOLOv8. Motivos completos en docs/DECISIONES.md D-001.
 *
 * ### Nada se asigna por frame
 *
 * El buffer de entrada, el de salida, el `IntArray` de pixeles, el `FloatArray` de trabajo y
 * el bitmap del letterbox se crean en el constructor y se reescriben. Asignar 1,6 MB de
 * bitmap treinta veces por segundo bastaria para hundir los FPS por presion del recolector.
 *
 * ### Contrato de coordenadas
 *
 * Devuelve cajas normalizadas sobre el cuadrado del letterbox del frame **sin girar**,
 * exactamente igual que [StubDetector], para que `BoxMapper` no cambie. Ver [Detector].
 *
 * @throws ModelMismatchException si `labels.txt` no cuadra con el tensor.
 */
class YoloTfliteDetector(
    context: Context,
    private val config: ModelConfig,
    override val labels: List<String>
) : Detector, DiagnosticsProvider {

    private var gpuDelegate: GpuDelegate? = null
    private val interpreter: Interpreter
    private val decoder: YoloDecoder
    private val scaler: LetterboxScaler

    private val inputTensor: TensorInfo
    private val outputTensor: TensorInfo
    private val spec: OutputSpec

    /** Lado real del cuadrado, leido del tensor y no de la configuracion. */
    override val inputSize: Int

    // --- Buffers reutilizados ---
    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer
    private val pixels: IntArray
    private val outputFloats: FloatArray

    /**
     * Vistas y arreglos de trabajo para copiar en bloque, reservados una sola vez.
     *
     * Existen por una medida concreta: escribir la entrada y leer la salida elemento a
     * elemento sobre un `ByteBuffer` directo costaba **86 ms por frame** en un SM-A566E, casi
     * tanto como la inferencia. Son 1 228 800 llamadas a `putFloat` y 705 600 a `get`, cada
     * una con su comprobacion de limites. Llenando primero un `FloatArray` plano en un bucle
     * cerrado y volcandolo de una sola vez, el coste cae a una fraccion.
     *
     * Solo se reserva el camino que el tensor real necesita: un modelo float32 no gasta
     * memoria en el arreglo de bytes ni al reves.
     */
    private val inputFloatView: FloatBuffer?
    private val inputFloatScratch: FloatArray?
    private val inputByteScratch: ByteArray?
    private val outputFloatView: FloatBuffer?

    private val _diagnostics: MutableStateFlow<ModelDiagnostics>
    override val diagnostics: StateFlow<ModelDiagnostics> get() = _diagnostics.asStateFlow()

    init {
        val model = loadModelFile(context, config.modelAsset)
        val options = Interpreter.Options().apply {
            // 4 hilos es el punto dulce en gama media: mas hilos compiten con la camara.
            //
            // Medido en un SM-A566E (8 nucleos) con yolov8n float32 a 640, mediana de 30
            // inferencias: 4 hilos = 100 ms, 6 hilos = 115 ms, 8 hilos = 106 ms. Subir de 4
            // empeora porque los nucleos que quedan libres son los que alimentan la camara y
            // componen la pantalla; quitarselos cuesta mas de lo que aporta el paralelismo.
            numThreads = THREADS
            if (config.useGpuDelegate) {
                try {
                    gpuDelegate = GpuDelegate().also { addDelegate(it) }
                    Log.i(App.LOG_TAG, "Delegado GPU activo")
                } catch (error: Throwable) {
                    // Muy comun con modelos int8: la GPU no soporta esas operaciones.
                    // Se sigue en CPU, que es lento pero funciona.
                    Log.w(App.LOG_TAG, "No se pudo crear el delegado GPU, se usa CPU", error)
                    gpuDelegate = null
                }
            }
        }

        interpreter = Interpreter(model, options)

        val input = interpreter.getInputTensor(0)
        val output = interpreter.getOutputTensor(0)
        inputTensor = input.toInfo()
        outputTensor = output.toInfo()

        // El tamano de entrada sale del tensor, nunca de una constante. Forma [1, H, W, 3].
        inputSize = try {
            validateInputShape(input.shape())
        } catch (mismatch: ModelMismatchException) {
            close()
            throw mismatch
        }
        if (inputSize != config.inputSize) {
            Log.w(
                App.LOG_TAG,
                "model_config.json dice inputSize=${config.inputSize} pero el modelo usa $inputSize; manda el modelo"
            )
        }

        spec = OutputSpec.from(output.shape(), OutputLayout.fromConfig(config.outputLayout))

        // Comprobacion que evita el error mas frecuente de integracion: labels.txt de otro
        // entrenamiento. Sin esto, las cajas saldrian con el nombre equivocado y nadie se
        // daria cuenta.
        if (labels.size != spec.numClasses) {
            close()
            throw ModelMismatchException(
                "labels.txt tiene ${labels.size} clases y el tensor declara ${spec.numClasses}"
            )
        }

        decoder = YoloDecoder(
            spec = spec,
            confidenceThreshold = config.confidenceThreshold,
            iouThreshold = config.iouThreshold,
            maxDetections = config.maxDetections,
            coordsNormalized = config.coordsNormalized,
            inputSize = inputSize
        )

        scaler = LetterboxScaler(inputSize)
        pixels = IntArray(inputSize * inputSize)
        inputBuffer = ByteBuffer
            .allocateDirect(inputSize * inputSize * CHANNELS * input.dataType().byteSize())
            .order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer
            .allocateDirect(output.numElements() * output.dataType().byteSize())
            .order(ByteOrder.nativeOrder())
        outputFloats = FloatArray(output.numElements())

        // Las vistas se crean aqui, no en cada frame: asFloatBuffer() reserva un objeto.
        val inputIsFloat = inputTensor.dataType == DataType.FLOAT32.name
        val elements = inputSize * inputSize * CHANNELS
        inputFloatView = if (inputIsFloat) inputBuffer.asFloatBuffer() else null
        inputFloatScratch = if (inputIsFloat) FloatArray(elements) else null
        inputByteScratch = if (inputIsFloat) null else ByteArray(elements)
        outputFloatView = if (outputTensor.dataType == DataType.FLOAT32.name) {
            outputBuffer.asFloatBuffer()
        } else {
            null
        }

        _diagnostics = MutableStateFlow(
            ModelDiagnostics(
                modelAsset = config.modelAsset,
                input = inputTensor,
                output = outputTensor,
                inputSize = inputSize,
                numCandidates = spec.numCandidates,
                inferredClassCount = spec.numClasses,
                labelCount = labels.size,
                layout = spec.layout,
                layoutOverridden = spec.layoutOverridden,
                usingGpuDelegate = gpuDelegate != null,
                threads = THREADS
            )
        )

        Log.i(
            App.LOG_TAG,
            "Modelo cargado: entrada ${inputTensor.shapeText} ${inputTensor.dataType}, " +
                "salida ${outputTensor.shapeText} ${outputTensor.dataType}, " +
                "${spec.numClasses} clases, disposicion ${spec.layout}"
        )
    }

    @Volatile
    private var inferenceMs: Long = 0L

    /** Solo el tiempo de `Interpreter.run`, sin la preparacion del bitmap ni el decodificado. */
    override val lastInferenceMs: Long get() = inferenceMs

    /**
     * Delegado en el decodificador, que es donde de verdad se aplica el umbral. No se guarda
     * una copia aqui para que no puedan discrepar.
     */
    override var confidenceThreshold: Float
        get() = decoder.confidenceThreshold
        set(value) {
            decoder.confidenceThreshold = value
        }

    override suspend fun detect(bitmap: Bitmap, rotationDegrees: Int): List<Detection> {
        // El letterbox se aplica al frame SIN GIRAR, que es lo que fija el contrato de
        // Detector. La rotacion la resuelve BoxMapper al dibujar, asi que aqui no se toca.
        scaler.scale(bitmap)
        writeInput(scaler.bitmap)

        outputBuffer.rewind()
        // Se cronometra SOLO el interprete: es la palanca que se toca bajando inputSize o
        // cambiando de delegado, y hay que poder distinguirla del coste de preparar el frame.
        val startedAt = System.nanoTime()
        interpreter.run(inputBuffer, outputBuffer)
        inferenceMs = (System.nanoTime() - startedAt) / 1_000_000
        readOutput()

        val detections = decoder.decode(outputFloats, labels)

        val stats = decoder.lastStats
        _diagnostics.value = _diagnostics.value.copy(
            lastOutputMin = stats.min,
            lastOutputMax = stats.max,
            lastCandidatesOverThreshold = stats.candidatesOverThreshold
        )
        return detections
    }

    /**
     * Vuelca el bitmap cuadrado en el buffer de entrada.
     *
     * Dos caminos segun el tipo real del tensor, no segun lo que diga la configuracion:
     * - cuantizado: `q = round(valor / scale + zero_point)`, saturado al rango del tipo,
     * - float32: el pixel normalizado a 0..1.
     */
    private fun writeInput(square: Bitmap) {
        square.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        inputBuffer.rewind()

        val scale = inputTensor.quantScale
        val zeroPoint = inputTensor.quantZeroPoint
        val unsigned = inputTensor.dataType == DataType.UINT8.name

        val floats = inputFloatScratch
        if (floats != null) {
            // Camino float32. El bucle solo toca un FloatArray plano, sin comprobaciones de
            // limites de buffer, y al final se vuelca todo de una sola llamada.
            var j = 0
            for (pixel in pixels) {
                // ARGB_8888: los canales se extraen en orden R, G, B, que es el que espera el
                // modelo. Si alguna vez sale todo mal detectado, sospechar de este orden.
                floats[j++] = ((pixel shr 16) and 0xFF) * INV_255
                floats[j++] = ((pixel shr 8) and 0xFF) * INV_255
                floats[j++] = (pixel and 0xFF) * INV_255
            }
            inputFloatView?.apply {
                rewind()
                put(floats)
            }
        } else {
            // Camino cuantizado, con la misma idea sobre un ByteArray.
            val bytes = inputByteScratch!!
            var j = 0
            for (pixel in pixels) {
                bytes[j++] = quantize((pixel shr 16) and 0xFF, scale, zeroPoint, unsigned)
                bytes[j++] = quantize((pixel shr 8) and 0xFF, scale, zeroPoint, unsigned)
                bytes[j++] = quantize(pixel and 0xFF, scale, zeroPoint, unsigned)
            }
            inputBuffer.put(bytes)
        }
        inputBuffer.rewind()
    }

    private fun quantize(channel: Int, scale: Float, zeroPoint: Int, unsigned: Boolean): Byte {
        val normalized = channel / 255f
        val q = (normalized / scale).roundToInt() + zeroPoint
        return if (unsigned) {
            q.coerceIn(0, 255).toByte()
        } else {
            q.coerceIn(-128, 127).toByte()
        }
    }

    /** Descuantiza la salida al `FloatArray` reutilizado: `valor = (q - zero_point) * scale`. */
    private fun readOutput() {
        outputBuffer.rewind()
        val scale = outputTensor.quantScale
        val zeroPoint = outputTensor.quantZeroPoint

        when (outputTensor.dataType) {
            // Copia en bloque: una sola llamada en lugar de 705 600 lecturas sueltas.
            DataType.FLOAT32.name -> outputFloatView?.apply {
                rewind()
                get(outputFloats)
            }

            DataType.UINT8.name -> for (i in outputFloats.indices) {
                val q = outputBuffer.get().toInt() and 0xFF
                outputFloats[i] = (q - zeroPoint) * scale
            }

            else -> for (i in outputFloats.indices) {
                // INT8: el byte de Java ya viene con signo.
                val q = outputBuffer.get().toInt()
                outputFloats[i] = (q - zeroPoint) * scale
            }
        }
        outputBuffer.rewind()
    }

    override fun close() {
        runCatching { interpreter.close() }
        runCatching { gpuDelegate?.close() }
        gpuDelegate = null
    }

    // `internal` y no `private` para que `validateInputShape` sea comprobable en la JVM.
    // Sigue sin ser API publica: no sale del modulo.
    internal companion object {
        /** Multiplicar es mas barato que dividir, y se hace 1 228 800 veces por frame. */
        const val INV_255 = 1f / 255f

        const val THREADS = 4
        const val CHANNELS = 3

        /** Indice del eje de canales en NHWC. En NCHW seria 1, y es justo lo que se rechaza. */
        const val CHANNELS_AXIS = 3

        /**
         * Comprueba que la entrada sea NHWC cuadrada y devuelve el lado del cuadrado.
         *
         * Es `internal` y pura —solo aritmetica sobre un `IntArray`, sin nada de Android ni de
         * TensorFlow— para que `YoloInputShapeTest` la cubra en la JVM. La cadena de
         * transformaciones de coordenadas ya se prueba asi (`BoxMapperTest`) y por el mismo
         * motivo: son las dos partes donde un error no se ve, se sufre.
         *
         * ### Por que existe
         *
         * El 2026-09-03 llego un export de Ultralytics en **NCHW**, `[1, 3, 640, 640]`. Sin esta
         * guarda, `shape[1]` valia 3: se reservaba un buffer para una imagen de 3x3 pixeles y
         * `writeInput` volcaba RGB intercalado donde el modelo esperaba tres planos de canal.
         *
         * Lo grave no era el fallo sino **como se manifestaba**. El interprete reventaba dentro
         * de `detect()`, que corre en `FrameAnalyzer.analyze()`, donde toda excepcion se atrapa
         * por frame para que un frame malo no tumbe la camara (regla 3 de CLAUDE.md). Resultado:
         * el Diagnostico decia "Detector real activo", **no** salia la banda de modo
         * demostracion, y la app sencillamente no detectaba nada. La unica pista era una linea
         * repitiendose en `adb logcat`.
         *
         * Fallando aqui, en construccion, `DetectorFactory` lo atrapa y cae al `StubDetector`
         * con el motivo **visible en pantalla**, que es lo que la regla 2 de CLAUDE.md pretendia
         * desde el principio. Ver D-028 y D-029.
         */
        internal fun validateInputShape(shape: IntArray): Int {
            if (shape.size != 4) {
                throw ModelMismatchException(
                    "La entrada del modelo tiene ${shape.size} dimensiones " +
                        "(${shape.joinToString("x")}); se esperaban 4: [1, lado, lado, $CHANNELS]."
                )
            }

            if (shape[CHANNELS_AXIS] != CHANNELS) {
                val pista = if (shape[1] == CHANNELS) {
                    " Parece un export NCHW (canales primero): hay que rehacerlo exportando a " +
                        "ONNX y convirtiendo con onnx2tf, que reordena los ejes."
                } else {
                    ""
                }
                throw ModelMismatchException(
                    "El modelo declara la entrada ${shape.joinToString("x")}. Se esperaba NHWC " +
                        "[1, lado, lado, $CHANNELS], con los canales al final.$pista " +
                        "Ver docs/INTEGRACION_MODELO.md."
                )
            }

            if (shape[1] != shape[2]) {
                throw ModelMismatchException(
                    "La entrada del modelo no es cuadrada: ${shape.joinToString("x")}. " +
                        "El letterbox de la app siempre produce un cuadrado."
                )
            }

            return shape[1]
        }

        /**
         * Mapea el `.tflite` en memoria en lugar de leerlo a un array.
         *
         * `openFd` solo funciona si el asset esta **sin comprimir**, que es exactamente
         * para lo que existe `androidResources.noCompress += listOf("tflite")` en
         * app/build.gradle.kts. Si alguien quita esa linea, esto falla aqui.
         */
        fun loadModelFile(context: Context, asset: String): MappedByteBuffer =
            context.assets.openFd(asset).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    stream.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        descriptor.startOffset,
                        descriptor.declaredLength
                    )
                }
            }

        fun Tensor.toInfo(): TensorInfo {
            val quant = quantizationParams()
            return TensorInfo(
                shape = shape().toList(),
                dataType = dataType().name,
                quantScale = quant.scale,
                quantZeroPoint = quant.zeroPoint
            )
        }

        /** Bytes que ocupa un elemento de este tipo. */
        fun DataType.byteSize(): Int = when (this) {
            DataType.FLOAT32, DataType.INT32 -> 4
            DataType.INT64 -> 8
            DataType.INT16 -> 2
            else -> 1
        }
    }
}
