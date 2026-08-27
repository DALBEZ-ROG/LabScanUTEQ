package ec.edu.uteq.labscan.detection

import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.delay

/**
 * Detector falso: devuelve cajas fijas sin cargar ningun modelo ni mirar los pixeles.
 *
 * Cumple dos funciones:
 *
 * 1. Permite construir y validar todo el overlay y el mapeo de coordenadas antes de que
 *    exista `model.tflite`. Es el detector con el que se cierra F2.
 * 2. Es el plan de contingencia de la regla 2 de CLAUDE.md: si el asset falta o falla al
 *    cargarse, [DetectorFactory] devuelve este detector y la app avisa en pantalla en
 *    lugar de crashear.
 *
 * ### Por que las cajas se definen sobre el FRAME y no sobre el cuadrado del modelo
 *
 * Las constantes de abajo son fracciones **del frame de la camara**, y se convierten al
 * espacio del modelo aplicando el letterbox hacia adelante. Es lo que exige la
 * verificacion obligatoria de CLAUDE.md: *"una caja fija en el 25 %-75 % del frame"*.
 *
 * Tomar 0,25-0,75 directamente sobre el cuadrado del modelo daria otra cosa: ese cuadrado
 * incluye las bandas grises, asi que en un frame 16:9 la caja saldria al 5,6 %-94,4 % del
 * eje corto y sus bordes quedarian fuera de la pantalla, que es justo lo que impide
 * comprobar el centrado a ojo. Ver docs/DECISIONES.md D-007.
 */
class StubDetector(
    override val inputSize: Int,
    override val labels: List<String>
) : Detector {

    /**
     * El detector de prueba no infiere nada, pero declara su latencia simulada para que el
     * Diagnostico y el HUD tengan algo coherente que ensenar en modo demostracion.
     */
    override val lastInferenceMs: Long get() = SIMULATED_LATENCY_MS

    /**
     * Se acepta y se ignora: las cajas de prueba son fijas y su confianza tambien, asi que
     * filtrarlas por umbral dejaria la pantalla vacia y pareceria una averia.
     */
    @Volatile
    override var confidenceThreshold: Float = 0f

    override suspend fun detect(bitmap: Bitmap, rotationDegrees: Int): List<Detection> {
        // Latencia simulada: sin ella el pipeline correria irrealmente rapido y no se
        // notarian los problemas de contrapresion que STRATEGY_KEEP_ONLY_LATEST resuelve.
        delay(SIMULATED_LATENCY_MS)

        val params = letterboxParams(bitmap.width, bitmap.height, inputSize)
        return listOf(
            buildDetection(0, 0.92f, CALIBRATION_BOX, bitmap, params),
            buildDetection(1, 0.67f, SECONDARY_BOX, bitmap, params)
        )
    }

    override fun close() {
        // No hay nada que liberar: este detector no reserva recursos nativos.
    }

    /**
     * Convierte una caja expresada en fracciones del frame al espacio del modelo.
     *
     * Es el letterbox hacia adelante: escalar y sumar el relleno. [BoxMapper] hace
     * exactamente lo inverso.
     */
    private fun buildDetection(
        classIndex: Int,
        score: Float,
        frameBox: RectF,
        bitmap: Bitmap,
        params: LetterboxParams
    ): Detection {
        val size = inputSize.toFloat()
        val box = RectF(
            (frameBox.left * bitmap.width * params.scale + params.padX) / size,
            (frameBox.top * bitmap.height * params.scale + params.padY) / size,
            (frameBox.right * bitmap.width * params.scale + params.padX) / size,
            (frameBox.bottom * bitmap.height * params.scale + params.padY) / size
        )
        return Detection(
            classId = labels.getOrElse(classIndex) { "clase_$classIndex" },
            classIndex = classIndex,
            score = score,
            box = box
        )
    }

    companion object {
        /** Latencia simulada por frame, en milisegundos. */
        const val SIMULATED_LATENCY_MS = 25L

        /**
         * Caja de calibracion, en fracciones del frame. Es la que hay que mirar para dar
         * F2 por buena: debe quedar centrada en vertical, en horizontal y con la frontal.
         */
        val CALIBRATION_BOX: RectF = RectF(0.25f, 0.25f, 0.75f, 0.75f)

        /**
         * Segunda caja, descentrada a proposito. Sirve para detectar espejados y giros
         * que la caja de calibracion, al ser simetrica, no puede revelar: esta debe
         * aparecer siempre abajo a la izquierda con la camara trasera.
         */
        val SECONDARY_BOX: RectF = RectF(0.05f, 0.60f, 0.35f, 0.90f)
    }
}
