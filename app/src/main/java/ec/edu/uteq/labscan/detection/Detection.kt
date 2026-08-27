package ec.edu.uteq.labscan.detection

import android.graphics.RectF

/**
 * Una deteccion: que equipo, con cuanta confianza y donde.
 *
 * ### Espacio de coordenadas de [box]
 *
 * `box` viene en el **espacio del modelo**, normalizado 0..1 sobre el cuadrado
 * `inputSize x inputSize` que produce el letterbox, **no** sobre el frame de la camara.
 * Es decir, incluye las bandas grises de relleno.
 *
 * Quitar ese relleno y volver al frame es el primer paso de
 * [ec.edu.uteq.labscan.ui.scanner.BoxMapper]. Ningun otro sitio del proyecto puede
 * interpretar estas coordenadas.
 *
 * El letterbox se aplica siempre al frame **sin rotar**, tal como lo entrega
 * `ImageAnalysis`. La rotacion se resuelve despues, al dibujar. Ver [Detector.detect].
 *
 * Es una clase tonta a proposito: sin dependencias de TensorFlow, de CameraX ni de
 * Compose, para poder cubrirla con JUnit puro.
 *
 * @param classId identificador de la clase tal como aparece en `labels.txt`,
 *   por ejemplo `microscopio_binocular`. Es la llave con la que F4 busca la ficha en
 *   `catalog.json` y con la que F5 llama a `GET /api/equipment/{classId}`.
 * @param classIndex indice de la clase dentro de `labels.txt`. Es lo que devuelve el
 *   tensor; `classId` es su traduccion.
 * @param score confianza entre 0 y 1.
 * @param box caja normalizada en el espacio del modelo.
 */
data class Detection(
    val classId: String,
    val classIndex: Int,
    val score: Float,
    val box: RectF
)
