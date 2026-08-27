package ec.edu.uteq.labscan.detection

import android.graphics.Bitmap

/**
 * INTERFAZ CENTRAL DEL PROYECTO.
 *
 * Todo lo que esta fuera del paquete `detection` depende solo de esto y de [Detection], y
 * nunca de una implementacion concreta. Es lo que permite terminar la app sin que exista
 * el modelo entrenado (docs/PLAN_FASES.md, regla de oro).
 *
 * Implementaciones: [StubDetector] (F2) y `YoloTfliteDetector` (F3).
 *
 * ### Contrato de coordenadas, que F3 debe respetar
 *
 * [detect] recibe el frame **sin rotar**, tal cual lo entrega `ImageAnalysis`, mas los
 * grados que habria que girarlo para verlo derecho. Las cajas que devuelve estan
 * normalizadas sobre el cuadrado del letterbox de ese frame **sin rotar**.
 *
 * Un modelo YOLO real necesita la imagen derecha para inferir. La forma correcta de
 * cumplir el contrato sin romper el mapeo es:
 *
 * 1. aplicar el letterbox al bitmap tal como llega,
 * 2. girar el cuadrado resultante [rotationDegrees] grados (girar un cuadrado en multiplos
 *    de 90 lo deja cuadrado, asi que no hay que rehacer el relleno),
 * 3. inferir,
 * 4. deshacer ese giro sobre las cajas normalizadas, que en un cuadrado unitario es una
 *    permutacion de coordenadas de cuatro lineas.
 *
 * Es equivalente a hacer el letterbox sobre el bitmap ya girado, porque el relleno
 * centrado y la rotacion conmutan: `padX` y `padY` simplemente se intercambian.
 */
interface Detector {

    /** Lado del cuadrado que espera el modelo, en pixeles. Tipicamente 640. */
    val inputSize: Int

    /** Clases conocidas, en el orden exacto de `labels.txt`. Nunca se codifica a mano. */
    val labels: List<String>

    /**
     * Analiza un frame.
     *
     * Se llama desde el hilo de analisis, nunca desde el principal.
     *
     * @param bitmap frame de la camara sin rotar, en ARGB_8888.
     * @param rotationDegrees grados que hay que girar [bitmap] en sentido horario para
     *   verlo derecho. Viene de `ImageProxy.imageInfo.rotationDegrees`.
     * @return detecciones ya filtradas por umbral y pasadas por NMS. Puede estar vacia.
     */
    suspend fun detect(bitmap: Bitmap, rotationDegrees: Int): List<Detection>

    /**
     * Milisegundos que tardo **solo la inferencia** del ultimo frame.
     *
     * Va aparte de la latencia total que mide `FrameAnalyzer` porque son dos cosas que se
     * optimizan de forma distinta: la total incluye la copia del `ImageProxy` a bitmap y el
     * letterbox, que se arreglan bajando la resolucion de `ImageAnalysis`; esta solo mide el
     * interprete, que se arregla bajando `inputSize` o cambiando de delegado. Sin separarlas
     * es imposible saber cual de las dos palancas tocar.
     *
     * El valor por defecto es 0 para que un detector que no mida nada no obligue a
     * implementarlo.
     */
    val lastInferenceMs: Long get() = 0L

    /**
     * Umbral de confianza en caliente.
     *
     * Es `var` porque el estudiante lo mueve desde Ajustes con la camara abierta: reconstruir
     * el detector en cada arrastre del control deslizante recargaria el modelo entero.
     * Arranca con lo que diga `model_config.json` y se escribe desde el hilo principal
     * mientras el de analisis lo lee, asi que las implementaciones lo marcan `@Volatile`.
     */
    var confidenceThreshold: Float

    /** Libera el interprete y sus buffers. Lo llama `AppContainer.close()`. */
    fun close()
}
