package ec.edu.uteq.labscan.ui.scanner

import android.util.Log
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import ec.edu.uteq.labscan.App
import ec.edu.uteq.labscan.R
import androidx.lifecycle.viewModelScope
import ec.edu.uteq.labscan.camera.FrameResult
import ec.edu.uteq.labscan.data.local.SettingsStore
import ec.edu.uteq.labscan.data.remote.ConnectivityObserver
import ec.edu.uteq.labscan.data.remote.DataOrigin
import ec.edu.uteq.labscan.data.remote.RagError
import ec.edu.uteq.labscan.data.remote.RagRepository
import ec.edu.uteq.labscan.data.remote.dto.EquipmentDto
import ec.edu.uteq.labscan.detection.Detection
import ec.edu.uteq.labscan.detection.Detector
import ec.edu.uteq.labscan.di.AppContainer
import ec.edu.uteq.labscan.di.PerformanceStats
import ec.edu.uteq.labscan.di.PerformanceTracker
import ec.edu.uteq.labscan.voice.TtsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Sensor de camara en uso. */
enum class CameraFacing {
    /** Camara trasera. Es la que tiene sentido para apuntar a un equipo. */
    BACK,

    /** Camara frontal. Sirve sobre todo para verificar el espejado del overlay. */
    FRONT;

    fun toCameraSelector(): CameraSelector = when (this) {
        BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
    }

    fun opposite(): CameraFacing = if (this == BACK) FRONT else BACK
}

/**
 * Geometria del frame con el que se produjeron las detecciones vigentes.
 *
 * Va aparte de la lista de cajas pero se actualiza en el mismo instante, porque dibujar
 * cajas de un frame sobre la geometria de otro es exactamente el error que F2 evita.
 */
data class FrameGeometry(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val isFrontCamera: Boolean
)

/** Estado de la pantalla que cambia poco: sensor elegido y avisos. */
/**
 * Ficha tecnica que se esta mostrando.
 *
 * @param score confianza de la deteccion que abrio la ficha. Va aparte de [equipment]
 *   porque no es un dato del equipo, sino de este avistamiento concreto.
 * @param fromCatalog `false` si la clase no estaba ni en el backend ni en catalog.json, y
 *   la ficha es minima.
 * @param fromCache `true` si el dato salio de `assets/catalog.json` porque la red o el
 *   backend no respondieron. La ficha lo muestra con la etiqueta "sin conexion".
 */
data class EquipmentSheetUiState(
    val equipment: EquipmentDto,
    val score: Float,
    val fromCatalog: Boolean,
    val fromCache: Boolean
)

/**
 * Como esta la app respecto del backend RAG.
 *
 * Son tres y no dos porque la diferencia importa: sin red no se puede hacer nada, pero con
 * red y el backend caido la ficha local sigue funcionando y lo unico que falta es el
 * asistente. El aviso que ve el estudiante es distinto en cada caso.
 */
enum class ConnectionState {
    /** Hay red y el backend contesta. */
    ONLINE,

    /** El dispositivo no tiene conexion validada. */
    OFFLINE,

    /** Hay red, pero `/api/health` no responde `ok`. */
    BACKEND_UNREACHABLE
}

data class ScannerUiState(
    val facing: CameraFacing = CameraFacing.BACK,
    @param:StringRes val message: Int? = null
)

/**
 * ViewModel de la pantalla principal.
 *
 * Aqui no se toca CameraX ni se calcula ninguna coordenada. Recibe los resultados que
 * produce el hilo de analisis y los publica.
 *
 * El estado se reparte en varios flujos a proposito, y no en un unico `uiState`: las
 * detecciones cambian unas 30 veces por segundo y el sensor elegido casi nunca. Juntarlos
 * obligaria a recomponer toda la pantalla en cada frame.
 */
class ScannerViewModel(
    private val detector: Detector,
    private val performanceTracker: PerformanceTracker,
    private val ragRepository: RagRepository,
    private val ttsManager: TtsManager,
    connectivityObserver: ConnectivityObserver,
    settingsStore: SettingsStore
) : ViewModel() {

    /**
     * Camara preferida, leida de Ajustes.
     *
     * Solo se aplica **una vez**, al abrir la pantalla: si se aplicara en cada emision, el
     * boton de cambiar de camara revertiria solo al valor guardado y pareceria averiado.
     */
    private var initialFacingApplied = false

    // --- Apertura automatica de la ficha -------------------------------------------------

    /** Si esta activada en Ajustes. Se lee sin suspender desde el hilo de analisis. */
    @Volatile
    private var autoOpenEnabled = true

    /**
     * Clase que el estudiante acaba de cerrar.
     *
     * Sin esto, cerrar la ficha de un equipo que sigue delante de la camara la vuelve a abrir
     * en el siguiente fotograma y la app queda inservible. Se olvida en cuanto esa clase deja
     * de detectarse, para que volver a apuntarla si la abra.
     */
    private var dismissedClassId: String? = null

    /** Clase candidata y cuantos fotogramas seguidos lleva. Ver [autoOpenIfConfident]. */
    private var candidateClassId: String? = null
    private var candidateFrames = 0

    /** `true` mientras se lee la ficha en voz alta. El boton pasa a "Detener". */
    val isSpeaking: StateFlow<Boolean> = ttsManager.isSpeaking

    /** `false` si el dispositivo no tiene motor de voz: el boton "Escuchar" se deshabilita. */
    val isVoiceReady: StateFlow<Boolean> = ttsManager.isReady

    /**
     * Salud del backend, refrescada cada vez que la red cambia de estado.
     *
     * Empieza en `true` para no ensenar un aviso de "asistente caido" durante el segundo
     * que tarda la primera comprobacion: mas vale callar hasta saberlo.
     */
    private val backendHealthy = MutableStateFlow(true)

    private val online = MutableStateFlow(true)

    /**
     * Que aviso de conexion mostrar, si es que hay alguno.
     *
     * `WhileSubscribed` con 5 s de gracia: al girar la pantalla el ViewModel sobrevive pero
     * la UI se vuelve a suscribir, y sin esa ventana se recalcularia el estado de cero.
     */
    val connection: StateFlow<ConnectionState> =
        combine(online, backendHealthy) { hasNetwork, healthy ->
            when {
                !hasNetwork -> ConnectionState.OFFLINE
                !healthy -> ConnectionState.BACKEND_UNREACHABLE
                else -> ConnectionState.ONLINE
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.ONLINE)

    init {
        // La preferencia se lee en un campo suelto porque quien la consulta es el hilo de
        // analisis, que no puede suspenderse para preguntarle a DataStore.
        settingsStore.autoOpenSheet
            .onEach { autoOpenEnabled = it }
            .launchIn(viewModelScope)

        settingsStore.defaultCameraBack
            .onEach { back ->
                if (initialFacingApplied) return@onEach
                initialFacingApplied = true
                val preferred = if (back) CameraFacing.BACK else CameraFacing.FRONT
                _uiState.update { it.copy(facing = preferred) }
            }
            .launchIn(viewModelScope)

        // Un solo recolector para toda la vida del ViewModel. Cada vez que la red aparece o
        // desaparece se vuelve a preguntar al backend: recuperar el wifi sin volver a
        // comprobar dejaria el aviso pegado en pantalla.
        connectivityObserver.isOnline
            .onEach { hasNetwork ->
                online.value = hasNetwork
                backendHealthy.value = if (hasNetwork) ragRepository.health() else false
            }
            .launchIn(viewModelScope)
    }

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections.asStateFlow()

    private val _frameGeometry = MutableStateFlow<FrameGeometry?>(null)
    val frameGeometry: StateFlow<FrameGeometry?> = _frameGeometry.asStateFlow()

    /** Delegado en el tracker compartido: la pantalla de Diagnostico lee el mismo objeto. */
    val performance: StateFlow<PerformanceStats> = performanceTracker.stats

    private val _selectedDetection = MutableStateFlow<Detection?>(null)

    /**
     * Deteccion tocada por el estudiante, o `null` si no hay ninguna.
     *
     * Mientras no sea `null`, el overlay queda **congelado**: ver [onFrameAnalyzed].
     */
    val selectedDetection: StateFlow<Detection?> = _selectedDetection.asStateFlow()

    private val _equipmentSheet = MutableStateFlow<EquipmentSheetUiState?>(null)

    /** Ficha tecnica a mostrar, o `null` si la hoja esta cerrada. */
    val equipmentSheet: StateFlow<EquipmentSheetUiState?> = _equipmentSheet.asStateFlow()

    private val _sheetLoading = MutableStateFlow(false)

    /**
     * `true` entre el toque y la llegada de la ficha.
     *
     * Existe porque con backend de por medio ese hueco puede durar segundos: sin indicador,
     * el estudiante toca una caja, no pasa nada visible y vuelve a tocar.
     */
    val sheetLoading: StateFlow<Boolean> = _sheetLoading.asStateFlow()

    /** Lado del cuadrado del modelo. El overlay lo necesita para invertir el letterbox. */
    val modelInputSize: Int get() = detector.inputSize

    /**
     * Llega desde el hilo de analisis, no desde el principal.
     *
     * `MutableStateFlow.value` es seguro entre hilos, asi que no hace falta saltar al hilo
     * principal: Compose ya recoge estos flujos con conciencia del ciclo de vida.
     */
    fun onFrameAnalyzed(result: FrameResult) {
        // El rendimiento se sigue midiendo aunque la vista este congelada: la camara y la
        // inferencia no se detienen, solo se deja de publicar lo que se dibuja.
        performanceTracker.record(result.latencyMs, result.inferenceMs, result.timestampMs)

        // CONGELADO. Con la ficha abierta, las cajas dejan de moverse para que el estudiante
        // pueda leer sin que aquello que esta mirando se le escape de la pantalla.
        if (_selectedDetection.value != null) return

        _detections.value = result.detections
        autoOpenIfConfident(result.detections)
        _frameGeometry.value = FrameGeometry(
            width = result.frameWidth,
            height = result.frameHeight,
            rotationDegrees = result.rotationDegrees,
            isFrontCamera = result.isFrontCamera
        )
    }


    /**
     * Abre la ficha sola cuando una deteccion es lo bastante buena y lo bastante estable.
     *
     * Dos condiciones, y las dos hacen falta:
     *
     * - **Confianza** por encima de [SettingsStore.AUTO_OPEN_CONFIDENCE]. El umbral de dibujo
     *   es mas bajo a proposito: dibujar un cuadro de mas solo estorba, abrir una ficha de mas
     *   interrumpe.
     * - **Estabilidad**, [FRAMES_PARA_ABRIR] fotogramas seguidos con la misma clase. El
     *   detector cambia de opinion entre fotogramas contiguos, sobre todo entre equipos
     *   parecidos, y sin esta condicion la ficha se abriria con el primer parpadeo.
     *
     * Se llama desde el hilo de analisis. No toca nada que no sea seguro entre hilos:
     * `onDetectionSelected` solo escribe `MutableStateFlow.value` y lanza una corrutina.
     */
    private fun autoOpenIfConfident(detections: List<Detection>) {
        if (!autoOpenEnabled) return

        val best = detections
            .filter { it.score >= SettingsStore.AUTO_OPEN_CONFIDENCE }
            .maxByOrNull { it.score }

        if (best == null) {
            candidateClassId = null
            candidateFrames = 0
            // Lo que se cerro deja de estar vetado cuando desaparece de la vista. Asi, apuntar
            // otra vez al mismo equipo vuelve a abrir su ficha, que es lo que se espera.
            dismissedClassId = null
            return
        }

        if (best.classId == dismissedClassId) return

        if (best.classId == candidateClassId) {
            candidateFrames++
        } else {
            candidateClassId = best.classId
            candidateFrames = 1
        }

        if (candidateFrames >= FRAMES_PARA_ABRIR) {
            candidateFrames = 0
            Log.d(App.LOG_TAG, "Apertura automatica: ${best.classId} ${best.score}")
            onDetectionSelected(best)
        }
    }

    /**
     * El estudiante toco el overlay.
     *
     * @param detection caja tocada, o `null` si el toque cayo fuera de todas, lo que cierra
     *   la ficha y reanuda la deteccion en vivo.
     */
    fun onDetectionSelected(detection: Detection?) {
        Log.d(App.LOG_TAG, "Seleccion: " + (detection?.classId ?: "ninguna"))
        _selectedDetection.value = detection
        if (detection == null) {
            _equipmentSheet.value = null
            _sheetLoading.value = false
            return
        }

        // Toda la decision de red vive en RagRepository: intenta el backend y cae al
        // catalogo local por su cuenta. Aqui no se sabe ni se quiere saber cual de los dos
        // respondio, solo si el dato venia de la cache para poder etiquetarlo.
        _sheetLoading.value = true
        viewModelScope.launch {
            val result = ragRepository.equipment(detection.classId)
            // La seleccion pudo cerrarse, o cambiar a otra caja, mientras se esperaba.
            if (_selectedDetection.value !== detection) return@launch
            _sheetLoading.value = false
            result
                .onSuccess { equipment ->
                    Log.d(
                        App.LOG_TAG,
                        "Ficha lista: " + equipment.details.displayName + " (" + equipment.origin + ")"
                    )
                    _equipmentSheet.value = EquipmentSheetUiState(
                        equipment = equipment.details,
                        score = detection.score,
                        fromCatalog = equipment.fromCatalog,
                        fromCache = equipment.origin == DataOrigin.CACHE
                    )
                }
                .onFailure { error ->
                    // Hoy no ocurre: el respaldo local es total. Se contempla igual porque
                    // dejar la pantalla congelada sin ficha y sin explicacion seria peor.
                    _selectedDetection.value = null
                    _uiState.update {
                        it.copy(message = (error as? RagError)?.messageRes ?: R.string.error_red_desconocido)
                    }
                }
        }
    }

    /**
     * Boton "Escuchar" de la ficha: lee, o corta si ya estaba leyendo.
     *
     * Lee la descripcion corta y el procedimiento basico, que es lo util con las manos
     * ocupadas. **No lee riesgos ni fuentes**: los riesgos merecen leerse mirando, y una
     * lista de titulos con numeros de pagina dicha en voz alta no le sirve a nadie.
     */
    fun onToggleSheetSpeech() {
        if (isSpeaking.value) {
            ttsManager.stop()
            return
        }
        val sheet = _equipmentSheet.value ?: return
        ttsManager.speak(sheet.equipment.toSpokenSummary())
    }

    /** Corta la lectura. La pantalla lo llama al cerrar la ficha y al salir. */
    fun stopSpeaking() {
        ttsManager.stop()
    }

    /** Cierra la ficha y reanuda la deteccion en vivo. */
    fun onSheetDismissed() {
        Log.d(App.LOG_TAG, "Ficha cerrada")
        // Para que la apertura automatica no la vuelva a abrir en el siguiente fotograma.
        dismissedClassId = _selectedDetection.value?.classId
        candidateClassId = null
        candidateFrames = 0
        // La voz no puede sobrevivir a la ficha que la origino.
        ttsManager.stop()
        onDetectionSelected(null)
    }

    /** Alterna entre la camara trasera y la frontal. */
    fun toggleCamera() {
        // Las cajas del sensor anterior dejan de ser validas en cuanto cambia la geometria.
        onDetectionSelected(null)
        _detections.value = emptyList()
        _uiState.update { it.copy(facing = it.facing.opposite(), message = null) }
    }

    /** El sensor pedido no existe: se vuelve al anterior, que sigue enganchado. */
    fun onCameraUnavailable() {
        _uiState.update {
            it.copy(facing = it.facing.opposite(), message = R.string.scanner_error_sin_camara)
        }
    }

    /** La camara fallo al iniciarse por cualquier otro motivo. */
    fun onCameraFailed() {
        _detections.value = emptyList()
        _uiState.update { it.copy(message = R.string.scanner_error_camara) }
    }

    /** El aviso ya se mostro; se descarta para que no reaparezca al recomponer. */
    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    companion object {
        /**
         * Factoria manual. Es lo que sustituye a Hilt: saca el [Detector] del
         * [AppContainer] y se lo pasa al ViewModel por constructor.
         */
        /**
         * Fotogramas seguidos con la misma clase antes de abrir la ficha sola.
         *
         * Dos, que a los 2,7 fotogramas por segundo medidos en el telefono son unos 0,7
         * segundos. Suficiente para descartar un parpadeo del detector y poco para que no se
         * sienta lento. Si el rendimiento sube con el modelo cuantizado habra que revisarlo,
         * porque a 10 fotogramas por segundo dos son 0,2 segundos y volveria a abrirse con
         * cualquier parpadeo.
         */
        private const val FRAMES_PARA_ABRIR = 2

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras
                ): T = ScannerViewModel(
                    detector = container.detector,
                    performanceTracker = container.performanceTracker,
                    ragRepository = container.ragRepository,
                    ttsManager = container.ttsManager,
                    connectivityObserver = container.connectivityObserver,
                    settingsStore = container.settingsStore
                ) as T
            }
    }
}

/**
 * Arma lo que se lee en voz alta de una ficha.
 *
 * Solo la descripcion corta y los pasos del procedimiento. Los pasos se unen con un punto
 * para que el motor haga una pausa entre ellos: encadenados con comas suenan como una sola
 * frase interminable y el estudiante pierde la cuenta de por que paso va.
 *
 * Es una funcion suelta para poder probarla en la JVM sin dispositivo.
 */
internal fun EquipmentDto.toSpokenSummary(): String {
    val frases = buildList {
        if (shortDescription.isNotBlank()) add(shortDescription.trimEnd('.'))
        basicProcedure.filter { it.isNotBlank() }.forEach { add(it.trimEnd('.')) }
    }
    // Una ficha minima no tiene ni descripcion ni pasos. Devolver "." haria que el motor se
    // arrancara para no decir nada; con la cadena vacia, speak() ni siquiera lo intenta.
    return if (frases.isEmpty()) "" else frases.joinToString(separator = ". ", postfix = ".")
}
