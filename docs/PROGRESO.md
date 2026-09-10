# Estado del proyecto

Este archivo es la fuente de verdad sobre qué está hecho. **Todo agente lo lee antes de empezar
y lo actualiza antes de terminar.** No borrar historial, solo agregar.

Estados: `PENDIENTE` · `EN CURSO` · `HECHO` · `BLOQUEADO`

| Fase | Descripción | Estado | Notas |
|---|---|---|---|
| F0 | Bootstrap, Gradle, docs, tema | HECHO | `assembleDebug` en verde. Ver desvios D-002 y D-003 en DECISIONES.md |
| F1 | CameraX preview + permisos | HECHO | Vista previa a pantalla completa, 3 estados de permiso, sin dependencias nuevas |
| F2 | Análisis + overlay + StubDetector + BoxMapper | HECHO Y VERIFICADO | Las 3 pruebas visuales pasan en un SM-A566E, medidas sobre captura. Ver bitácora del 2026-08-26 |
| F3 | YoloTfliteDetector + diagnóstico | HECHO | Verificado en un SM-A566E con un YOLOv8n COCO: person 91 %, bus 84 %. Assets restaurados a modo demostración |
| F4 | Selección + ficha técnica + catálogo local | HECHO Y VERIFICADO | La hoja se vio abierta en un SM-A566E el 2026-08-27. Se corrigieron dos defectos que la bloqueaban |
| F5 | Retrofit + RagRepository + mock | HECHO Y VERIFICADO | 31 pruebas JVM + 5 instrumentadas. Los 4 caminos de red vistos en pantalla en un SM-A566E |
| F6 | Chat + TTS + STT | HECHO Y VERIFICADO | Recorrido completo en un SM-A566E. Solo el dictado queda por probar a mano: no se puede inyectar audio por adb |
| F7 | Rendimiento, release, evidencias | HECHO Y VERIFICADO | 188→107 ms por frame. APK release firmado y ofuscado, detección verificada tras R8 |
| F8 | Conversacion por voz manos libres | HECHO Y VERIFICADO | 5/5 criterios. Latencia 888 ms de media (limite 2,5 s), interrupcion en 377 ms (limite 400) |

## Dependencias externas

| Ítem | Responsable | Estado | Fecha comprometida |
|---|---|---|---|
| Autorización para fotografiar el laboratorio | | PENDIENTE | |
| Dataset etiquetado en Roboflow | Mario | HECHO | v2 con 54 clases y 748 fotos anotadas. 1569 / 114 / 111 tras aumentos. Pendiente: más fotos de las clases con 3 o 4, y fotos con varios equipos en el encuadre. Ver D-043 |
| `model.tflite` + `labels.txt` | Mario | v2 INTEGRADO, SIN VERIFICAR EN TELÉFONO | 54 clases, mAP@50 0,912. Entrada NHWC `[1,640,640,3]`, salida `[1,58,8400]`. Verificado con `tools/integrar_modelo.py`. Falta apuntar a un equipo real. Ver D-043 |
| Manuales y guías digitalizados para el RAG | Mario | HECHO | 54 clases, 129 documentos, 1888 fragmentos indexados. 15 clases con manual del fabricante. Ver D-035 |
| Backend RAG desplegado | Mario | HECHO, CORRE EN EL PC DE MARIO | Repositorio aparte: `labscan-rag`. Verificado desde el teléfono el 2026-09-09 (`GET /api/health` 200). No está en un servidor: hay que levantarlo con `uvicorn` y poner la IP del PC en Ajustes |

## Bitácora

<!-- Formato: AAAA-MM-DD — fase — qué se hizo — qué quedó pendiente -->

### 2026-08-26 — F0 — Bootstrap del proyecto Android

**Qué se hizo.**

- Proyecto migrado de la plantilla Java + vistas XML que traía Android Studio a Kotlin +
  Jetpack Compose + Material 3. Se eliminaron el paquete `com.uteq.software.labscanuteq`,
  `activity_main.xml` y el tema de AppCompat.
- `namespace` y `applicationId` fijados en `ec.edu.uteq.labscan`. `minSdk 26`,
  `targetSdk 35`, bytecode Java 17 (verificado: *major version* 61).
- `gradle/libs.versions.toml` reescrito como catálogo único de versiones, con las
  dependencias de las ocho fases ya declaradas: CameraX 1.4.2, TFLite 2.17.0 (+ GPU),
  Retrofit 2.12.0, OkHttp 4.12.0, kotlinx.serialization 1.9.0, navigation-compose,
  lifecycle-viewmodel-compose y datastore-preferences. Registradas en DECISIONES.md D-004.
- `androidResources.noCompress += listOf("tflite")` para poder mapear el modelo con
  `MappedByteBuffer`. **Verificado**: con un `model.tflite` de prueba, `unzip -v` sobre el
  APK muestra la entrada como `Stored`, no `Defl:N`.
- `buildConfigField` `BASE_URL` y `USE_MOCK_API` por variante, según docs/CONTRATO_API.md.
- Manifiesto: permisos `CAMERA`, `INTERNET` y `RECORD_AUDIO`; `uses-feature`
  `android.hardware.camera.any` con `required="true"`; `queries` de
  `android.speech.RecognitionService` y de `TTS_SERVICE`, necesarias en Android 11+ para
  que `SpeechRecognizer.isRecognitionAvailable()` no devuelva `false`. Orientación fijada
  en vertical: rotar complica el mapeo de coordenadas sin aportar al caso de uso.
- `App.kt` (Application) crea el `AppContainer`; `di/AppContainer.kt` queda como único
  punto de construcción de dependencias, documentado fase por fase. Sin Hilt ni Koin.
- `MainActivity.kt` con `setContent`, tema Material 3 y `NavHost` de una sola ruta,
  `"scanner"`, que muestra un marcador centrado con el texto "LabScan UTEQ".
- Tema en `ui/theme/` (Color, Type, Theme): paleta oscura forzada, acento #009B4C,
  tipografía por defecto de Material 3. Motivo en DECISIONES.md D-005.
- Estructura de paquetes completa de CLAUDE.md creada, con un `.kt` documentado por
  archivo previsto (29 archivos Kotlin en total). Cada marcador dice qué contendrá y qué
  fase lo llena.
- Assets: `model_config.json` (con `useStubDetector: true`), `labels.txt` con las 4 clases
  de ejemplo y `catalog.json` con las 4 fichas, usando exactamente los campos de
  `GET /api/equipment/{classId}` de docs/CONTRATO_API.md. Los tres viajan en el APK.
- `.gitignore` de Android y `docs/DECISIONES.md` con cinco entradas (D-001 a D-005).
- `./gradlew clean assembleDebug` → **BUILD SUCCESSFUL**, 39 tareas, sin errores.

**Desvíos respecto al plan de F0.** Dos, ambos forzados por el andamiaje que ya existía en
el repositorio (AGP 9.3.2 + Gradle 9.5), no elegidos:

1. `compileSdk 37` en lugar de 35: AGP 9 y el Compose BOM vigente lo exigen y el build
   lo rechaza si no. `targetSdk` y `minSdk` quedan como los fija CLAUDE.md. Detalle en D-002.
2. Kotlin 2.4.10 con el soporte integrado de AGP 9, en lugar de Kotlin 2.0.x con el plugin
   `org.jetbrains.kotlin.android`: ese plugin es incompatible con el DSL nuevo de AGP 9 en
   cualquier versión de Kotlin. Detalle en D-003.

**Qué quedó pendiente.**

- No hay pruebas todavía: `src/test` y `src/androidTest` están vacíos. La primera prueba
  útil es la de `BoxMapper` en F2, que es matemática pura.
- El APK de depuración pesa 44 MB por las librerías nativas de `tensorflow-lite-gpu` en
  todas las ABI. F7 debe recortarlo con *splits* por ABI. No es un problema en desarrollo.
- El icono del lanzador sigue siendo el genérico de Android Studio.
- `model.tflite` no existe aún; por eso `useStubDetector` está en `true`. La app arranca
  igual, que es justo lo que exige la regla 2 de CLAUDE.md.


### 2026-08-26 — F1 — Cámara en vivo y permisos

**Qué se hizo.**

- `camera/CameraBinder.kt`: envoltorio de CameraX. `bind()`, `unbind()` y `switchCamera()`,
  con `ProcessCameraProvider.awaitInstance()` (ya existe como `suspend` en 1.4.2, así que
  **no hizo falta ninguna dependencia nueva**). Solo engancha `Preview`; `ImageAnalysis` es F2.
- `ui/scanner/ScannerViewModel.kt`: `StateFlow<ScannerUiState>` con el sensor activo
  (`CameraFacing.BACK` por defecto) y un aviso opcional guardado como id de recurso, para
  no meter castellano dentro del ViewModel. No toca CameraX.
- `ui/scanner/ScannerScreen.kt`: `AndroidView` con `PreviewView` a pantalla completa
  (`FILL_CENTER` + `COMPATIBLE`), barra superior transparente con degradado de legibilidad,
  título y botón de cambio de cámara, y las tres pantallas de permiso.
- `MainActivity.kt`: la ruta `"scanner"` ya monta `ScannerScreen()`; se eliminó el marcador.
- `res/values/strings.xml`: 9 cadenas en español. Ningún texto literal en los composables.

**Decisiones de robustez que no estaban pedidas explícitamente pero hacían falta.**

- `bind()` comprueba `hasCamera()` **antes** de `unbindAll()`. Pedir un sensor que no
  existe devuelve `CameraUnavailable` sin tocar la sesión viva, así que la pantalla nunca
  se queda en negro. En un dispositivo sin cámara frontal, el botón de cambio avisa y
  vuelve solo a la trasera.
- `bind()` reengancha solo si cambió algo. Comparación por identidad de `CameraSelector`,
  que no implementa `equals()`; los únicos selectores que circulan son las dos constantes.
- `CancellationException` se relanza en lugar de tratarse como fallo: `LaunchedEffect` la
  usa para cancelar el enganche cuando la pantalla desaparece a mitad del arranque.
- El aviso de error se resuelve con `stringResource` fuera de la corrutina. Lo detectó
  `./gradlew lint`: `Context.getString()` dentro de un `LaunchedEffect` no es sensible a
  los cambios de configuración y devolvería el idioma anterior.
- `FLAG_KEEP_SCREEN_ON` se pone y se quita con `DisposableEffect`, limitado a esta
  pantalla: no debe seguir activo si se navega a la ficha o al chat.

**Verificación.** `./gradlew clean assembleDebug` → BUILD SUCCESSFUL, sin errores ni
advertencias del compilador. `./gradlew lint` → sin hallazgos.

**Qué quedó pendiente.**

- Sin probar en dispositivo físico: en este entorno no hay teléfono conectado ni emulador
  corriendo. La verificación manual de los tres estados de permiso queda para el usuario.
- La orientación sigue fijada en vertical desde F0, así que rotar no rehace la pantalla.
  Ver D-006: el código de F1 es correcto igual si se quita esa línea del manifiesto.
- Sin pruebas automatizadas todavía. La primera será la de `BoxMapper` en F2.


### 2026-08-26 — F2 — Análisis en vivo, overlay y mapeo de coordenadas

**Qué se hizo.**

- `detection/`: `Detection`, `Detector`, `Letterbox`, `ModelConfig`, `StubDetector` y
  `DetectorFactory`. `DetectorFactory` publica en un `StateFlow` por qué está activo el
  detector de prueba y **nunca propaga la excepción** (CLAUDE.md, regla 2).
- `camera/FrameAnalyzer.kt`: `STRATEGY_KEEP_ONLY_LATEST`, salida `RGBA_8888`, ejecutor de un
  solo hilo (`labscan-analysis`), `imageProxy.close()` en `finally`.
- `ui/scanner/BoxMapper.kt`: los 6 pasos de la cadena, cada uno comentado.
- `ui/scanner/DetectionOverlay.kt`: `Canvas` superpuesto, rectángulo redondeado de 3 dp en
  verde institucional, etiqueta `nombre  87%` con fondo negro al 65 %, que se mete dentro de
  la caja si no cabe encima.
- `ScannerViewModel`: `StateFlow<List<Detection>>`, `StateFlow<FrameGeometry?>` y
  `StateFlow<PerformanceStats>`, más una `ViewModelProvider.Factory` manual que saca el
  `Detector` del `AppContainer`.
- `AppContainer`: `modelConfig`, `detector`, `detectorStatus` y `analysisExecutor`, todos
  perezosos.
- HUD de FPS, latencia media, tamaño de frame y rotación, solo con `BuildConfig.DEBUG`.
- **Se quitó el bloqueo de orientación** que puso F0. El criterio (b) de F2 exige probar en
  horizontal, y con la Activity bloqueada girar el teléfono no cambia nada. Al girar, la
  Activity se recrea, CameraX se reengancha y el estado sobrevive en el ViewModel.

**Verificación hecha: la matriz, con 6 pruebas unitarias.**

`BoxMapper.buildValues()` no toca ninguna clase de Android, así que se prueba en la JVM sin
Robolectric. Escenario: frame 1280×720, modelo 640, pantalla 1080×2400.

| Prueba | Resultado |
|---|---|
| Letterbox 16:9 → `scale` 0,5 · `padX` 0 · `padY` 140 | ✔ |
| (a) Vertical, trasera: caja de calibración en y 600-1800 px = 25 %-75 % exacto, x 202,5-877,5 px centrado | ✔ |
| (b) Horizontal, trasera: centrada en los dos ejes | ✔ |
| (c) Vertical, frontal: centrada, y en 25 %-75 % exacto | ✔ |
| El espejado frontal invierte X y no toca Y (`back.x + front.x == anchoVista`) | ✔ |
| Giro de 90°: la esquina superior izquierda del frame acaba en la superior derecha | ✔ |

`./gradlew clean testDebugUnitTest lint assembleDebug` → **BUILD SUCCESSFUL**, 6/6 pruebas,
0 errores y 0 avisos de lint en código propio.

**Verificación pendiente: las tres pruebas visuales.**

No hay teléfono ni emulador en este entorno, así que **(a), (b) y (c) no se han comprobado
en pantalla**. Están calculadas y probadas, no observadas. F2 no debe darse por cerrada
hasta que alguien las mire en un dispositivo real y anote el resultado aquí abajo.

| Prueba visual | Estado | Observado |
|---|---|---|
| (a) Vertical, cámara trasera | **PASA** | Caja en x 211-869 px, y 585-1755 px. Centro (540, 1170) sobre un centro de vista de (540, 1170) |
| (b) Horizontal, cámara trasera | **PASA** | Caja en x 585-1755 px, y 211-869 px. Centro (1170, 540) sobre un centro de vista de (1170, 540) |
| (c) Cámara frontal | **PASA** | Misma caja centrada con `rot 270`. La caja secundaria salta de arriba a abajo, como predice la cadena |

Medidas tomadas sobre las capturas de 2026-08-26, comparadas con el valor calculado a mano.
Coinciden dentro de 3 px. Detalle en la bitácora de ese día.

Para ayudar a comprobarlas, el overlay dibuja en depuración una cruz en el centro exacto de
la vista y marcas al 25 % y al 75 % de cada eje. El centro de la caja de calibración debe
caer sobre la cruz en los tres casos.

**Qué esperar en pantalla, y qué NO.** La caja de calibración queda perfectamente centrada,
pero **no ocupa la mitad de la pantalla en los dos ejes**: `FILL_CENTER` recorta el eje que
sobra. En un teléfono 20:9 en vertical sale al 25 %-75 % de alto y al 18,8 %-81,3 % de ancho.
Eso es correcto. El motivo, con los números, está en docs/DECISIONES.md D-007.

**Desvío respecto al plan de F2.** Las cajas del `StubDetector` se interpretan como
fracciones **del frame** y no del cuadrado del modelo. Con la lectura literal, la caja de
calibración se sale de la pantalla por ambos lados (x de −5,6 % a 105,6 %) y la segunda cae
dentro del relleno gris, fuera de la imagen. Se sigue lo que dice CLAUDE.md: *"una caja fija
en el 25 %-75 % del frame"*. Detalle y tabla comparativa en D-007.

**Qué quedó pendiente.**

- Las tres pruebas visuales de la tabla de arriba.
- Medir FPS reales en un teléfono de gama media. El HUD ya está; el objetivo de ≥10 FPS es
  de F7.
- El `StubDetector` simula 25 ms de latencia, así que los FPS que muestre el HUD ahora no
  dicen nada del modelo real.
- `Letterbox.letterbox()` está escrito y sin usar: lo estrena F3. Solo se usa por ahora
  `letterboxParams()`.


### 2026-08-26 — F3 — Inferencia real y pantalla de diagnóstico

**Qué se hizo.**

- `detection/YoloTfliteDetector.kt`: `Interpreter` crudo, modelo mapeado con
  `MappedByteBuffer` vía `assets.openFd` (funciona porque el `.tflite` va sin comprimir desde
  F0), `setNumThreads(4)`, delegado GPU opcional que cae a CPU sin crashear.
- `detection/YoloDecoder.kt`: `OutputSpec` deduce candidatos, clases y disposición de la forma
  real; decodificación de las dos disposiciones, descuantización, `cxcywh`→`xyxy` y NMS por
  clase.
- `detection/ModelDiagnostics.kt`: `TensorInfo`, `ModelDiagnostics` y la interfaz
  `DiagnosticsProvider`, que solo implementa el detector real.
- `detection/Letterbox.kt`: `letterbox()` sustituido por `LetterboxScaler`, que redibuja
  siempre sobre el mismo bitmap. Ver D-010.
- `DetectorFactory` construye el detector real; **toda** la selección sigue ahí y ningún otro
  archivo decide.
- `ui/diagnostics/DiagnosticsScreen.kt`, accesible desde el icono de la barra superior, con
  botón "Copiar diagnóstico".
- `di/PerformanceTracker.kt`: FPS y latencia pasan al `AppContainer` porque ahora los leen dos
  pantallas en rutas distintas.
- Banda superior "Modo demostración: modelo de detección no cargado" cuando falta el `.tflite`.
- `FrameAnalyzer` traza detecciones en el log una vez por segundo, solo en depuración.

**Prueba de punta a punta con un YOLOv8n COCO. HECHA, en un dispositivo real.**

Teléfono: **Samsung SM-A566E, Android 16, arm64-v8a**. Modelo de prueba: YOLOv8n COCO de 3,2 MB
con `labels.txt` de 80 clases.

Al arrancar, con el modelo en assets y `useStubDetector: false`:

```
I LabScan: Modelo cargado: entrada 1x640x640x3 FLOAT32, salida 1x84x8400 FLOAT32,
           80 clases, disposicion TRANSPOSED
I LabScan: Detector real activo con model.tflite
D LabScan: Camara enganchada, analisis=true
D LabScan: Sin detecciones (285 ms)
```

Como la cámara apunta a donde apunte el teléfono, la verificación de contenido se hizo con una
**prueba instrumentada** (`androidTest/.../YoloTfliteDetectorTest.kt`) sobre una fotografía
conocida, que es repetible:

```
I LabScanTest: Entrada: 1x640x640x3 FLOAT32 sin cuantizar
I LabScanTest: Salida:  1x84x8400 FLOAT32 sin cuantizar
I LabScanTest: Clases: labels=80 tensor=80 layout=TRANSPOSED
I LabScanTest: Detecciones: [person 91%, person 90%, person 90%, bus 84%]
I LabScanTest: Rango salida: 1.5011087E-8 .. 0.91176903
```

`./gradlew connectedDebugAndroidTest` → **2 pruebas en verde**. Detecta personas y un autobús
con puntajes coherentes, el máximo de la salida se acerca a 1 (señal de que la decodificación y
la normalización son correctas) y dos pasadas seguidas sobre la misma imagen dan lo mismo, que
es lo que demuestra que reutilizar los buffers no ensucia el resultado.

**Un hallazgo de la prueba.** El modelo COCO declara entrada y salida en `FLOAT32`, pero
`model_config.json` decía `"quantized": true`. El detector tomó el camino float32 por
introspección del tensor y funcionó a la primera. Si se hubiera fiado de la configuración,
habría escrito bytes int8 en un buffer float32. Decisión registrada en D-009.

**Latencia observada: 285 ms por frame** (unos 3,5 FPS) con float32 en CPU a 640×640. Está
lejos del objetivo de ≥10 FPS de F7. Con el modelo int8 de Mario debería bajar bastante; si no
basta, F7 tiene margen en el delegado GPU y en bajar `inputSize`.

**Estado final de los assets.** El modelo COCO se **borró** de `assets/`, `labels.txt` volvió a
las 4 clases de la UTEQ y `model_config.json` está otra vez en `useStubDetector: true`. La app
arranca en modo demostración.

**Verificación estática.** `./gradlew clean testDebugUnitTest lint assembleDebug` →
BUILD SUCCESSFUL. 14 pruebas unitarias en verde (6 de `BoxMapper`, 8 nuevas de `YoloDecoder`),
0 errores y 0 avisos de lint en código propio.

**Qué quedó pendiente.**

- Las tres pruebas visuales de alineación de F2 siguen sin mirarse en pantalla. Ahora sí hay un
  teléfono disponible, así que se pueden hacer cuando esté libre.
- El delegado GPU no se ha ejercitado: `useGpuDelegate` está en `false` y el modelo de prueba
  era float32. Hay que probarlo con el modelo int8 real, donde es habitual que la GPU lo
  rechace y haya que caer a CPU.
- `outputLayout: "STANDARD"` está implementado y cubierto por pruebas unitarias, pero nunca se
  ha ejecutado contra un modelo real con esa disposición.
- Rendimiento: 285 ms por frame es demasiado. Es trabajo de F7.


### 2026-08-26 — F4 — Selección de detección y ficha técnica

**Qué se hizo.**

- `data/remote/RagDtos.kt`: `EquipmentDto` y `SourceDto`, `@Serializable`, que espejan campo a
  campo `GET /api/equipment/{classId}`. **Un solo modelo para los dos orígenes**: el catálogo
  local son respuestas del contrato guardadas en el APK, así que cuando F5 conecte el backend
  la ficha no cambia, solo cambia de dónde viene el DTO.
- `data/local/EquipmentCatalog.kt`: lee `assets/catalog.json` en `Dispatchers.IO`, cachea en
  memoria y protege la carga con un `Mutex` para que varios toques seguidos no disparen varias
  lecturas. Si la clase no está en el catálogo devuelve una ficha mínima con el `classId`
  formateado (`camara_electroforesis` → `Camara electroforesis`) y `fromCatalog = false`, que la
  interfaz traduce en el aviso "Ficha no disponible". Nunca devuelve `null` ni lanza.
- `ui/scanner/DetectionOverlay.kt`: `pointerInput` + `detectTapGestures`. La matriz se
  **recalcula** en el gesto en lugar de compartir estado con la fase de dibujo; son unas pocas
  multiplicaciones y ocurre solo al tocar, mientras que compartir estado mutable entre gesto y
  dibujo es una carrera segura. Con cajas solapadas gana la de mayor `score`; un toque fuera de
  todas devuelve `null` y cierra la ficha.
- Resaltado: la caja elegida pasa de 3 dp verde a 5 dp ámbar (`SelectionAmber`, nuevo en
  `ui/theme/Color.kt`) con un velo interior al 15 %; las demás bajan al 35 % de opacidad.
- **Congelado**: mientras `selectedDetection` no es `null`, `onFrameAnalyzed` deja de publicar
  detecciones y geometría. La cámara y la inferencia siguen corriendo y el `PerformanceTracker`
  sigue midiendo: lo único que se detiene es lo que se dibuja, para que la caja no se le escape
  de la pantalla al estudiante mientras lee.
- `ui/sheet/EquipmentSheet.kt`: `ModalBottomSheet` con `skipPartiallyExpanded = false`, para que
  la cámara y la caja resaltada sigan viéndose detrás. Encabezado con nombre y chip de
  confianza, descripción, seis secciones colapsables, pie de fuentes y los dos botones
  deshabilitados con su explicación.
- **Toda sección con texto o lista vacía se oculta sola.** El contrato permite campos vacíos y
  un título sin contenido debajo es peor que no mostrar la sección.
- Accesibilidad: `contentDescription` en todos los iconos; los decorativos (viñetas, números de
  paso, iconos dentro de botones con texto) van con `clearAndSetSemantics {}` o
  `contentDescription = null` para no duplicar la lectura. Las cabeceras colapsables tienen la
  fila entera como zona de toque, con `heightIn(min = 48.dp)`.

**Verificación.**

`./gradlew clean testDebugUnitTest lint installDebug` → BUILD SUCCESSFUL. **18 pruebas
unitarias** en verde (6 de `BoxMapper`, 8 de `YoloDecoder`, 4 nuevas de contrato del catálogo).
0 errores y 0 avisos de lint en código propio.

Las 4 pruebas nuevas (`CatalogJsonTest`) leen el `catalog.json` real del módulo y lo
deserializan **sin** `ignoreUnknownKeys`, a propósito: así un campo mal escrito rompe la
compilación. En la app ese error sería silencioso, porque el campo se ignoraría y la sección
aparecería vacía sin explicación. También comprueban que cada clase de `labels.txt` tiene ficha.

**Qué quedó pendiente.**

- **Sin probar en pantalla.** El APK está instalado en el SM-A566E, pero el teléfono estaba en
  uso y no lo lancé para no interrumpir. Falta comprobar a ojo: que la ficha sube desde abajo a
  media altura, que la caja se queda quieta y resaltada detrás, y que deslizar hacia abajo
  reanuda la detección.
- Se acumulan tres verificaciones visuales pendientes: las de alineación de F2 y esta.
- Los botones "Escuchar" y "Preguntar al asistente" están deshabilitados: F6 y F5 los activan.
- La ficha mínima de "Ficha no disponible" no se ha visto nunca, porque las 4 clases de
  `labels.txt` sí están en el catálogo. Se puede forzar borrando una entrada de `catalog.json`.


### 2026-08-26 — Verificación visual en dispositivo (cierra F2 y parte de F4)

Teléfono **Samsung SM-A566E, Android 16**, pantalla 1080×2340, frame de análisis 1280×720.
Medidas tomadas sobre capturas de pantalla y comparadas con el valor calculado a mano.

**Las tres pruebas de alineación de F2, con `StubDetector`. PASAN.**

| # | Situación | Predicho | Medido | Δ |
|---|---|---|---|---|
| a | Vertical, trasera, `rot 90` | x 211–869 · y 585–1755 | x 211–870 · y 588–1749 | ≤3 px |
| b | Horizontal, trasera, `rot 0` | x 585–1755 · y 211–869 | x 581–1757 · y 211–869 | ≤4 px |
| c | Vertical, frontal, `rot 270` | x 211–869 · y 585–1755 | x 211–872 · y 585–1749 | ≤3 px |

En los tres casos el centro de la caja de calibración cae sobre la cruz que el overlay dibuja
en el centro exacto de la vista. La caja secundaria aparece siempre donde predice la cadena de
transformaciones: arriba a la izquierda con la trasera en vertical, abajo a la izquierda en
horizontal, y abajo a la izquierda con la frontal.

**Prueba adicional con objetos reales.** Las cajas del stub están fijas en el frame, así que
comprueban el centrado y el giro pero no demuestran que una caja caiga sobre el objeto que la
provocó. Para cerrar eso se volvió a instalar el YOLOv8n COCO un momento y se apuntó la cámara
**frontal** a una persona:

```
D LabScan: Detecciones (180 ms): person 73%
D LabScan: Detecciones (188 ms): person 90%
```

La caja `person 90%` envuelve a la persona con el borde superior justo sobre la cabeza y el
derecho siguiendo su contorno. Es la verificación que faltaba: **con la cámara frontal, que va
espejada, la caja cae sobre el objeto real.** Si el espejado estuviera invertido, ese borde
derecho habría aparecido reflejado, cortando la cara por el otro lado. La cadena de
`BoxMapper` queda confirmada de punta a punta, giro y espejado incluidos.

**Otras cosas confirmadas de paso.**

- La banda "Modo demostración: modelo de detección no cargado" aparece sin modelo y desaparece
  al cargarlo. Requisito 6 de F3, verificado en los dos sentidos.
- Rendimiento con el stub: **35–48 FPS** con 28–34 ms de latencia. Con el YOLOv8n COCO float32
  en CPU: **10–11 FPS con 173–193 ms**, bastante mejor que los 285 ms medidos en F3, ya con el
  objetivo de ≥10 FPS de F7 al alcance.
- Zonas de toque de la barra superior: 135×135 px sobre una densidad de 2,8125, o sea **48 dp
  exactos**. Requisito 5 de F4, comprobado con `uiautomator dump`.
- El resaltado de selección de F4 se vio funcionando: caja ámbar de 5 dp y las demás atenuadas.

**Defecto cosmético encontrado.** La etiqueta de una caja pegada al borde superior se dibuja
sobre la barra de estado y se solapa con el reloj. El overlay respeta los límites del `Canvas`
pero no los de las barras del sistema. No afecta al mapeo. Pendiente para F7.

**Estado de los assets al terminar.** El modelo COCO se borró otra vez, `labels.txt` tiene las 4
clases de la UTEQ y `model_config.json` está en `useStubDetector: true`. La app instalada en el
teléfono es esa.

### 2026-08-27 — F5: Retrofit, RagRepository y backend simulado

**Qué quedó hecho.** La app habla el contrato completo de `docs/CONTRATO_API.md` y funciona hoy
contra un mock local, sin que exista el backend.

- `data/remote/dto/` con los DTO de los tres endpoints y del sobre de error estándar.
  `EquipmentDto` y `SourceDto` se mudaron aquí desde `RagDtos.kt`. Ver D-011.
- `LabScanApi`: los tres endpoints en `suspend`, rutas relativas a `BASE_URL`.
- `RagRepository`: `health()`, `equipment()` con caída al catálogo local, `chat()` con el
  historial truncado a 6 turnos. Ninguna excepción cruda sale de aquí.
- `RagError`: seis casos cerrados, cada uno con su texto en español y si se puede reintentar.
- `MockInterceptor` + `assets/mock/`: siete JSON. Ver D-013.
- `ConnectivityObserver`: estado de red como `Flow`, exige `NET_CAPABILITY_VALIDATED`.
- `network_security_config.xml` solo en la variante debug. Ver D-012.
- Ficha conectada: banda de conexión en el scanner, indicador de carga y etiqueta
  "sin conexión" dentro de la hoja cuando el dato salió del APK.

**Comportamiento ante cada fallo de red.**

| Situación | Qué pasa por dentro | Qué ve el estudiante |
|---|---|---|
| Sin red validada | `ConnectivityObserver` corta antes de salir | Banda "Sin conexión…", ficha del catálogo con la etiqueta "sin conexión" |
| Timeout (>15 s) | `SocketTimeoutException` → `RagError.Timeout` | Ficha del catálogo con la etiqueta "sin conexión" |
| 404 en `/api/equipment` | `EQUIPMENT_NOT_FOUND` → `RagError.NotFound` | Ficha del catálogo con la etiqueta "sin conexión" |
| Clase que no está ni en el backend ni en `catalog.json` | ficha mínima con el `classId` | "Ficha no disponible. Este equipo aún no está en el catálogo" |
| 5xx en `/api/equipment` | `RagError.ServerError` | Ficha del catálogo con la etiqueta "sin conexión" |
| 5xx en `/api/chat` | `RagError.ServerError`, `retryable = true` | (lo pinta F6) "El servidor no pudo atender la consulta" con botón Reintentar |
| `LLM_UNAVAILABLE` | gana el código del cuerpo sobre el 503 | Banda "El asistente no está disponible en este momento" |
| `/api/health` no responde `ok` | `backendHealthy = false` | Banda "El asistente no está disponible en este momento", la ficha sigue funcionando |
| `hasSufficientContext: false` | el repositorio **vacía** `sources` | (lo pinta F6) respuesta en ámbar, sin fuentes |

La ficha **nunca** falla: el respaldo local es total. Por eso `equipment()` devuelve hoy siempre
`Result.success`, y está documentado en el propio código por qué se conserva el `Result`.

**Verificado en el dispositivo (SM-A566E, teléfono bloqueado).** `MockBackendTest`, 5 pruebas
instrumentadas, todas en verde. No es una simulación en la JVM: el JSON atraviesa OkHttp,
el interceptor, Retrofit y el convertidor de kotlinx.serialization, igual que lo hará con el
backend real.

- `/api/health` responde `ok` (visto también en logcat al arrancar la app).
- `microscopio_binocular` llega **por red** con sus 2 fuentes; el catálogo local solo tiene 1,
  así que la diferencia demuestra de dónde salió el dato.
- `camara_electroforesis` da 404 y cae al catálogo local con `reason = NotFound`.
- Una clase inventada devuelve la ficha mínima sin romper nada.
- Dos llamadas seguidas a `/api/chat` devuelven `hasSufficientContext` true y luego false, con
  las fuentes vacías en el segundo caso.

**Defecto anterior corregido de paso.** Seis textos de `strings.xml` usaban `%1`, `%2` sin el
sufijo de tipo (`%1$d`, `%1$s`). `String.format` lanza `UnknownFormatConversionException` con
esa forma, comprobado ejecutándolo: **la ficha técnica de F4 habría reventado al abrirse**, y
tres filas de la pantalla de Diagnóstico también. Nadie lo había visto porque la hoja nunca
llegó a abrirse en pantalla. Corregidos los seis y verificados contra sus llamadas.

**Otro defecto corregido.** `ScannerViewModel.onFrameAnalyzed` llamaba dos veces a
`performanceTracker.record` por frame, así que los FPS del HUD y del Diagnóstico salían al
doble mientras no hubiera ficha abierta. Las cifras de rendimiento anotadas en la bitácora del
2026-08-26 están afectadas por esto y hay que volver a medirlas en F7.

**Dependencias nuevas.** Ninguna. Retrofit, OkHttp, el interceptor de registro, el convertidor y
kotlinx-serialization ya estaban declarados desde F0. Se corrigió el paquete de importación del
convertidor: es `com.jakewharton.retrofit2.converter.kotlinx.serialization`, no
`retrofit2.converter.…`.

**Permiso nuevo.** `ACCESS_NETWORK_STATE`. No da acceso a nada; sin él,
`ConnectivityObserver` no puede avisar de que no hay red y cada consulta gastaría los 15 s de
timeout completos.

### 2026-08-27 (tarde) — Verificación visual de F5, y la ficha de F4 abierta por fin

Con el teléfono desbloqueado se recorrieron en pantalla los cuatro caminos de red. **Es la
primera vez en el proyecto que la hoja de la ficha se ve abierta.**

**Camino con backend (mock activo).** Toque sobre la caja de calibración →
`Seleccion: microscopio_binocular` → `MOCK /api/equipment/microscopio_binocular -> 200` →
`Ficha lista: Microscopio binocular (NETWORK)`. La hoja se abre a media altura con la caja
ámbar visible detrás. Se confirmó que el contenido viene **del servidor y no del APK** por tres
señales que solo existen en el mock: la frase "El aumento total es el producto del aumento del
ocular por el del objetivo en uso", el componente "Oculares 10x" (en el catálogo es "Oculares"),
y **dos** fuentes citadas en lugar de una.

**Camino sin backend.** Se puso `USE_MOCK_API` en `false` un momento, con lo que el teléfono
real intenta alcanzar `10.0.2.2` de verdad y no llega:

```
09:08:20.007 D Seleccion: microscopio_binocular
09:08:23.325 W El backend no responde: Timeout
09:08:30.028 W Ficha de 'microscopio_binocular' desde el catalogo local: Timeout
09:08:30.037 I Catalogo cargado: 4 fichas
09:08:30.038 D Ficha lista: Microscopio binocular (CACHE)
```

Diez segundos exactos entre el toque y la caída: es el `connectTimeout` configurado, medido
sin querer. En pantalla, los tres elementos nuevos:

- **Indicador de carga**, visible durante esos 10 s en el centro de la vista, sobre la caja tocada.
- **Banda de conexión** "El asistente no está disponible en este momento", apilada debajo de la
  banda del detector de prueba sin solaparse.
- **Etiqueta "Sin conexión: ficha guardada en el teléfono"** bajo el título de la hoja, y el
  contenido pasa a ser el del catálogo: la descripción termina en "a simple vista" y el
  componente vuelve a ser "Oculares" a secas.

`USE_MOCK_API` quedó restaurado en `true` y el APK instalado es ese.

**Dos defectos de F4 encontrados y corregidos.**

1. **La hoja no tenía scroll.** El `Column` dentro del `ModalBottomSheet` no llevaba
   `verticalScroll`, así que la ficha se cortaba por donde terminara la pantalla: riesgos,
   prácticas, **fuentes** y los dos botones de acción eran inalcanzables. Incumplía la regla 6
   de CLAUDE.md, porque las fuentes existían pero no había forma de llegar a ellas. Corregido y
   verificado: ahora se llega hasta "Fuentes consultadas → Guía de prácticas de Biotecnología
   UTEQ, p. 12 / Manual de usuario Olympus CX23, p. 8".
2. Los seis textos de formato rotos, ya descritos en la entrada anterior. Con la hoja abierta se
   confirma que "92% de confianza" y "…, p. 12" se pintan bien.

**Trazas nuevas que se quedan.** `Seleccion:`, `Ficha lista: … (NETWORK|CACHE)` y
`Ficha cerrada`. Se añadieron para depurar esto y se conservan: dicen de un vistazo, en
`adb logcat -s LabScan`, si una ficha vino del servidor o del teléfono.

**FPS reales tras corregir el conteo doble.** 29,6–29,9 FPS con 28–30 ms de latencia usando el
stub, es decir el ritmo de la cámara. La cifra de "35–48 FPS" de la bitácora del 2026-08-26
estaba inflada por el `record` duplicado; esta es la buena.

**Lo único que queda sin ver en pantalla,** y por qué:

- **Banda "Sin conexión"** (estado `OFFLINE`, sin red en absoluto). `adb` está conectado por
  wifi, así que apagarlo cortaría la sesión de depuración. Usa el mismo composable que la banda
  ya verificada, solo cambia el texto. Para comprobarlo hace falta conectar el teléfono por USB.
- **"Ficha no disponible"** para una clase que no está ni en el backend ni en `catalog.json`.
  El `StubDetector` solo emite las dos primeras clases de `labels.txt`, las dos con ficha, así
  que no hay forma de provocarlo desde la interfaz. Sí está cubierto por
  `MockBackendTest.una_clase_que_no_esta_en_ningun_lado_devuelve_ficha_minima`.



### 2026-08-27 (noche) — F6: voz y chat con el asistente

**Qué quedó hecho.** El estudiante puede seleccionar un equipo, preguntar hablando y escuchar la
respuesta con su fuente en pantalla.

- `voice/TtsManager.kt`: inicialización asíncrona, es-EC → es-ES → idioma del sistema,
  `speak`/`stop`/`shutdown` y `StateFlow<Boolean>` de "está hablando".
- `voice/SttManager.kt`: `SpeechRecognizer` con `FREE_FORM` y es-EC, estados como `StateFlow`.
- `ui/chat/ChatScreen.kt` + `ChatBubbles.kt` + `ChatViewModel.kt`.
- `ui/settings/SettingsScreen.kt` y `data/local/SettingsStore.kt` sobre DataStore.
- `ui/common/RuntimePermission.kt`: el permiso de F1 extraído y compartido. Ver D-017.
- Los dos botones de la ficha de F4 quedaron activos.

**Flujo completo de estados de voz.**

```
DICTADO (SttManager)

  Idle ──start()──▶ Listening ──primera palabra──▶ PartialResult(texto)
   ▲                   │                                  │
   │                   │ (el parcial se pinta en el campo de texto)
   │                   │                                  │
   │                   └────────── stop() ────────────────┤
   │                          (soltar el botón)           ▼
   │                                                   Result(texto)
   │                                                      │
   │                                          ChatViewModel.send(texto)
   │                                                      │
   └──────────────────── consumed() ◀─────────────────────┘
   ▲
   └── Error(kind) ──▶ snackbar ──▶ consumed()
        NO_MATCH · NETWORK · PERMISSION · BUSY · UNAVAILABLE · UNKNOWN

LECTURA (TtsManager)

  isReady=false ──onInit(SUCCESS)──▶ isReady=true
       │                                  │
       │ speak() se guarda en pendingText │ speak() ──▶ isSpeaking=true
       └──────────────────────────────────┘                 │
                                              onDone/onStop/onError
                                                            ▼
                                                     isSpeaking=false
```

**Los tres cortes obligatorios del TTS,** exigidos por la restricción de F6, están cada uno en
su sitio y no como efecto colateral de la navegación: `onDispose` del chat y del escáner,
`ChatViewModel.send()` antes de enviar, y el micrófono antes de abrirse.

**Lo que nunca se lee en voz alta.** La lista de fuentes y los mensajes de error de red. Además,
el botón "Escuchar" de la ficha lee solo la descripción corta y el procedimiento: ni riesgos ni
EPP ni fuentes. Hay una prueba que lo afirma con datos centinela
(`SpeechTextTest.la ficha se lee como descripcion mas procedimiento…`), así que si alguien
amplía `toSpokenSummary()` sin pensarlo, falla la compilación de las pruebas.

**Sí se lee** la respuesta marcada con `hasSufficientContext: false`. Es deliberado: ese texto
dice "consulte al docente", que es justo lo que más le conviene oír a alguien con las manos
ocupadas. Lo que no se lee de ella son las fuentes, que además vienen vacías por contrato.

**Verificado en el dispositivo (SM-A566E, teléfono bloqueado).** `VoiceStackTest`, 5 pruebas
instrumentadas en verde. Dos datos que solo se podían saber en el teléfono:

```
I LabScan : Voz lista, idioma: es-EC
I System.out: Reconocimiento de voz disponible en este dispositivo: true
```

El motor tiene **español de Ecuador**, así que ni siquiera hizo falta el respaldo a es-ES. Y hay
reconocimiento de voz, con lo que el camino de degradación (micrófono deshabilitado con
explicación) no se activa en este equipo. También se comprobó que la lectura automática viene
activada de fábrica y que el interruptor persiste en DataStore.

**Defecto encontrado por una prueba.** `toSpokenSummary()` devolvía `"."` para una ficha mínima,
lo que habría arrancado el motor de voz para no decir nada. Ahora devuelve cadena vacía y
`speak()` ni lo intenta.

**Cómo probar el caso `hasSufficientContext: false` con el mock.** `MockInterceptor` **alterna**
por número de petición a `/api/chat`: la 1.ª, la 3.ª, la 5.ª… responden `chat.json`
(`true`, con dos fuentes); la 2.ª, la 4.ª… responden `chat_sin_contexto.json` (`false`, sin
fuentes). Así que basta con **hacer dos preguntas seguidas**: la segunda sale en ámbar, con el
título "Información insuficiente", sin chips de fuente, y se lee en voz alta igual. El contador
vive en la instancia del interceptor, así que se reinicia al reiniciar la app: la primera
pregunta de cada sesión siempre se responde bien.

**Recorrido visual, en el SM-A566E.** Se hizo el camino entero de la actividad, salvo el dictado:

1. **Ficha → "Escuchar".** El botón cambia a "Detener" y en logcat aparece
   `com.google.android.tts is now playing`. Al salir de la ficha, la voz calla sola.
2. **Ficha → "Preguntar al asistente".** Navega al chat con el encabezado verde
   "Consultando sobre: Microscopio binocular" y su botón de quitar contexto. Se ven los cuatro
   chips de preguntas frecuentes.
3. **Primera pregunta** (chip "¿Cómo se enciende?"): burbuja verde del usuario a la derecha,
   respuesta gris a la izquierda, **dos chips de fuente** debajo con desplazamiento horizontal
   ("Manual de usuario Olympus CX23, p. 8" y la Guía UTEQ), y el altavoz de la burbuja
   convertido en cuadrado de "detener" mientras habla. Vuelve a altavoz al terminar.
4. **Segunda pregunta**, escrita: el mock alterna a `chat_sin_contexto.json` y la burbuja sale
   **en ámbar**, con el icono de advertencia, el título "Información insuficiente" y **sin
   ningún chip de fuente**, junto a la primera que sí los tiene. Es la comparación lado a lado
   que pedía el contrato.
5. **Lectura automática de las dos respuestas**, confirmada en el registro de audio del sistema:
   `CONTENT_TYPE_SPEECH` arranca a las 09:32:56 y a las 09:33:16, una vez por respuesta. La
   ámbar **también** se lee, que es lo previsto.
6. **Teclado**: `imePadding` levanta la barra de entrada por encima del teclado sin taparla.
7. **Ajustes**: el interruptor "Leer las respuestas en voz alta" aparece **activado de fábrica**.

**Lo único que no se pudo probar, y por qué.** El gesto de mantener pulsado el micrófono. Ni
`input swipe` con origen y destino iguales ni `input motionevent DOWN/UP` llegan al
`detectTapGestures` de Compose: cada invocación de `adb shell input` es una sesión de
inyección distinta, así que el DOWN se cancela antes de que llegue el UP. Y aunque llegara, **no
hay forma de inyectar audio real por adb**, con lo que el reconocedor no tendría nada que
transcribir. El dictado tiene que probarlo una persona: abrir el chat, mantener pulsado el
botón verde, decir "cómo la enciendo" y soltar.

**Nota de la sesión.** `connectedDebugAndroidTest` desinstala la app al terminar, así que la
siguiente instalación pide otra vez los permisos. Eso sirvió de verificación no planeada del
refactor de D-017: la pantalla explicativa de la cámara y la petición automática al entrar se
comportan igual que en F1.

### 2026-08-27 (cierre) — F7: rendimiento, estabilidad y empaquetado

Última fase. **Las ocho están terminadas.**

#### Rendimiento

Todo medido en un **Samsung SM-A566E** con un YOLOv8n de COCO float32 a 640, que es el sustituto
mientras no llega el modelo de la UTEQ. Los números de banco salen de `DetectorBenchmarkTest`:
mismo frame, 30 repeticiones, sin cámara ni interfaz de por medio, que es lo único que hace
comparables un antes y un después.

| Medida | Antes | Después | Cambio |
|---|---|---|---|
| Latencia total por frame (banco) | 188 ms | **107 ms** | −43 % |
| — de la cual, inferencia | 102 ms | 100 ms | sin cambio |
| — de la cual, preparación del frame | **86 ms** | **6 ms** | −93 % |
| FPS teóricos (banco) | 5,3 | **9,3** | +75 % |
| FPS reales en el teléfono | ~5,5 | **8,6** | +56 % |
| Latencia real en el teléfono | 183 ms | **113 ms** | −38 % |
| Resolución de análisis | 1280×720 | 640×360 | −75 % de píxeles |
| Bytes reservados por frame | 10 922 | **9 833** | ya estaba bien |
| APK release | (no existía) | 25,8 MB | — |

La cifra de "antes" en el teléfono viene de la bitácora de F3 (173–193 ms medidos en este mismo
equipo con el mismo modelo). Sus FPS se recalculan a 5,5 porque el valor anotado entonces estaba
inflado por el conteo doble que se corrigió en F5.

**Dónde estaba de verdad el problema.** No en la inferencia. De los 188 ms, **86 se iban copiando
buffers elemento a elemento**: 1 228 800 llamadas a `putFloat` y 705 600 a `get` por frame.
Sustituirlas por dos copias en bloque bajó eso a 6 ms. Es toda la mejora. Queda como D-019, junto
con el motivo por el que `PerformanceStats` ahora separa las dos latencias: para que nadie repita
la suposición.

**Lo que se probó y NO se quedó:**

- **Delegado GPU.** Estaba roto desde F3 (`NoClassDefFoundError`, faltaba el artefacto de API). Se
  arregló, y aun así pierde: inferencia 73 ms pero total 120 ms, contra 107 ms en CPU. Ver D-018.
- **6 y 8 hilos.** 4 hilos = 100 ms, 6 = 115 ms, 8 = 106 ms. El comentario original de F3 tenía
  razón: subir de 4 le quita núcleos a la cámara y a la composición de pantalla.

**Lo que sí se aplicó,** en el orden que pedía la actividad:

1. Resolución de `ImageAnalysis` limitada a 960×540; el dispositivo ofreció **640×360**, que sigue
   siendo 16:9 y por encima del lado del letterbox. La detección no se degradó.
2. `inputSize` de 416: la app ya lo admite sin tocar código, porque lo lee del tensor. **No se pudo
   verificar** por no disponer de un modelo a 416; queda proyectado en `docs/INTEGRACION_MODELO.md`
   con las cuentas hechas y marcado como estimación.
3. Salto adaptativo de frames, con histéresis entre 250 ms y 150 ms. En este equipo **no se activa
   nunca**: es un seguro térmico, no un acelerador, y así está escrito en el código.
4. **Suavizado de las cajas** entre frames (D-020). Es lo que más se nota a ojo y en video: antes la
   caja se quedaba quieta seis fotogramas y saltaba, y encima temblaba.

**Sin reservas por frame:** 9 833 bytes, medidos con `art.gc.bytes-allocated`. El tope que vigila
la prueba son 256 KB, y un bitmap de 640×640 solo sería 1,6 MB. No hay nada grande en el bucle.

#### Estabilidad

| Prueba | Resultado |
|---|---|
| 10 rotaciones seguidas | Sin fallos. Java heap 17 684 → 17 776 KB, plano |
| Ficha abierta + rotar | Sin fallos. Cámara suelta y reengancha, detección continúa |
| 10 ciclos de minimizar/restaurar **con cámara** | Sin fallos. Native heap 58 → 197 MB |
| 10 ciclos de minimizar/restaurar **sin cámara** (en Ajustes) | Java heap 31 588 → 31 632 KB, **plano** |
| `send-trim-memory RUNNING_CRITICAL` | Native 178 → 87 MB: recuperable |

**Conclusión, que es lo que importa:** el crecimiento con la cámara viene del pool de buffers de
CameraX al parar y arrancar, no del código del proyecto. La prueba de control lo aísla: con la
cámara suelta, diez ciclos dejan la memoria exactamente igual. Y el sistema lo recupera bajo
presión. Ni un `FATAL EXCEPTION` ni un ANR en toda la sesión.

#### Release

- `minifyEnabled` y `shrinkResources` activados, con `app/proguard-rules.pro` documentado regla
  por regla.
- Firmado con `keystore/labscan-demo.jks`, que va en el repositorio a propósito (D-021).
- `apksigner verify` → **Verifies**, esquema v2.
- **Verificado tras la ofuscación**, que es lo que de verdad había que comprobar: se instaló el APK
  release con el modelo COCO y el log dijo `Modelo cargado: entrada 1x640x640x3 FLOAT32 …` y
  `Detector real activo`. Ese segundo mensaje es la prueba fina de que kotlinx.serialization
  sobrevivió a R8: `ModelConfig` tiene `useStubDetector = true` por defecto, así que si el
  serializador se hubiera roto, la app habría caído al detector de prueba en silencio.
- APK: **25,8 MB** release contra 46,2 MB debug. Los JSON del backend simulado ya no viajan en el
  release (D-022) — y el primer intento de sacarlos, con `packaging { resources { excludes } }`,
  no funcionaba y compilaba igual.

#### Lo que se agregó a la interfaz

Ajustes pasó de un interruptor a cinco cosas: umbral de confianza (0,25–0,75, aplicado **en
caliente** sin recargar el modelo), cámara al abrir, lectura automática, URL del backend editable
**sin recompilar**, acceso a Diagnóstico y "Acerca de" con los integrantes, la universidad y el
laboratorio. Icono adaptativo propio y splash con la API oficial.

#### Documentación de entrega

`README.md`, `docs/EVIDENCIAS.md` (checklist con el estado real de cada ítem),
`docs/GUION_VIDEO.md` (4 minutos, diez tomas obligatorias) y `docs/capturas/` con ocho imágenes
del teléfono.

#### Lo que queda pendiente, y de qué depende

Nada de esto necesita tocar código:

- **`model.tflite` de la UTEQ** — Mario. Copiarlo a `assets/` y poner `useStubDetector` en `false`.
- **Backend RAG desplegado** — Mario. Basta escribir la URL en Ajustes.
- **Video de demostración** — necesita el modelo y el laboratorio. El guion está listo.
- **Autorización para fotografiar el laboratorio** — bloquea el dataset y las evidencias
  fotográficas.
- **Revisar `catalog.json` contra los manuales reales** — hoy es contenido de ejemplo.
- **`inputSize` de 416 medido de verdad** — cuando exista un export a 416.

## Supuestos vigentes

- El laboratorio asignado y las clases finales aún no están confirmados. `labels.txt` y
  `catalog.json` usan clases de ejemplo hasta que se defina.
- Se asume teléfono de gama media con Android 10+ para las pruebas de rendimiento.
- Las fichas de `catalog.json` son contenido de ejemplo redactado para poder construir y
  probar la interfaz. Antes de la entrega deben revisarse contra los manuales reales de
  los equipos y contra la guía de prácticas de la UTEQ.
- El entorno de compilación es AGP 9.3.2 con Gradle 9.5 y el JDK 25 de Android Studio,
  compilando a bytecode Java 17. Si otro integrante clona el repositorio con un Android
  Studio más antiguo, tendrá que actualizarlo: AGP 9 no es compatible hacia atrás.

### 2026-08-27 (tarde) — Verificacion del APK de release en el telefono

Conexion por **depuracion inalambrica** (`adb-R5CY80TJLNE-S0soFg._adb-tls-connect._tcp`),
Samsung SM-A566E, Android 16. El cable no daba datos: Windows no veia el VID 04E8 de Samsung
por ningun puerto, asi que la sesion se hizo entera por Wi-Fi.

**Pruebas instrumentadas.** `connectedDebugAndroidTest`: **14 pruebas, 0 fallos, 0 errores,
2 omitidas**. Las omitidas son las de `DetectorBenchmarkTest`, que su `assumeTrue` salta al no
haber `model.tflite` en modo demostracion. Esto confirma ejecutando lo que antes solo se habia
contado leyendo anotaciones `@Test`. De paso quedan corregidas dos cifras infladas en la
documentacion: eran 47 y 17, son **39 en la JVM y 14 instrumentadas**.

**Recorrido completo del APK de release ofuscado.** Instalado y recorrido pantalla por pantalla:

| Pantalla | Resultado |
|---|---|
| Escaner | Banda ambar de modo demostracion, banda de asistente no disponible, dos cajas del stub con etiquetas en espanol de `labels.txt` |
| Ajustes | Umbral 0.45, camara Trasera/Frontal, lectura automatica activada, campo de servidor **vacio** con el URL de fabrica como *placeholder* (`SettingsScreen.kt:176`), y "Acerca de" con universidad, laboratorio, integrantes y version |
| Diagnostico | Detector activo, motivo, y **24,6 FPS · 26 ms total · 25 ms inferencia · 1 ms preparacion** |
| Ficha | "Sin conexion: ficha guardada en el telefono", desplazamiento hasta **Fuentes consultadas** y los dos botones |
| Voz | "Escuchar" reproduce: `com.google.android.tts is now playing`, ~2 s |
| Chat | Encabezado con el equipo, cuatro preguntas sugeridas, y ante fallo de red burbuja roja "No hay conexion a internet" con Descartar/Reintentar |

**Tres pruebas independientes de que R8 no rompio la serializacion**, que es lo que mas se temia:
`ModelConfig` deserializa (el motivo del stub sale con el valor leido del JSON), `catalog.json`
deserializa (la ficha completa se dibuja desde el catalogo local) y el mapeo de `RagError`
sobrevive (el fallo de red llega a la UI como mensaje traducido, no como excepcion cruda).

**El panel de rendimiento confirma el arreglo del doble `record()`.** Con `StubDetector`, cuya
latencia simulada es de 25 ms, el panel reporta 25 ms de inferencia y 24,6 FPS. Con el bug
anterior habria reportado aproximadamente el doble.

**Memoria: descartada la fuga, esta vez de forma concluyente.** El heap nativo por ciclos de
minimizar y restaurar con la camara: 42 MB al arrancar, **103 MB a los 5 ciclos, 63 MB a los 10,
80 MB a los 15**. No crece de forma monotona: oscila entre ~60 y ~105 MB y baja sola. El heap
Java se queda plano entre 5 y 7 MB. Una fuga seria monotona; esto es el pool de buffers de
CameraX reciclandose. Es mejor evidencia que el `send-trim-memory` de la manana, que en esta
sesion solo devolvio 3 MB y por si solo no habria decidido nada.

**Estabilidad:** 8 rotaciones y 15 ciclos de minimizar/restaurar sin un solo `FATAL EXCEPTION`
ni ANR.

**Contenido del APK de entrega:** `unzip -l` sobre el release confirma que en `assets/` solo
viajan `catalog.json`, `labels.txt` y `model_config.json`, ademas del perfil de arranque.
El backend simulado no esta. Firma: `apksigner verify` → **Verifies**, esquema v2.

**Observacion abierta, no corregida.** Con un URL de servidor que no resuelve, `toRagError()`
mapea `UnknownHostException` a `NoConnection` y la app dice "No hay conexion a internet" aunque
el telefono si tenga red. Era irrelevante mientras el URL estaba fijo, pero desde F7 el usuario
puede escribirlo en Ajustes, asi que un error de tipeo ahora se reporta como un problema de red.
Se deja anotado y sin tocar: F7 es fase de estabilizacion, no de cambios de comportamiento.

---

## F8 — Conversacion por voz manos libres — 2026-08-27

Modo de llamada: el estudiante entra, habla, escucha y vuelve a hablar sin tocar la pantalla.
La arquitectura es una **cascada**, no una API de voz a voz: reconocimiento en el dispositivo →
texto → backend → texto → sintesis en el dispositivo. Todo el audio se queda en el telefono; lo
unico que sale es la misma cadena de texto que enviaria el chat escrito, mas la bandera `voiceMode`.

### Archivos

Nuevos: `voice/ContinuousSttManager.kt`, `voice/AudioFocusController.kt`, `voice/EchoControl.kt`,
`ui/voice/VoiceState.kt`, `ui/voice/VoiceCallViewModel.kt`, `ui/voice/VoiceCallScreen.kt`,
`ui/voice/VoiceOrb.kt`, `test/voice/VoiceCallLogicTest.kt`, y los mock `chat_voz.json` y
`chat_voz_sin_contexto.json`.

Modificados: `voice/TtsManager.kt` (foco de audio, lectura por frases, aviso de fin),
`dto/ChatDtos.kt` y `RagRepository` (`voiceMode`), `ui/chat/ChatViewModel.kt` y `ChatScreen.kt`
(modo de voz y boton de auriculares), `ui/sheet/EquipmentSheet.kt` ("Hablar con el asistente"),
`ui/scanner/ScannerScreen.kt`, `MainActivity.kt` (subgrafo de conversacion),
`di/AppContainer.kt`, `MockInterceptor.kt`, `strings.xml`.

### Un solo historial, dos presentaciones

El chat escrito y la conversacion por voz viven en un **subgrafo de navegacion** comun
(`Routes.CONVERSATION`) y comparten la misma instancia de `ChatViewModel`. La pantalla de voz no
guarda mensajes: publica el turno cerrado, la pantalla lo envia por `ChatViewModel.send(...,
voiceMode = true)` y lee la respuesta de su `uiState`. Por eso al colgar la conversacion aparece
entera en el chat: nunca hubo dos historiales.

El enganche entre las dos maquinas de estado vive en la pantalla, con efectos de composicion, y no
en un ViewModel llamando a otro: un ViewModel que sostiene a otro se lleva mal con los ambitos de
navegacion y es imposible de probar por separado.

### Lo que costo la fase: tres fallos que solo aparecen en el telefono

Los tres compilaban, los tres pasaban las pruebas, y los tres hacian la pantalla inservible.

**1. La app se interrumpia a si misma (D-026).** El servicio de reconocimiento de voz pide el foco
de audio para grabar. Como pediamos foco para toda la llamada, al abrir el microfono lo perdiamos,
lo leiamos como "entro una llamada telefonica", cerrabamos el microfono, el servicio soltaba el
foco, lo recuperabamos, y vuelta a empezar — varias veces por segundo. Se arreglo no pidiendo foco
para escuchar (el foco es para reproducir) y dando 500 ms de gracia antes de creerse una perdida.

**2. El reconocedor se queda mudo (D-027).** Con `EXTRA_PREFER_OFFLINE` y sin el paquete de espanol
descargado, `SpeechRecognizer` abre el microfono, lo cierra 400 ms despues y **no llama a ningun
callback**: ni resultado, ni error. El reintento en linea que pide F8 se dispara desde `onError`,
asi que no se disparaba nunca y la pantalla se quedaba en "Escuchando" para siempre. Se arreglo con
un vigilante de 2500 ms que usa `onRmsChanged` como latido.

**3. `ERROR_CLIENT` es nuestro propio `cancel()` (D-023).** Cada reinicio deliberado provocaba el
codigo 5 por el mismo callback que una averia real, y se leia como fallo fatal. Se arreglo marcando
las cancelaciones propias.

Los tres se encontraron leyendo el log y `dumpsys audio`, no mirando el codigo.

### Verificado en el Samsung SM-A566E, Android 16

| | Comprobacion | Resultado |
|---|---|---|
| `[x]` | La escucha continua se sostiene sin agotarse | 30 s en sala silenciosa, reinicios cada ~7,8 s, sin darse por vencida |
| `[x]` | `voiceMode` viaja al backend | `MOCK /api/chat -> 200 (mock/chat_voz.json)` |
| `[x]` | El respaldo de reconocimiento sin conexion funciona | "se pasa a en linea para el resto de la sesion", y la sesion en linea graba 5,4 s reales |
| `[x]` | El microfono **no** queda abierto fuera de la pantalla | `dumpsys audio`: sin `rec start` despues de salir |
| `[x]` | Minimizar cierra microfono y voz | `ON_STOP` → `pause()` |
| `[x]` | **Criterio (e)**: el historial es uno solo | Pregunta escrita → modo voz → transcripcion: sigue ahi, con sus fuentes |
| `[x]` | Entradas al modo | Boton "Hablar con el asistente" en la ficha y auriculares en el chat |
| `[x]` | El dispositivo trae cancelador de eco y supresor de ruido | `eco=true, ruido=true` (aunque no se pueden enganchar, ver D-024) |
| `[x]` | 57 pruebas en la JVM, 0 fallos | 16 nuevas en `VoiceCallLogicTest` |
| `[x]` | 0 errores de lint | 28 avisos, ninguno de codigo propio |

### Latencia medida con voz real

Tres turnos seguidos, dictados al telefono, con el backend simulado:

| Turno | Fin de turno | Red | Sintesis | **Total** |
|---|---:|---:|---:|---:|
| 1 | 503 ms | 369 ms | 91 ms | **974 ms** |
| 2 | 425 ms | 360 ms | 68 ms | **865 ms** |
| 3 | 458 ms | 358 ms | 18 ms | **846 ms** |
| **Media** | **462 ms** | **362 ms** | **59 ms** | **895 ms** |

El total se mide desde el **ultimo parcial** —el instante real en que el estudiante deja de
hablar— hasta que suena la primera palabra de la respuesta. Presupuesto de F8: 2,5 s.
Medido: 0,9 s.

**Tres advertencias sobre esta cifra, porque sola engañaria:**

1. **Los 362 ms de "red" son el mock**, que simula 350 ms de latencia fija. Un backend real con
   un LLM detras tardara segundos, no decimas. Lo que estos numeros demuestran es que **el
   trabajo propio de la app cuesta unos 520 ms** (462 de cierre de turno mas 59 de sintesis); el
   presupuesto restante hasta los 2,5 s, unos 2 s, es lo que le queda al backend.
2. **El cierre de turno salio en 462 ms de media, no en los 900 ms del temporizador de
   silencio.** En esos turnos el `onResults` del sistema llego antes que el temporizador, y se
   aprovecho porque viene mejor puntuado. Los 900 ms son el techo, no el caso tipico.
3. **La sintesis baja a 18 ms** en el tercer turno frente a 91 en el primero: es el motor de voz
   ya caliente. Partir la respuesta en frases ayuda justamente al primer turno, que es el que se
   nota.

En esos tres turnos el mock alterno `chat_voz.json` y `chat_voz_sin_contexto.json`, asi que
tambien quedo probado el caso de contexto insuficiente hablado.

### El cuarto fallo, encontrado con esa misma prueba

A los 2,5 s del tercer turno apareció en el log:

```
17:40:01  Latencia de voz: total 846 ms (...)
17:40:03  Foco perdido de verdad: se pausa la conversacion
```

La app se cortaba a si misma a mitad de la respuesta. Mismo origen que D-026 pero con otra cara:
mientras el asistente habla el microfono sigue abierto para poder interrumpirlo, y esa sesion de
reconocimiento retiene el foco **varios segundos seguidos**, mas que cualquier periodo de gracia
razonable.

La pregunta correcta no era "cuanto llevo sin foco" sino **"por que lo perdi"**. Ahora la pausa
exige que `AudioManager` diga que el telefono esta sonando o en llamada
(`AudioFocusController.isPhoneBusy`), que es la unica senal fiable sin pedir
`READ_PHONE_STATE`.

### Criterios de aceptacion de F8, con voz real

Dictado al telefono por una persona. Ninguno de estos se puede automatizar: `adb` no inyecta
audio en el microfono.

**(a) Tres turnos seguidos sin tocar la pantalla — CUMPLE.**
Tres preguntas encadenadas a las 17:47:56, 17:48:13 y 17:48:23, separadas 17 s y 10 s, sin
ninguna interaccion tactil entre ellas. El mock alterno contexto suficiente e insuficiente, asi
que tambien quedo probada la burbuja ambar hablada.

**(b) Interrupcion en menos de 400 ms — CUMPLE, con poco margen.**

```
17:48:35.190  Interrupcion del asistente confirmada tras 377 ms
```

377 ms sobre un limite de 400. **El margen es de 23 ms y conviene no ignorarlo.** Los 300 ms son
la ventana de confirmacion que fija F8 y no se pueden bajar; los 77 ms restantes son la
granularidad de `onRmsChanged`, que solo se comprueba cuando el motor entrega una medida nueva. En
un telefono mas lento, o con un motor que entregue medidas mas espaciadas, este criterio podria
pasarse de 400 ms.

La mejora, si hiciera falta: en vez de comprobar solo al llegar cada medida, programar una
comprobacion a los 300 ms exactos desde la primera medida alta y verificar entonces que el nivel
sigue arriba. Aterrizaria alrededor de 310 ms en vez de 377. No se ha hecho porque el criterio se
cumple y la complejidad no se justifica hoy, pero queda escrito por si el limite se estrecha.

**(c) Sin bucle de eco con el altavoz al maximo — CUMPLE.**
El asistente leyo tres respuestas completas por altavoz y se generaron **exactamente tres**
llamadas a `/api/chat`: una por pregunta. Si el microfono se hubiera oido a si mismo habria
llamadas de sobra, que es justo la forma que tiene el bucle de manifestarse.

Aviso sobre la primera pasada: aquellos tres turnos se hicieron con el altavoz a **10/15** y el
flujo de asistente a **7/15**, no al maximo. Los dos flujos se subieron despues a **15/15** con
`cmd media_session volume --stream 3 --set 15` (y `--stream 11`) para que la comprobacion valga
para lo que dice el criterio.

**(d) Latencia media menor a 2,5 s — CUMPLE con mucho margen.**

| Turno | Fin de turno | Red | Sintesis | **Total** |
|---|---:|---:|---:|---:|
| 1 | 503 ms | 369 ms | 91 ms | 974 ms |
| 2 | 425 ms | 360 ms | 68 ms | 865 ms |
| 3 | 458 ms | 358 ms | 18 ms | 846 ms |
| 4 | 452 ms | 367 ms | 24 ms | 855 ms |
| 5 | 359 ms | 363 ms | 3 ms | 733 ms |
| 6 | 667 ms | 364 ms | 17 ms | 1057 ms |
| **Media (6)** | **477 ms** | **364 ms** | **37 ms** | **888 ms** |

**(e) Al colgar, la conversacion aparece completa en el chat — CUMPLE.**
Verificado en los dos sentidos: una pregunta escrita sobrevive el viaje chat → voz →
transcripcion con sus fuentes intactas, y los turnos hablados aparecen en el chat escrito. Es la
misma instancia de `ChatViewModel`, resuelta contra el subgrafo `Routes.CONVERSATION`.

### Lo que estas cifras no demuestran

**Los 364 ms de "red" son el backend simulado**, que inyecta 350 ms fijos. Un LLM real tardara
segundos. Lo unico que estos numeros prueban es que **el trabajo propio de la app cuesta unos
514 ms** (477 de cierre de turno mas 37 de sintesis), y que por tanto le quedan cerca de 2 s de
presupuesto al backend antes de incumplir el criterio. Cuando exista el backend real hay que
repetir esta medicion; la instrumentacion ya esta puesta y escribe la linea sola.

El total se mide siempre desde el **ultimo parcial** —el instante real en que el estudiante deja
de hablar— y no desde que la app lo detecta: la espera que cierra el turno es parte de lo que el
estudiante percibe como demora, y descontarla seria hacerse trampas.

---

### 2026-09-03 — Primer intento de integración del modelo real de Mario

**Qué se hizo.** Mario entregó `best.tflite` (YOLOv8n, 50 clases) y `labels.txt`. Se copiaron a
`app/src/main/assets/` como `model.tflite` y `labels.txt`, y `useStubDetector` pasó a `false` en
`model_config.json` (también `quantized` a `false`, el modelo es float32 sin cuantizar). Ningún
`.kt` se tocó. `./gradlew assembleDebug` → **BUILD SUCCESSFUL**.

**Verificación antes de dar el modelo por bueno.** Se inspeccionó el tensor real del `.tflite` con
`ai-edge-litert` (Python), sin fiarse solo del documento de Mario:

```
INPUT:  [1, 3, 640, 640] float32   ← NCHW
OUTPUT: [1, 54, 8400]    float32   ← 4 + 50 clases, TRANSPOSED
```

La salida cuadra con el contrato y con `labels.txt` (50 clases). **La entrada no**: es NCHW y tanto
`docs/INTEGRACION_MODELO.md` como `YoloTfliteDetector.kt` asumen NHWC `[1, 640, 640, 3]`. Detalle
completo, hipótesis del origen (probable export vía `ai-edge-torch` en vez de `onnx2tf`) y por qué
no se tocó ningún `.kt` en **D-028** de `docs/DECISIONES.md`.

**Qué pasa hoy en el teléfono.** No crashea (regla 2 de CLAUDE.md se respeta: `DetectorFactory` y
`FrameAnalyzer` atrapan la falla), pero **no detecta nada y no muestra ningún aviso en pantalla**.
La única pista visible es la pantalla de Diagnóstico, que va a mostrar la entrada como `1x3x640x640`
en vez de `1x640x640x3`, y `adb logcat -s LabScan` repitiendo "Fallo al analizar un frame".

**Resuelto el mismo día: modelo reexportado en NHWC.** Mario reexportó desde Colab saltándose el
`format='tflite'` de Ultralytics: primero a ONNX, después `onnx2tf` (que existe para convertir
NCHW→NHWC). El `model.tflite` de `assets/` es ese, verificado antes de copiarlo:

```
INPUT : images   [1, 640, 640, 3]  float32
OUTPUT: output0  [1, 54, 8400]      float32   ← 50 clases, cuadra con labels.txt
```

**Segundo hallazgo en esa verificación.** Este export **no normaliza las coordenadas**: pasando
ruido por el modelo, `cx,cy,w,h` salen entre 3,97 y 643,94 — píxeles, no 0..1. Se puso
`"coordsNormalized": false` en `model_config.json`. Con `true` las cajas habrían salido 640 veces
más grandes que la pantalla y el síntoma habría sido idéntico al del problema NCHW: ninguna caja,
sin ningún error. Detalle en D-028.

`./gradlew assembleDebug` → BUILD SUCCESSFUL. Config final: `useStubDetector: false`,
`quantized: false`, `coordsNormalized: false`, `outputLayout: "TRANSPOSED"`, `inputSize: 640`.

**VERIFICADO EN EL TELÉFONO el mismo día.** SM-A566E por depuración inalámbrica. Es la primera
vez en el proyecto que corre un modelo entrenado para la UTEQ:

```
Modelo cargado: entrada 1x640x640x3 FLOAT32, salida 1x54x8400 FLOAT32, 50 clases, TRANSPOSED
Detector real activo con model.tflite
Camara enganchada, analisis=true
Detecciones (242 ms total, 234 ms inferencia): microcentrifuga 51%
Detecciones (285 ms total, 275 ms inferencia): microcentrifuga 57%
```

Entrada NHWC, 54 = 4 + 50, `labels=50` cuadra con el tensor, y **detecta con nombres reales de
`labels.txt`**. La cadena entera —letterbox, NHWC, `coordsNormalized: false`, decodificación,
NMS, etiquetas— queda confirmada ejecutando, no por cálculo.

**Rendimiento medido: 234–307 ms de inferencia, ~3,2 FPS.** Muy por debajo del objetivo de ≥10 FPS
de F7, y el salto adaptativo de frames ya está entrando (umbral de 250 ms).

**Corrige un supuesto de la bitácora de F3/F7.** El YOLOv8n de COCO con el que se midieron los
~100 ms pesaba **3,2 MB**, imposible para un float32 de 3,2 M parámetros (serían ~12,8 MB): era un
modelo con pesos cuantizados y tensores de E/S float32, no un float32 puro. El modelo de Mario pesa
**12,2 MB** y sí es float32 completo. Es decir, los 300 ms no son una regresión respecto a los
100 ms: **nunca se había medido un float32 real en este proyecto**. Las proyecciones de la tabla de
`docs/INTEGRACION_MODELO.md` están calculadas sobre esa base equivocada y hay que rehacerlas cuando
exista el int8.

**Qué quedó pendiente.**

- **Export int8**, ahora obligatorio y no opcional: con 3,2 FPS la app no cumple F7. El
  procedimiento con dataset de calibración quedó preparado; el margen de mejora es mayor de lo que
  decía la proyección, justamente porque el punto de partida es un float32 real.
- **Verificar los positivos contra equipos reales.** En la prueba detectó `microcentrifuga` al
  45–57 % con la cámara apuntando a una escena cualquiera de escritorio: puede ser un falso
  positivo. Con un umbral de 0,45 y un v1 entrenado con pocas fotos por clase, es lo esperable.
  Hay que apuntar a los equipos del laboratorio y decidir el umbral con datos.
- **Las 3 pruebas visuales de alineación de cajas no se han repetido con este modelo.** Las de F2
  se hicieron con `StubDetector`, que emite cajas fijas. Falta ver que una caja real caiga encima
  del equipo que la provocó.


### 2026-09-03 (tarde) — Endurecimiento tras la integración, con permiso de Dariem

**Autorización.** `CLAUDE.md` prohíbe tocar Kotlin cuando se trabaja con Mario. Dariem levantó la
restricción para esta sesión, transmitido por Mario, a cambio de dejar constancia. Está en
**D-029**.

**Qué se arregló.**

1. **Un modelo con la forma equivocada ya no falla en silencio.** `YoloTfliteDetector` daba NHWC
   por sentado (`inputSize = inputShape[1]`). Con el export NCHW de la mañana eso valía 3, y el
   fallo se manifestaba de la peor forma posible: Diagnóstico diciendo "Detector real activo",
   sin banda de modo demostración, sin detectar nada, y con la única pista en `adb logcat`. Ahora
   `validateInputShape()` lo rechaza al construir y `DetectorFactory` cae al `StubDetector` con
   el motivo **en pantalla**.
2. **`CatalogJsonTest` volvió a verde invirtiendo lo que comprueba.** Exigía que toda clase de
   `labels.txt` tuviera ficha: imposible con 50 clases, y la suite estaba en rojo. Ahora exige lo
   contrario —que ninguna ficha apunte a una clase inexistente—, que es lo que detecta
   podredumbre real. Se eliminaron `incubadora` y `vortex` de `catalog.json`: eran clases que el
   modelo ya no puede emitir, así que sus fichas no se mostrarían nunca.

**No se inventaron 48 fichas técnicas, a propósito.** Fabricar EPP, riesgos y procedimientos para
equipos reales que va a leer un estudiante de primer semestre es peligroso, y `catalog.json` es
el único sitio de la app donde se muestra contenido sin que se vea la fuente. Las 50 clases las
tiene que responder el backend RAG, con los manuales reales. Razonamiento completo en D-029.

**Verificación.** `testDebugUnitTest --rerun-tasks` → **63 pruebas, 0 fallos** (antes 57 con 1 en
rojo). `assembleDebug` y `lint` en verde. Pruebas nuevas: `YoloInputShapeTest`, 5 casos, incluido
el `[1, 3, 640, 640]` real que causó el problema.

**Qué quedó sin verificar, y por qué.** **Nada de esto se probó en el teléfono**: la depuración
inalámbrica se cayó antes de poder reinstalar. Para el modelo actual el comportamiento es
demostrablemente idéntico —`validateInputShape([1,640,640,3])` devuelve 640, igual que
`inputShape[1]`— pero conviene reinstalar y confirmar que sigue detectando.

**Sigue pendiente y no se tocó:** el defecto cosmético de F7 (la etiqueta de una caja pegada al
borde superior se dibuja sobre la barra de estado). Se dejó fuera a propósito: es trabajo de
maquetación cuyo resultado hay que juzgar a ojo en pantalla, y sin teléfono conectado no se puede
verificar.
- **`catalog.json` tiene 4 fichas de ejemplo y el modelo trae 50 clases.** Toda clase detectada que
  no esté en el catálogo va a abrir la ficha mínima "Ficha no disponible" hasta que exista el
  backend RAG o se amplíe el catálogo.
- Sigue pendiente lo que ya documentó Mario en su nota de integración: 5 clases sin fotos
  etiquetadas y 3 clases genéricas intrusas (`agitador`, `camara_electroforesis`, `refrigeradora`)
  por corregir en Roboflow antes del entrenamiento final.


### 2026-09-05 — Reentrenamiento, YOLOv8m descartado con datos, y modelo nuevo instalado

**Qué se hizo.** El profesor de Mario recomendó `yolov8m` en lugar de `yolov8n`. Se entrenaron los
**dos** sobre el mismo dataset, misma semilla, mismas épocas, y se evaluaron sobre el mismo split
de test.

| | mAP50 | mAP50-95 | Precisión | Recall | Inferencia | Tamaño |
|---|---|---|---|---|---|---|
| `yolov8n` | **0,8374** | 0,5102 | 0,7733 | 0,7989 | 275 ms (medido) | 12,2 MB |
| `yolov8m` | 0,8305 | **0,5283** | **0,8227** | 0,796 | ~2 290 ms (proyectado) | 103,7 MB |

**`yolov8m` no gana.** Pierde en mAP50, gana por poco en mAP50-95 y precisión, empata en recall, y
cuesta 8,3 veces más cómputo y 8,5 veces más tamaño. A 2,3 s por frame no sirve para dibujar cajas
sobre la cámara en vivo. Razonamiento completo, y cómo se obtuvo la proyección sin repetir el
error de base de la tabla del 2026-09-03, en **D-030**.

**Lo importante no es cuál ganó, sino por qué no se puede saber.** El split de test tiene
**48 imágenes y 48 instancias** entre 28 clases, la mayoría con 1 o 2 ejemplos; y 22 de las 50
clases no aparecen en el test. Diferencias de 0,7 puntos sobre esa base son ruido. El cuello de
botella del proyecto **son los datos, no el modelo**.

**El dataset sigue sin corregir.** El `labels.txt` del reentrenamiento es idéntico byte por byte
al anterior, así que las 3 clases intrusas y las 5 sin anotar siguen ahí. Se ve en la tabla por
clase: `camara_electroforesis` (la genérica) se come las detecciones de
`camara_de_electroforesis_b2`, que colapsó a 0 en las cuatro métricas.

**Se cambió el modelo instalado** por el `yolov8n` del reentrenamiento (150 épocas). No por
precisión —las métricas del anterior eran sobre validación y las nuevas sobre test, así que no son
comparables— sino porque **el anterior era irreproducible**: su `best.pt` se perdió al reiniciarse
el runtime de Colab y su cuaderno tampoco existe. El nuevo tiene los pesos en Drive, métricas
sobre test y un cuaderno repetible.

`model_config.json` no necesitó cambios: el modelo nuevo es igual de float32, NHWC 640,
`TRANSPOSED` y con las cajas en píxeles.

**Verificación.** Tensores comprobados antes de copiar (`[1,640,640,3]` float32 →
`[1,54,8400]`, cajas 7,1–648,9). `testDebugUnitTest --rerun-tasks` → **63 pruebas, 0 fallos**.
`assembleDebug` en verde.

**Sin verificar en el teléfono.** No hubo dispositivo conectado en esta sesión. Falta reinstalar,
confirmar en el log que carga y detecta, y **medir los FPS reales del modelo nuevo** — la cifra de
275 ms de la tabla es del modelo anterior, no de este.

**Lo que de verdad desbloquea el siguiente salto:** corregir las 3 clases intrusas, anotar las 5
que faltan y subir de 1-2 fotos por clase a 20-30. Con eso `yolov8n` debería pasar holgadamente
de 0,83.

### 2026-09-09 — Corpus del RAG a 54 clases y cuaderno de reentrenamiento

**Qué se hizo.**

- **El backend RAG pasa al dataset v2.** Mario entregó `ProyectoMobil`: 54 clases, 746 fotos
  listas para Roboflow, y por cada clase una guía de referencia y unas normas de seguridad.
  `manuals/` se reconstruyó entero con `tools/migrar_manuales_v2.py`, que lleva la tabla de
  equivalencias explícita entre los nombres de clase viejos y los nuevos.

  **Los 21 manuales reales de la entrega anterior no se perdieron:** se recolocaron bajo el
  nombre de clase que les toca ahora y conviven con los documentos nuevos en la misma carpeta.
  Con `top_k = 4` cada tipo de documento gana el tipo de pregunta para el que sirve. Ver D-035.

  Resultado: 54 clases, 129 documentos, **1888 fragmentos**. 15 clases con manual del modelo
  exacto, 6 con referencia de familia, las 54 con guía y normas.

- **Tres documentos se descartaron a propósito** y quedan guardados en
  `manuals_v1_2026-09-05/`: el manual del vortex VX-200 (esa clase era una identificación
  equivocada, es una microcentrífuga), la referencia Cleaver multiSUB (horizontal, y el equipo
  del laboratorio es vertical) y el manual de bioseguridad de la OMS.

- **El troceado bajó de 500 a 200 palabras.** Los documentos nuevos son de dos páginas y cabían
  enteros en un solo fragmento, cuyo vector se parecía a todo y a nada. El microscopio devolvía
  cero fragmentos a "cómo enfoco la muestra" teniendo la respuesta escrita. Medido sobre 14
  clases y 9 preguntas antes de cambiar nada. Ver D-036.

- **`docs/ENTRENAMIENTO_COLAB.md`**, el cuaderno de reentrenamiento en 12 celdas, con el porqué
  de cada decisión: montar Drive, la línea que falta en el snippet de Roboflow, la verificación
  de clases contra `docs/labels_v2.txt`, el export vía ONNX y onnx2tf, la calibración int8 con
  imágenes reales y la comprobación de que el tensor sale NHWC.

**Qué quedó pendiente.**

- **Entrenar.** Es la tarea de Mario ahora mismo. El objetivo de rendimiento sigue siendo el
  del informe de depuración: 2,7 FPS medidos contra ≥10 de meta, y el export int8 es la palanca
  sin usar.

- **`labels.txt` sigue con las 50 clases viejas, a propósito.** Los 54 nombres esperan en
  `docs/labels_v2.txt`. Cambiarlos antes de que llegue el `.tflite` nuevo tumbaría la app al
  `StubDetector`, y Dariem tiene el teléfono con la versión que funciona. Ver D-037.

- **Revisar las fichas generadas antes de la entrega.** `storage/equipment_cards.json` se
  regeneró con el corpus nuevo. Las de equipos peligrosos (autoclaves, centrífugas, cabinas)
  las tiene que leer una persona.

- "pasos para encender el equipo" sigue sin recuperar nada en el microscopio. Es límite del
  modelo de embeddings, no falta de documento.
