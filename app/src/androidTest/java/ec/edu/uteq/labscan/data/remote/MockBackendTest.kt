package ec.edu.uteq.labscan.data.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ec.edu.uteq.labscan.data.local.EquipmentCatalog
import ec.edu.uteq.labscan.data.remote.dto.ChatRole
import ec.edu.uteq.labscan.di.AppContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recorre el contrato completo contra el mock, en el dispositivo.
 *
 * A diferencia de las pruebas de JVM, aqui el JSON viaja de verdad: pasa por OkHttp, por el
 * [MockInterceptor], por Retrofit y por el convertidor de kotlinx.serialization, exactamente
 * como lo hara el dia que responda el backend de Mario. Si algo del cableado esta mal (una
 * ruta con barra de mas, un converter mal registrado, un DTO que no encaja), falla aqui y no
 * el dia de la integracion.
 *
 * No abre ninguna pantalla, asi que corre con el telefono bloqueado.
 *
 * Requiere que el dispositivo tenga red validada: [ConnectivityObserver] corta las peticiones
 * antes de salir cuando no la hay, y entonces todo llegaria desde el catalogo local.
 */
@RunWith(AndroidJUnit4::class)
class MockBackendTest {

    private lateinit var repository: RagRepository
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        container = AppContainer(context)
        // Se construye a mano y no se usa container.ragRepository para dejar claro que la
        // prueba controla sus dependencias; la api y el observador si son los reales.
        repository = RagRepository(
            api = container.labScanApi,
            catalog = EquipmentCatalog(context),
            connectivity = container.connectivityObserver
        )
    }

    @Test
    fun health_responde_ok() = runBlocking {
        assertTrue("El mock deberia responder sano", repository.health())
    }

    @Test
    fun una_clase_con_mock_llega_por_red() = runBlocking {
        val result = repository.equipment("microscopio_binocular")
        val equipment = result.getOrThrow()

        assertEquals(DataOrigin.NETWORK, equipment.origin)
        assertTrue(equipment.fromCatalog)
        assertEquals("microscopio_binocular", equipment.details.classId)
        assertTrue("Sin fuentes se incumple la regla 6", equipment.details.sources.isNotEmpty())
        // El mock trae dos fuentes y el catalogo local solo una: es la forma de comprobar
        // que el dato que se ve viene del servidor y no del APK.
        assertEquals(2, equipment.details.sources.size)
    }

    @Test
    fun un_404_cae_al_catalogo_local() = runBlocking {
        // camara_electroforesis no tiene archivo en assets/mock/, asi que el interceptor
        // responde 404 con el sobre de error del contrato.
        val equipment = repository.equipment("camara_electroforesis").getOrThrow()

        assertEquals(DataOrigin.CACHE, equipment.origin)
        assertTrue("El catalogo local si tiene esta ficha", equipment.fromCatalog)
        assertTrue(
            "Se esperaba NotFound y llego ${equipment.reason}",
            equipment.reason is RagError.NotFound
        )
        assertEquals("Cámara de electroforesis horizontal", equipment.details.displayName)
    }

    @Test
    fun una_clase_que_no_esta_en_ningun_lado_devuelve_ficha_minima() = runBlocking {
        val equipment = repository.equipment("centrifuga_inventada").getOrThrow()

        assertEquals(DataOrigin.CACHE, equipment.origin)
        assertFalse("No deberia estar en el catalogo", equipment.fromCatalog)
        // La app no crashea ni muestra una ficha vacia sin explicacion.
        assertEquals("Centrifuga inventada", equipment.details.displayName)
    }

    @Test
    fun el_chat_alterna_entre_los_dos_casos_de_contexto() = runBlocking {
        val historial = listOf(ChatTurn(ChatRole.USER, "¿Para qué sirve?"))

        val primera = repository.chat("vortex", "¿Cómo lo apago?", historial).getOrThrow()
        val segunda = repository.chat("vortex", "¿Y el mantenimiento?", historial).getOrThrow()

        assertTrue("La primera respuesta deberia tener contexto", primera.hasSufficientContext)
        assertTrue("Con contexto hay que citar fuentes", primera.sources.isNotEmpty())

        assertFalse("La segunda deberia declarar contexto insuficiente", segunda.hasSufficientContext)
        assertTrue("Sin contexto no se citan fuentes", segunda.sources.isEmpty())
        assertTrue("Falta el aviso de consultar al docente", segunda.answer.contains("docente"))
    }
}
