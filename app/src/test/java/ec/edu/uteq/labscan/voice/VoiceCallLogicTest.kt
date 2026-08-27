package ec.edu.uteq.labscan.voice

import ec.edu.uteq.labscan.ui.voice.VoiceErrorKind
import ec.edu.uteq.labscan.ui.voice.toVoiceErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de las piezas de F8 que no necesitan dispositivo.
 *
 * Son las tres que deciden si una conversacion por voz se siente bien o mal, y las tres son
 * funciones puras justamente para poder probarlas aqui: el filtro de lo que llega por el
 * microfono, el corte en frases de lo que se lee, y la normalizacion del volumen.
 */
class VoiceCallLogicTest {

    // --- Filtro de lo que se manda al backend ----------------------------------------------

    @Test
    fun `una pregunta normal pasa el filtro`() {
        assertTrue(isUsableUtterance("como se enciende la incubadora"))
        assertTrue(isUsableUtterance("¿Qué protección necesito?"))
    }

    @Test
    fun `los textos de menos de tres caracteres se descartan`() {
        assertFalse(isUsableUtterance("a"))
        assertFalse(isUsableUtterance("ok"))
        assertFalse(isUsableUtterance("   "))
        assertFalse(isUsableUtterance(""))
    }

    @Test
    fun `las muletillas solas se descartan`() {
        assertFalse(isUsableUtterance("eh"))
        assertFalse(isUsableUtterance("mmm"))
        assertFalse(isUsableUtterance("este, eh, bueno"))
        assertFalse(isUsableUtterance("o sea"))
    }

    @Test
    fun `una muletilla delante de una pregunta real no la descarta`() {
        // Es el caso que importa: la gente arranca dudando y la pregunta viene detras. Un
        // filtro que mirara solo la primera palabra se comeria la pregunta entera.
        assertTrue(isUsableUtterance("eh, como se apaga"))
        assertTrue(isUsableUtterance("mmm bueno y los riesgos"))
    }

    @Test
    fun `si y no no estan en la lista de muletillas, pero los descarta el minimo de tres`() {
        // Documenta una consecuencia real del filtro de F8, no un descuido: la regla de
        // "menos de tres caracteres se ignora" se come tambien "si" y "no", que tienen dos.
        //
        // Se deja asi a proposito. El asistente de este proyecto responde preguntas sobre
        // equipos, no hace repreguntas de si o no, de modo que el caso no aparece; y bajar el
        // minimo a dos dejaria entrar "eh", "ah" y "mm", que si aparecen constantemente.
        //
        // Lo que se comprueba aqui es que el motivo del descarte sea la longitud y no la
        // lista: en cuanto la palabra tiene cuerpo suficiente, pasa.
        assertFalse(isUsableUtterance("si"))
        assertFalse(isUsableUtterance("no"))
        assertTrue(isUsableUtterance("claro"))
        assertTrue(isUsableUtterance("si por favor"))
    }

    // --- Corte en frases para la lectura ---------------------------------------------------

    @Test
    fun `corta por punto interrogacion y exclamacion`() {
        val frases = splitIntoSentences(
            "Baje la intensidad al minimo. ¿Ya lo apago? Nunca lo mueva encendido!"
        )
        assertEquals(3, frases.size)
        assertEquals("Baje la intensidad al minimo.", frases[0])
        assertEquals("¿Ya lo apago?", frases[1])
    }

    @Test
    fun `conserva el signo final de cada frase`() {
        // El motor de sintesis usa la puntuacion para la entonacion: sin el signo de
        // interrogacion, una pregunta se lee como una afirmacion.
        val frases = splitIntoSentences("¿Como se enciende? Se pulsa el interruptor.")
        assertTrue(frases[0].endsWith("?"))
        assertTrue(frases[1].endsWith("."))
    }

    @Test
    fun `un texto sin puntuacion se devuelve entero`() {
        val frases = splitIntoSentences("pulse el interruptor lateral")
        assertEquals(listOf("pulse el interruptor lateral"), frases)
    }

    @Test
    fun `los fragmentos cortos se pegan al siguiente`() {
        // Al partir por punto, un decimal deja trozos de dos caracteres. Encolarlos aparte
        // meteria una pausa en mitad de la cifra.
        val frases = splitIntoSentences("Use 1.5 mililitros de reactivo en cada tubo.")
        assertEquals(1, frases.size)
    }

    @Test
    fun `un texto vacio no produce ninguna frase`() {
        assertTrue(splitIntoSentences("").isEmpty())
        assertTrue(splitIntoSentences("   ").isEmpty())
    }

    // --- Normalizacion del volumen ---------------------------------------------------------

    @Test
    fun `la amplitud se normaliza entre cero y uno`() {
        assertEquals(0f, normalizeRms(-2f), 0.001f)
        assertEquals(1f, normalizeRms(10f), 0.001f)
        assertEquals(0.5f, normalizeRms(4f), 0.001f)
    }

    @Test
    fun `los valores fuera de escala se recortan`() {
        // Algunos motores entregan picos muy fuera del rango documentado. Sin el recorte, la
        // forma animada de la pantalla se saldria del lienzo.
        assertEquals(0f, normalizeRms(-120f), 0.001f)
        assertEquals(1f, normalizeRms(90f), 0.001f)
    }

    @Test
    fun `el umbral de interrupcion queda por encima del rango de eco medido`() {
        // El altavoz al maximo devuelve al microfono alrededor de 0,4 normalizado en el
        // dispositivo de prueba. Si alguien baja este umbral por debajo de ahi, la app se
        // interrumpe a si misma y esta prueba lo dice antes de llegar al telefono.
        assertTrue(
            "El umbral de barge-in tiene que dejar margen sobre el eco medido",
            ec.edu.uteq.labscan.ui.voice.VoiceCallViewModel.BARGE_IN_THRESHOLD_SPEAKING > 0.45f
        )
    }

    // --- Traduccion de fallos --------------------------------------------------------------

    @Test
    fun `cada fallo del reconocedor tiene su equivalente en el modo de voz`() {
        assertEquals(VoiceErrorKind.SIN_RECONOCEDOR, SttErrorKind.UNAVAILABLE.toVoiceErrorKind())
        assertEquals(VoiceErrorKind.SIN_PERMISO, SttErrorKind.PERMISSION.toVoiceErrorKind())
        assertEquals(VoiceErrorKind.SIN_CONEXION, SttErrorKind.NETWORK.toVoiceErrorKind())
        assertEquals(VoiceErrorKind.MICROFONO, SttErrorKind.BUSY.toVoiceErrorKind())
        assertEquals(VoiceErrorKind.DESCONOCIDO, SttErrorKind.UNKNOWN.toVoiceErrorKind())
    }

    @Test
    fun `la espera creciente tiene tantos tramos como reinicios permitidos`() {
        // Si alguien agrega un reinicio sin agregar su espera, el acceso al array se sale de
        // rango y la escucha continua se cae con una excepcion en produccion.
        assertEquals(
            ContinuousSttManager.MAX_CONSECUTIVE_RESTARTS,
            ContinuousSttManager.RESTART_BACKOFF_MS.size
        )
    }

    @Test
    fun `la espera creciente crece de verdad`() {
        val esperas = ContinuousSttManager.RESTART_BACKOFF_MS
        for (index in 1 until esperas.size) {
            assertTrue(
                "La espera ${esperas[index]} no es mayor que ${esperas[index - 1]}",
                esperas[index] > esperas[index - 1]
            )
        }
    }
}
