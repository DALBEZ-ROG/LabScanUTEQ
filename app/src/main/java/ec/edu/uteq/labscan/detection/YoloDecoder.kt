package ec.edu.uteq.labscan.detection

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/** Como esta organizado el tensor de salida. */
enum class OutputLayout {
    /** `[1, 4 + N, 8400]`. Lo que produce Ultralytics por defecto: candidatos al final. */
    TRANSPOSED,

    /** `[1, 8400, 4 + N]`. El orden intuitivo: una fila por candidato. */
    STANDARD;

    companion object {
        fun fromConfig(value: String): OutputLayout =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: TRANSPOSED
    }
}

/**
 * Como se interpreta un tensor de salida concreto.
 *
 * Se deduce de la forma **real** que declara el interprete, no de constantes: ni el numero
 * de candidatos ni el de clases se escriben a mano en ninguna parte (CLAUDE.md, regla 4).
 *
 * @param numCandidates cajas propuestas, tipicamente 8400 para una entrada de 640.
 * @param numClasses clases inferidas del tensor: el eje de canales menos las 4 coordenadas.
 * @param layout con cual de las dos disposiciones se leyo.
 * @param layoutOverridden `true` si la forma real contradecia a `model_config.json` y se
 *   ignoro lo que decia la configuracion.
 */
data class OutputSpec(
    val numCandidates: Int,
    val numClasses: Int,
    val layout: OutputLayout,
    val layoutOverridden: Boolean
) {
    companion object {
        /** Numero de coordenadas por caja: cx, cy, w, h. YOLOv8 no tiene objectness. */
        const val COORDS = 4

        /**
         * Deduce la interpretacion a partir de la forma real del tensor.
         *
         * Se parte de lo que declara `model_config.json`, pero se comprueba contra la
         * forma: el eje de canales vale `4 + N` y el de candidatos ronda los miles, asi que
         * el eje corto es siempre el de canales. Si la configuracion dice lo contrario gana
         * la forma real y queda constancia en [layoutOverridden], que la pantalla de
         * Diagnostico muestra.
         */
        fun from(shape: IntArray, declared: OutputLayout): OutputSpec {
            require(shape.size == 3) { "Salida inesperada: ${shape.joinToString("x")}" }
            val a = shape[1]
            val b = shape[2]

            // Con 80 clases son 84 canales frente a 8400 candidatos; incluso con una sola
            // clase serian 5 frente a 8400.
            val channelsFirst = a < b
            val actual = if (channelsFirst) OutputLayout.TRANSPOSED else OutputLayout.STANDARD

            val channels = if (channelsFirst) a else b
            val candidates = if (channelsFirst) b else a
            require(channels > COORDS) { "El tensor no tiene canales de clase: $channels" }

            return OutputSpec(
                numCandidates = candidates,
                numClasses = channels - COORDS,
                layout = actual,
                layoutOverridden = actual != declared
            )
        }
    }
}

/** Lo que se aprendio de la salida del ultimo frame, para la pantalla de Diagnostico. */
data class DecodeStats(val min: Float, val max: Float, val candidatesOverThreshold: Int)

/**
 * Decodifica el tensor de salida de YOLOv8/YOLO11 y aplica supresion de no maximos.
 *
 * Es logica pura: no conoce TensorFlow ni Android salvo `RectF`. Por eso se cubre entera
 * con pruebas unitarias.
 *
 * Detalle del formato en docs/INTEGRACION_MODELO.md, seccion 4.
 */
class YoloDecoder(
    private val spec: OutputSpec,
    /**
     * Umbral vigente. Es `var` y `@Volatile` porque Ajustes lo mueve desde el hilo
     * principal mientras el de analisis lo lee en cada frame. Un valor a medio escribir no
     * es posible en un `Float`, y leer el anterior durante un frame no tiene consecuencias.
     */
    @Volatile
    var confidenceThreshold: Float,
    private val iouThreshold: Float,
    private val maxDetections: Int,
    private val coordsNormalized: Boolean,
    private val inputSize: Int
) {

    /** Ultimo rango observado en la salida. Lo lee la pantalla de Diagnostico. */
    var lastStats: DecodeStats = DecodeStats(0f, 0f, 0)
        private set

    // Reutilizados entre frames para no asignar en el bucle de inferencia.
    private val candidates = ArrayList<Candidate>(MAX_CANDIDATES_KEPT)
    private val survivors = ArrayList<Candidate>(MAX_CANDIDATES_KEPT)

    /**
     * @param output tensor ya descuantizado y aplanado, de tamano
     *   `numCandidates * (4 + numClasses)`.
     * @param labels clases leidas de `labels.txt`, en el orden del entrenamiento.
     */
    fun decode(output: FloatArray, labels: List<String>): List<Detection> {
        candidates.clear()

        var minSeen = Float.MAX_VALUE
        var maxSeen = -Float.MAX_VALUE
        val channels = OutputSpec.COORDS + spec.numClasses

        for (i in 0 until spec.numCandidates) {
            // La unica diferencia entre las dos disposiciones es como se indexa.
            val strideChannel: Int
            val base: Int
            if (spec.layout == OutputLayout.TRANSPOSED) {
                // [canal][candidato]: avanzar un canal salta una fila entera.
                strideChannel = spec.numCandidates
                base = i
            } else {
                // [candidato][canal]: los canales de un candidato son contiguos.
                strideChannel = 1
                base = i * channels
            }

            // Mejor clase. YOLOv8 NO tiene canal de objectness: el score es directamente el
            // maximo de los N puntajes de clase.
            var bestScore = -Float.MAX_VALUE
            var bestClass = -1
            for (c in 0 until spec.numClasses) {
                val score = output[base + (OutputSpec.COORDS + c) * strideChannel]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }

            if (bestScore < minSeen) minSeen = bestScore
            if (bestScore > maxSeen) maxSeen = bestScore
            if (bestScore < confidenceThreshold || bestClass < 0) continue
            if (candidates.size >= MAX_CANDIDATES_KEPT) continue

            var cx = output[base]
            var cy = output[base + strideChannel]
            var w = output[base + 2 * strideChannel]
            var h = output[base + 3 * strideChannel]

            // Si el modelo emite pixeles del cuadrado de entrada en lugar de 0..1, se
            // normaliza aqui. Lo indica coordsNormalized en model_config.json.
            if (!coordsNormalized) {
                val size = inputSize.toFloat()
                cx /= size
                cy /= size
                w /= size
                h /= size
            }

            // cxcywh -> xyxy, recortado al cuadrado del modelo.
            candidates.add(
                Candidate(
                    left = (cx - w / 2f).coerceIn(0f, 1f),
                    top = (cy - h / 2f).coerceIn(0f, 1f),
                    right = (cx + w / 2f).coerceIn(0f, 1f),
                    bottom = (cy + h / 2f).coerceIn(0f, 1f),
                    score = bestScore,
                    classIndex = bestClass
                )
            )
        }

        lastStats = DecodeStats(
            min = if (minSeen == Float.MAX_VALUE) 0f else minSeen,
            max = if (maxSeen == -Float.MAX_VALUE) 0f else maxSeen,
            candidatesOverThreshold = candidates.size
        )

        return nonMaxSuppression(labels)
    }

    /**
     * Supresion de no maximos **por clase**: dos objetos distintos pueden solaparse mucho,
     * por ejemplo una botella delante de un microscopio, y no deben suprimirse entre si.
     */
    private fun nonMaxSuppression(labels: List<String>): List<Detection> {
        survivors.clear()
        // Mayor puntaje primero: el ganador de cada grupo suprime a los demas.
        candidates.sortByDescending { it.score }

        for (candidate in candidates) {
            if (survivors.size >= maxDetections) break
            val overlapsBetterOne = survivors.any { kept ->
                kept.classIndex == candidate.classIndex && iou(kept, candidate) > iouThreshold
            }
            if (!overlapsBetterOne) survivors.add(candidate)
        }

        return survivors.map { c ->
            Detection(
                classId = labels.getOrElse(c.classIndex) { "clase_${c.classIndex}" },
                classIndex = c.classIndex,
                score = c.score,
                box = RectF(c.left, c.top, c.right, c.bottom)
            )
        }
    }

    private fun iou(a: Candidate, b: Candidate): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interWidth = interRight - interLeft
        val interHeight = interBottom - interTop
        if (interWidth <= 0f || interHeight <= 0f) return 0f

        val intersection = interWidth * interHeight
        val union = a.area() + b.area() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private class Candidate(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val score: Float,
        val classIndex: Int
    ) {
        fun area(): Float = (right - left) * (bottom - top)
    }

    private companion object {
        /**
         * Tope de candidatos que se guardan antes del NMS. Con un umbral razonable nunca se
         * llega; existe para que un modelo mal calibrado, que devuelva miles de cajas por
         * encima del umbral, no dispare la memoria.
         */
        const val MAX_CANDIDATES_KEPT = 300
    }
}
