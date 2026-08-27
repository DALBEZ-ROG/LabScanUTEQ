package ec.edu.uteq.labscan.ui.scanner

import android.graphics.RectF
import ec.edu.uteq.labscan.detection.Detection
import kotlin.math.abs
import kotlin.math.exp

/**
 * Suaviza el movimiento de las cajas entre una deteccion y la siguiente.
 *
 * ### El problema que resuelve
 *
 * El detector produce unas 9 listas por segundo, pero la pantalla se refresca a 60 o 120 Hz.
 * Dibujar la ultima lista tal cual significa que la caja se queda quieta durante seis o siete
 * fotogramas y despues salta de golpe. Ademas, dos inferencias consecutivas sobre la misma
 * escena nunca dan exactamente el mismo rectangulo: hay un par de pixeles de diferencia por
 * ruido del sensor. El resultado combinado es una caja que tiembla y da tirones.
 *
 * Aqui se guarda una posicion **dibujada** por caja y en cada fotograma se acerca un poco a la
 * ultima posicion **detectada**. La caja llega al mismo sitio, pero recorriendo el camino.
 *
 * ### Por que el suavizado depende del tiempo y no del fotograma
 *
 * Un factor fijo por fotograma haria que la caja se moviera al doble de velocidad en un
 * telefono de 120 Hz que en uno de 60 Hz. Con `1 - exp(-dt / tau)` el resultado depende solo
 * del tiempo transcurrido, asi que se ve igual en cualquier pantalla.
 *
 * No es seguro entre hilos: vive en la fase de dibujo del overlay, que siempre es el hilo
 * principal.
 */
class BoxSmoother {

    /** Posicion que se esta dibujando ahora mismo, por caja viva. */
    private val drawn = HashMap<Long, RectF>()

    /** Reutilizado en cada emparejamiento para no reservar en la fase de dibujo. */
    private val matched = HashSet<Long>()

    private var lastFrameNanos = 0L

    /**
     * Devuelve las detecciones con sus cajas acercadas a la posicion real.
     *
     * @param source ultima lista entregada por el detector.
     * @param frameNanos marca de tiempo del fotograma actual, de `withFrameNanos`.
     */
    fun smooth(source: List<Detection>, frameNanos: Long): List<Detection> {
        val deltaNanos = if (lastFrameNanos == 0L) 0L else frameNanos - lastFrameNanos
        lastFrameNanos = frameNanos

        if (source.isEmpty()) {
            drawn.clear()
            return source
        }

        // Un salto largo sin fotogramas (la app estuvo en segundo plano) no debe producir una
        // animacion larguisima al volver: se coloca cada caja directamente en su sitio.
        val factor = if (deltaNanos <= 0L || deltaNanos > MAX_GAP_NANOS) {
            1f
        } else {
            1f - exp(-(deltaNanos / NANOS_PER_SECOND) / TAU_SECONDS)
        }

        matched.clear()
        val result = ArrayList<Detection>(source.size)
        source.forEachIndexed { index, detection ->
            val key = detection.keyAmong(source, index)
            matched.add(key)
            val previous = drawn[key]

            if (previous == null || factor >= 1f || previous.jumpedFrom(detection.box)) {
                // Caja nueva, o un salto tan grande que interpolarlo se veria como un
                // deslizamiento por la pantalla en vez de como una deteccion nueva.
                val fresh = RectF(detection.box)
                drawn[key] = fresh
                result.add(detection.copy(box = RectF(fresh)))
            } else {
                previous.lerpTowards(detection.box, factor)
                result.add(detection.copy(box = RectF(previous)))
            }
        }

        // Las cajas que ya no llegan se olvidan; si no, el mapa creceria sin limite.
        drawn.keys.retainAll(matched)
        return result
    }

    /** Se llama al cambiar de camara o al perder el frame: la proxima caja aparece sin animar. */
    fun reset() {
        drawn.clear()
        lastFrameNanos = 0L
    }

    private companion object {
        /**
         * Constante de tiempo del suavizado. Con 80 ms la caja recorre el 70 % de la distancia
         * en cien milisegundos: se siente inmediata y aun asi no tiembla. Subirlo la vuelve
         * perezosa; bajarlo devuelve el temblor.
         */
        const val TAU_SECONDS = 0.080f

        const val NANOS_PER_SECOND = 1_000_000_000f

        /** Medio segundo sin dibujar: se considera que la animacion anterior ya no vale. */
        const val MAX_GAP_NANOS = 500_000_000L

        /**
         * Distancia a partir de la cual se deja de interpolar, en fraccion del cuadrado del
         * modelo. Media pantalla de salto no es la misma caja moviendose, es otra caja.
         */
        const val JUMP_THRESHOLD = 0.5f

        /**
         * Clave estable de una caja entre fotogramas.
         *
         * Con una sola caja de su clase basta el `classId`. Con varias del mismo tipo (tres
         * microscopios en una mesa) hace falta desempatar, y se usa la posicion dentro de la
         * lista: el decodificador las devuelve ordenadas por confianza, que es razonablemente
         * estable de un frame al siguiente. No es un seguimiento de objetos de verdad, pero
         * para suavizar el dibujo es suficiente y no cuesta nada.
         */
        fun Detection.keyAmong(all: List<Detection>, index: Int): Long {
            val sameClassBefore = (0 until index).count { all[it].classIndex == classIndex }
            return classIndex.toLong() * 1_000L + sameClassBefore
        }

        fun RectF.jumpedFrom(target: RectF): Boolean =
            abs(centerX() - target.centerX()) > JUMP_THRESHOLD ||
                abs(centerY() - target.centerY()) > JUMP_THRESHOLD

        /** Acerca este rectangulo a [target] una fraccion [factor] del camino que falta. */
        fun RectF.lerpTowards(target: RectF, factor: Float) {
            left += (target.left - left) * factor
            top += (target.top - top) * factor
            right += (target.right - right) * factor
            bottom += (target.bottom - bottom) * factor
        }
    }
}
