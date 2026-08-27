package ec.edu.uteq.labscan.voice

import ec.edu.uteq.labscan.data.remote.dto.EquipmentDto
import ec.edu.uteq.labscan.ui.scanner.toSpokenSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de lo que se manda al motor de voz.
 *
 * Corren en la JVM: ni [sanitizeForSpeech] ni [toSpokenSummary] tocan clases de Android. Su
 * valor es que un motor de sintesis no tiene forma de avisar de que esta pronunciando basura;
 * lo unico que se oye es "asterisco asterisco", y para entonces ya esta en manos del usuario.
 */
class SpeechTextTest {

    @Test
    fun `quita el enfasis de markdown y conserva la palabra`() {
        assertEquals(
            "Baje la intensidad al minimo antes de apagar.",
            sanitizeForSpeech("Baje la intensidad al **minimo** antes de apagar.")
        )
        assertEquals("texto en cursiva", sanitizeForSpeech("_texto en cursiva_"))
        assertEquals("muy importante", sanitizeForSpeech("***muy importante***"))
    }

    @Test
    fun `quita las referencias entre corchetes`() {
        val leido = sanitizeForSpeech("Apague el equipo [Manual CX23, p. 8] y cubralo.")
        assertEquals("Apague el equipo y cubralo.", leido)
        assertFalse("No debe quedar ningun corchete", leido.contains('['))
    }

    @Test
    fun `de un enlace markdown se lee el texto y no la URL`() {
        assertEquals(
            "Consulte la guia de practicas antes de empezar.",
            sanitizeForSpeech("Consulte la [guia de practicas](https://uteq.edu.ec/guia.pdf) antes de empezar.")
        )
    }

    @Test
    fun `las vinetas y los encabezados no se pronuncian`() {
        val leido = sanitizeForSpeech(
            """
            ## Procedimiento
            - Encender la lampara
            - Colocar la muestra
            """.trimIndent()
        )
        assertEquals("Procedimiento Encender la lampara Colocar la muestra", leido)
    }

    @Test
    fun `el codigo no se lee con sus comillas`() {
        assertEquals("Pulse el boton de encendido.", sanitizeForSpeech("Pulse el boton de `encendido`."))
        assertTrue(sanitizeForSpeech("```\nsetTemp(37)\n```").isBlank())
    }

    @Test
    fun `un texto ya limpio no se toca`() {
        val original = "Baje la intensidad de la lampara al minimo antes de apagar el interruptor."
        assertEquals(original, sanitizeForSpeech(original))
    }

    @Test
    fun `la ficha se lee como descripcion mas procedimiento, sin riesgos ni fuentes`() {
        val equipment = EquipmentDto(
            classId = "vortex",
            displayName = "Agitador vortex",
            shortDescription = "Agitador de tubos por movimiento orbital.",
            function = "ESTO NO SE LEE",
            basicProcedure = listOf(
                "Verificar que el tubo este bien tapado.",
                "Agitar de 5 a 15 segundos."
            ),
            risks = listOf("ESTO TAMPOCO SE LEE"),
            ppe = listOf("Bata de laboratorio")
        )

        val leido = equipment.toSpokenSummary()

        assertEquals(
            "Agitador de tubos por movimiento orbital. " +
                "Verificar que el tubo este bien tapado. " +
                "Agitar de 5 a 15 segundos.",
            leido
        )
        // Lo que la restriccion de F6 prohibe leer no puede haberse colado.
        assertFalse("Se coló la funcion", leido.contains("ESTO NO SE LEE"))
        assertFalse("Se colaron los riesgos", leido.contains("ESTO TAMPOCO"))
        assertFalse("Se colo el EPP", leido.contains("Bata"))
    }

    @Test
    fun `una ficha minima no produce una lectura rota`() {
        val leido = EquipmentDto(classId = "desconocido", displayName = "Desconocido")
            .toSpokenSummary()
        // Sin contenido no hay nada que decir; sanitizeForSpeech descarta lo que quede.
        assertTrue("Se esperaba algo vacio y llego '$leido'", sanitizeForSpeech(leido).isBlank())
    }
}
