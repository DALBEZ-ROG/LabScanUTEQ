package ec.edu.uteq.labscan.ui.scanner

import ec.edu.uteq.labscan.ui.scanner.AutoOpenDecider.Candidata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La decision de abrir la ficha sola.
 *
 * Cada prueba de aqui corresponde a algo que se midio contra un SM-A566E el 2026-09-10, no a
 * un caso inventado. Las dos primeras reglas se escribieron mal la primera vez y solo se vio
 * al apuntar el telefono a la mesa del laboratorio.
 */
class AutoOpenDeciderTest {

    private fun decisor(umbral: Float = 0.70f, frames: Int = 2) =
        AutoOpenDecider(umbral = umbral, framesNecesarios = frames)

    /** Una candidata centrada, con la confianza que se le pase. */
    private fun centrada(clase: String, score: Float) =
        Candidata(clase, score, centerX = 0.5f, centerY = 0.5f)

    @Test
    fun `no abre nada mientras no haya detecciones`() {
        val d = decisor()
        assertNull(d.decidir(emptyList()))
        assertNull(d.decidir(emptyList()))
    }

    @Test
    fun `no abre con un solo fotograma, aunque la confianza sea altisima`() {
        val d = decisor()
        assertNull(d.decidir(listOf(centrada("autoclave", 0.99f))))
    }

    @Test
    fun `abre al segundo fotograma de la misma clase`() {
        val d = decisor()
        assertNull(d.decidir(listOf(centrada("autoclave", 0.90f))))
        assertEquals(0, d.decidir(listOf(centrada("autoclave", 0.88f))))
    }

    @Test
    fun `nunca abre por debajo del umbral`() {
        val d = decisor()
        repeat(6) {
            assertNull(d.decidir(listOf(centrada("autoclave", 0.60f))))
        }
    }

    /**
     * El caso que hizo cambiar la regla.
     *
     * Medido en el telefono: el mismo aparato, sin moverse, dio 87 %, 65 % y 47 % en tres
     * fotogramas seguidos. Exigiendo fotogramas CONSECUTIVOS por encima del umbral la ficha
     * no se abria nunca. Lo que cuenta es la mejor confianza vista mientras esa clase siguio
     * siendo la candidata.
     */
    @Test
    fun `abre aunque la confianza baje, si en la racha llego al umbral`() {
        val d = decisor()
        assertNull(d.decidir(listOf(centrada("microcentrifuga", 0.87f))))
        assertEquals(0, d.decidir(listOf(centrada("microcentrifuga", 0.65f))))
    }

    /**
     * La otra regla que se corrigio en el laboratorio.
     *
     * Con el telefono en horizontal entran dos aparatos. Elegir por confianza es decidir por
     * el estudiante; se elige por punteria.
     */
    @Test
    fun `elige la mas cercana al centro, no la de mayor confianza`() {
        val d = decisor()
        val lejosPeroSegura = Candidata("centrifuga", 0.95f, centerX = 0.9f, centerY = 0.9f)
        val cercaYSuficiente = Candidata("ph_metro", 0.80f, centerX = 0.52f, centerY = 0.48f)

        assertNull(d.decidir(listOf(lejosPeroSegura, cercaYSuficiente)))
        assertEquals(
            "deberia abrir la del centro, que es el indice 1",
            1,
            d.decidir(listOf(lejosPeroSegura, cercaYSuficiente))
        )
    }

    @Test
    fun `registra la mejor confianza de la racha, no la del ultimo fotograma`() {
        // Lo contrario confunde al depurar: en el telefono aparecio "Apertura automatica
        // 0.62" con el umbral en 0,70 y parecia un fallo cuando no lo era.
        val d = decisor()
        assertNull(d.decidir(listOf(centrada("bano_maria", 0.73f))))
        assertEquals(0, d.decidir(listOf(centrada("bano_maria", 0.62f))))
        assertEquals(0.73f, d.confianzaDeLaApertura, 0.0001f)
    }

    @Test
    fun `cambiar de equipo apuntado reinicia la racha`() {
        val d = decisor()
        assertNull(d.decidir(listOf(centrada("autoclave", 0.95f))))
        // Se apunta a otra cosa: la racha del autoclave ya no vale.
        assertNull(d.decidir(listOf(centrada("microscopio", 0.95f))))
        assertEquals(0, d.decidir(listOf(centrada("microscopio", 0.95f))))
    }

    /**
     * Sin este veto la app queda inservible: cerrar la ficha de un equipo que sigue delante
     * de la camara la reabre en el fotograma siguiente, una y otra vez.
     */
    @Test
    fun `lo que se acaba de cerrar no se reabre mientras siga a la vista`() {
        val d = decisor()
        d.decidir(listOf(centrada("autoclave", 0.95f)))
        assertEquals(0, d.decidir(listOf(centrada("autoclave", 0.95f))))

        d.alCerrarFicha("autoclave")

        repeat(6) {
            assertNull(d.decidir(listOf(centrada("autoclave", 0.95f))))
        }
    }

    @Test
    fun `el veto se levanta cuando el equipo sale de la vista`() {
        val d = decisor()
        d.decidir(listOf(centrada("autoclave", 0.95f)))
        d.decidir(listOf(centrada("autoclave", 0.95f)))
        d.alCerrarFicha("autoclave")

        // Se aparta la camara.
        assertNull(d.decidir(emptyList()))

        // Y se vuelve a apuntar: ahora si debe abrirse.
        assertNull(d.decidir(listOf(centrada("autoclave", 0.95f))))
        assertEquals(0, d.decidir(listOf(centrada("autoclave", 0.95f))))
    }

    @Test
    fun `el veto solo alcanza a la clase cerrada, no a las demas`() {
        val d = decisor()
        d.decidir(listOf(centrada("autoclave", 0.95f)))
        d.decidir(listOf(centrada("autoclave", 0.95f)))
        d.alCerrarFicha("autoclave")

        assertNull(d.decidir(listOf(centrada("microscopio", 0.95f))))
        assertEquals(0, d.decidir(listOf(centrada("microscopio", 0.95f))))
    }

    @Test
    fun `abrir una ficha consume la racha y no vuelve a disparar sola`() {
        val d = decisor()
        d.decidir(listOf(centrada("autoclave", 0.95f)))
        assertEquals(0, d.decidir(listOf(centrada("autoclave", 0.95f))))

        // Sin cerrar la ficha: el siguiente fotograma no debe volver a pedir apertura, porque
        // la racha se reinicio al abrir. Hacen falta otros dos fotogramas.
        assertNull(d.decidir(listOf(centrada("autoclave", 0.95f))))
    }

    @Test
    fun `reiniciar olvida el veto y la racha`() {
        val d = decisor()
        d.decidir(listOf(centrada("autoclave", 0.95f)))
        d.decidir(listOf(centrada("autoclave", 0.95f)))
        d.alCerrarFicha("autoclave")

        d.reiniciar()

        assertNull(d.decidir(listOf(centrada("autoclave", 0.95f))))
        assertEquals(0, d.decidir(listOf(centrada("autoclave", 0.95f))))
    }

    /**
     * Una caja floja bien centrada puede quitarle la candidatura a una buena mal centrada.
     * Es el precio conocido de elegir por punteria, y esta prueba lo fija para que si alguna
     * vez cambia, sea a proposito.
     */
    @Test
    fun `una caja floja centrada bloquea a una buena descentrada`() {
        val d = decisor()
        val flojaCentrada = Candidata("mesa", 0.50f, centerX = 0.5f, centerY = 0.5f)
        val buenaLejos = Candidata("autoclave", 0.95f, centerX = 0.85f, centerY = 0.85f)

        repeat(6) {
            assertNull(d.decidir(listOf(flojaCentrada, buenaLejos)))
        }
    }
}
