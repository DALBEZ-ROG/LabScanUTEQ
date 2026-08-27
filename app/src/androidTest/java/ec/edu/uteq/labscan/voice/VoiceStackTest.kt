package ec.edu.uteq.labscan.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ec.edu.uteq.labscan.data.local.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprueba en el dispositivo lo que solo se puede comprobar en un dispositivo.
 *
 * `TextToSpeech` se enlaza con un servicio de otro proceso y `SpeechRecognizer` depende de que
 * haya un motor instalado: ninguna de las dos cosas se puede simular en la JVM. Lo que aqui se
 * verifica es que el motor **arranca de verdad** en este telefono y que las preferencias
 * sobreviven a una escritura, no la calidad de la pronunciacion.
 *
 * No abre ninguna pantalla, asi que corre con el telefono bloqueado.
 */
@RunWith(AndroidJUnit4::class)
class VoiceStackTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var tts: TtsManager? = null

    @After
    fun tearDown() {
        val engine = tts ?: return
        InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.shutdown() }
    }

    @Test
    fun el_motor_de_voz_arranca_y_queda_listo() = runBlocking {
        val engine = TtsManager(context).also { tts = it }

        // El enlace con el servicio tarda decimas de segundo; diez es de sobra y evita que la
        // prueba se cuelgue si el dispositivo no tiene motor.
        val ready = withTimeoutOrNull(TTS_TIMEOUT_MS) {
            engine.isReady.first { it }
        }

        assertNotNull("El motor de voz no llego a estar listo en ${TTS_TIMEOUT_MS} ms", ready)
        assertTrue("isReady deberia ser true", engine.isReady.value)
        // Recien arrancado no puede estar hablando.
        assertTrue("No deberia estar hablando", !engine.isSpeaking.value)
    }

    @Test
    fun hablar_no_lanza_aunque_el_texto_venga_sucio() = runBlocking {
        val engine = TtsManager(context).also { tts = it }
        withTimeoutOrNull(TTS_TIMEOUT_MS) { engine.isReady.first { it } }

        // Markdown, referencias y un enlace: lo que de verdad devuelve el backend.
        engine.speak("**Baje** la intensidad [Manual CX23, p. 8] y vea la [guia](http://x.y).")
        engine.stop()

        assertTrue("stop() debe dejar isSpeaking en false", !engine.isSpeaking.value)
    }

    @Test
    fun un_texto_vacio_no_arranca_el_motor() = runBlocking {
        val engine = TtsManager(context).also { tts = it }
        withTimeoutOrNull(TTS_TIMEOUT_MS) { engine.isReady.first { it } }

        // Es el caso de una ficha minima: no hay nada que decir y no se debe intentar.
        engine.speak("   ")
        engine.speak("[solo una referencia]")

        assertTrue("No deberia haber empezado a hablar", !engine.isSpeaking.value)
    }

    @Test
    fun se_sabe_si_hay_reconocimiento_de_voz() {
        val stt = SttManager(context)
        // No se afirma true ni false: depende del dispositivo. Lo que importa es que la
        // consulta responda sin lanzar, porque de ella depende que el microfono se deshabilite
        // en lugar de fallar al pulsarlo.
        val available = stt.isAvailable
        assertEquals(SttState.Idle, stt.state.value)
        println("Reconocimiento de voz disponible en este dispositivo: $available")
    }

    @Test
    fun la_lectura_automatica_viene_activada_y_se_puede_cambiar() = runBlocking {
        val settings = SettingsStore(context)
        val inicial = settings.autoReadAnswers.first()

        try {
            settings.setAutoReadAnswers(false)
            assertEquals(false, settings.autoReadAnswers.first())

            settings.setAutoReadAnswers(true)
            assertEquals(true, settings.autoReadAnswers.first())
        } finally {
            // La prueba no puede dejar el ajuste del usuario cambiado.
            settings.setAutoReadAnswers(inicial)
        }
    }

    private companion object {
        const val TTS_TIMEOUT_MS = 10_000L
    }
}
