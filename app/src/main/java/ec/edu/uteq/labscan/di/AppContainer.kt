package ec.edu.uteq.labscan.di

import android.content.Context
import ec.edu.uteq.labscan.BuildConfig
import ec.edu.uteq.labscan.data.local.EquipmentCatalog
import ec.edu.uteq.labscan.data.local.SettingsStore
import ec.edu.uteq.labscan.data.remote.BaseUrlInterceptor
import ec.edu.uteq.labscan.data.remote.ConnectivityObserver
import ec.edu.uteq.labscan.data.remote.LabScanApi
import ec.edu.uteq.labscan.data.remote.MockInterceptor
import ec.edu.uteq.labscan.data.remote.NetworkTimeouts
import ec.edu.uteq.labscan.data.remote.RagRepository
import ec.edu.uteq.labscan.detection.Detector
import ec.edu.uteq.labscan.detection.DetectorFactory
import ec.edu.uteq.labscan.detection.DetectorStatus
import ec.edu.uteq.labscan.detection.ModelConfig
import ec.edu.uteq.labscan.voice.AudioFocusController
import ec.edu.uteq.labscan.voice.SttManager
import ec.edu.uteq.labscan.voice.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Unico punto de construccion de dependencias de la app.
 *
 * El proyecto no usa Hilt ni Koin (regla fija de CLAUDE.md): la inyeccion es manual y pasa
 * toda por aqui. Lo instancia [ec.edu.uteq.labscan.App] al arrancar y vive lo que vive el
 * proceso.
 *
 * Todo se crea de forma perezosa, para no penalizar el arranque en frio: leer los assets y
 * construir el detector ocurre la primera vez que la pantalla del scanner lo pide, no al
 * abrir la app.
 *
 * Que falta por llegar:
 * - F7: ajustes de rendimiento y compilacion de entrega.
 *
 * @param context contexto de aplicacion. Nunca guardar aqui un contexto de Activity: este
 *   objeto sobrevive a los cambios de configuracion y provocaria una fuga.
 */
class AppContainer(private val context: Context) {

    /** Contexto de aplicacion, para las dependencias que necesiten leer `assets/`. */
    val applicationContext: Context get() = context.applicationContext

    /** Configuracion de inferencia leida de `assets/model_config.json`. */
    val modelConfig: ModelConfig by lazy { ModelConfig.load(applicationContext) }

    private val detectorFactory: DetectorFactory by lazy { DetectorFactory(applicationContext) }

    /**
     * Detector activo. Es [Detector], nunca una implementacion concreta: fuera del paquete
     * `detection` nadie sabe si por debajo hay TensorFlow o cajas falsas.
     */
    val detector: Detector by lazy { detectorFactory.create(modelConfig) }

    /**
     * Motivo por el que esta activo el detector que esta activo. La pantalla del scanner lo
     * observa para avisar cuando se esta trabajando con cajas de prueba.
     *
     * Se lee `detector` antes de exponer el flujo para forzar su construccion: si no, el
     * estado se quedaria en `Unknown` hasta el primer frame.
     */
    val detectorStatus: StateFlow<DetectorStatus>
        get() {
            detector
            return detectorFactory.status
        }

    /** Fichas tecnicas sin conexion, leidas de assets/catalog.json. */
    val equipmentCatalog: EquipmentCatalog by lazy { EquipmentCatalog(applicationContext) }

    // -----------------------------------------------------------------------------------
    // Red (F5)
    // -----------------------------------------------------------------------------------

    /**
     * Configuracion de kotlinx.serialization para todo el trafico del backend.
     *
     * `ignoreUnknownKeys` porque el backend puede crecer y una app ya instalada no debe
     * romperse por un campo nuevo. `explicitNulls = false` para no enviar `"classId": null`
     * cuando el estudiante pregunta sin equipo seleccionado: el contrato permite omitirlo.
     *
     * En las pruebas de `assets/mock/` se usa a proposito un `Json` **estricto**, para que
     * un campo mal escrito falle ahi en lugar de aparecer vacio en pantalla.
     */
    private val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
    }

    /**
     * Aplica la URL de backend elegida en Ajustes. Ver [BaseUrlInterceptor].
     *
     * Se expone para que la pantalla de Ajustes pueda escribir en el sin reconstruir el
     * cliente HTTP.
     */
    val baseUrlInterceptor: BaseUrlInterceptor by lazy { BaseUrlInterceptor() }

    /** Estado de la red. Lo observa la UI y lo consulta el repositorio antes de cada llamada. */
    val connectivityObserver: ConnectivityObserver by lazy {
        ConnectivityObserver(applicationContext)
    }

    /**
     * Cliente HTTP unico de la app.
     *
     * El orden de los interceptores importa: el de registro va primero para que su salida
     * incluya tambien lo que devuelve el mock, que es justo lo que se quiere mirar mientras
     * el backend no existe.
     *
     * Ninguno de los dos llega a release: `HttpLoggingInterceptor` volcaria cuerpos enteros
     * al log, y `MockInterceptor` devolveria datos de mentira.
     */
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(NetworkTimeouts.CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NetworkTimeouts.READ_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(NetworkTimeouts.WRITE_SECONDS, TimeUnit.SECONDS)
            .apply {
                // Va el primero de todos: reescribe el destino antes de que nadie mas mire la
                // URL, incluido el registro, que asi muestra la direccion real usada.
                addInterceptor(baseUrlInterceptor)
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BODY
                        }
                    )
                }
                if (BuildConfig.USE_MOCK_API) {
                    addInterceptor(MockInterceptor(applicationContext))
                }
            }
            .build()
    }

    /** Cliente del contrato de docs/CONTRATO_API.md. */
    val labScanApi: LabScanApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(LabScanApi::class.java)
    }

    /**
     * Fachada de datos del asistente. Es lo unico que la UI conoce de la capa de red: ni la
     * pantalla ni el ViewModel ven Retrofit, OkHttp ni una excepcion de red.
     */
    val ragRepository: RagRepository by lazy {
        RagRepository(
            api = labScanApi,
            catalog = equipmentCatalog,
            connectivity = connectivityObserver
        )
    }

    // -----------------------------------------------------------------------------------
    // Voz y preferencias (F6)
    // -----------------------------------------------------------------------------------

    /**
     * Motor de lectura en voz alta.
     *
     * Uno solo para toda la app, y no uno por pantalla: enlazar `TextToSpeech` tarda decimas
     * de segundo y reconstruirlo en cada navegacion se notaria como un retardo antes de la
     * primera palabra. Lo usan el chat y la ficha tecnica.
     */
    val ttsManager: TtsManager by lazy { TtsManager(applicationContext, audioFocusController) }

    /**
     * Foco de audio de la app (F8).
     *
     * Uno solo y compartido, porque lleva la cuenta de quien lo tiene pedido: el TTS mientras
     * lee y la pantalla de conversacion durante toda la llamada. Con una instancia por parte,
     * la primera en terminar le quitaria el foco a la otra y la app dejaria de enterarse de
     * las llamadas telefonicas a mitad de conversacion.
     */
    val audioFocusController: AudioFocusController by lazy {
        AudioFocusController(applicationContext)
    }

    /**
     * Dictado de preguntas.
     *
     * Tambien unico: `SpeechRecognizer` es un recurso del sistema y dos instancias
     * escuchando a la vez se estorban con ERROR_RECOGNIZER_BUSY.
     */
    val sttManager: SttManager by lazy { SttManager(applicationContext) }

    /** Preferencias del estudiante en DataStore. */
    val settingsStore: SettingsStore by lazy { SettingsStore(applicationContext) }

    /**
     * Conecta las preferencias con quien las obedece.
     *
     * Existe porque ni el detector ni el cliente HTTP pueden observar DataStore por su cuenta:
     * uno vive en el hilo de analisis y el otro en los de OkHttp, y ninguno de los dos puede
     * suspenderse. Alguien tiene que recoger los flujos y empujarles el valor, y ese alguien
     * es el contenedor, que es quien conoce a las dos partes.
     *
     * Lo arranca [ec.edu.uteq.labscan.App] al crear el contenedor.
     */
    fun observeSettings(scope: CoroutineScope) {
        settingsStore.confidenceThreshold(modelConfig.confidenceThreshold)
            .onEach { detector.confidenceThreshold = it }
            .launchIn(scope)

        settingsStore.backendUrl
            .onEach { baseUrlInterceptor.override = it }
            .launchIn(scope)
    }

    /** Medidor de FPS y latencia, compartido entre el HUD del scanner y el Diagnostico. */
    val performanceTracker: PerformanceTracker by lazy { PerformanceTracker() }

    /**
     * Hilo unico donde corre `ImageAnalysis`.
     *
     * Uno solo, y no un pool: la inferencia es secuencial y con varios hilos los resultados
     * llegarian desordenados. La contrapresion la resuelve STRATEGY_KEEP_ONLY_LATEST.
     */
    val analysisExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "labscan-analysis").apply { isDaemon = true }
        }
    }

    /**
     * Libera lo que reserva la app.
     *
     * No se llama desde `Application.onTerminate()`, que en dispositivos reales no se
     * invoca: esto existe para las pruebas y para un cierre ordenado si alguna vez hace
     * falta.
     *
     * Los motores de voz se sueltan aqui y **no** al salir de una pantalla: son caros de
     * enlazar y viven lo que vive el proceso.
     */
    fun close() {
        detector.close()
        ttsManager.shutdown()
        sttManager.release()
        analysisExecutor.shutdown()
    }
}
