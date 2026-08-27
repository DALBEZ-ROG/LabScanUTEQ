# CLAUDE.md — LabScan UTEQ

Asistente móvil de detección de equipos de laboratorio de la Universidad Técnica Estatal de Quevedo.
Este archivo es la memoria principal del proyecto. Léelo completo antes de tocar código.

## Qué hace la app

Un estudiante de primer semestre entra al laboratorio, abre la app, apunta con la cámara.
La app dibuja cuadros sobre cada equipo con su nombre y confianza. El estudiante toca un cuadro
y se abre la ficha técnica. Desde ahí puede **hablarle** a la app y preguntar cómo se enciende,
cómo se apaga, qué protección necesita. La respuesta viene de un LLM con RAG sobre los manuales
y guías de práctica de la UTEQ, y siempre cita la fuente.

## Estado actual

Lee **`docs/PROGRESO.md`** antes de empezar cualquier tarea. Ese archivo dice qué fase está
terminada y qué está a medias. Actualízalo al terminar tu trabajo.

## Quién eres y qué te toca

Este repositorio lo trabajan dos personas con roles distintos. Antes de actuar, identifica
con quién estás hablando:

- **Dariem** (encargado de la app Android). Trabaja sobre este repositorio. Sigue `CLAUDE.md`
  normalmente y consulta `docs/PROGRESO.md` para saber en qué fase va.

- **Mario** (encargado del modelo YOLO y del backend RAG). **Lee `MARIO.md` de inmediato y
  sigue únicamente lo que dice ahí.** Su trabajo es en su mayoría fuera de este repositorio:
  el backend RAG es un proyecto Python separado. Lo único que Mario toca aquí son tres archivos
  en `app/src/main/assets/`: `model.tflite`, `labels.txt` y `model_config.json`.
  **No modifiques código Kotlin cuando trabajes con Mario.**

- Si no sabes quién es, pregunta antes de escribir cualquier cosa.

`MARIO.md` no describe trabajo sobre la app Android. Si el usuario es Dariem, ignora ese archivo.

## Stack fijo (no proponer alternativas)

- Kotlin, Jetpack Compose + Material 3
- minSdk 26, targetSdk 35, JDK 17
- CameraX 1.4.x (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`)
- LiteRT / TensorFlow Lite `Interpreter` **crudo** (NO usar Task Library ni MediaPipe: no leen metadatos de YOLO)
- Retrofit + OkHttp + kotlinx.serialization
- `android.speech.tts.TextToSpeech` y `android.speech.SpeechRecognizer` (plataforma, no ML Kit)
- Inyección de dependencias **manual** vía `di/AppContainer.kt`. No Hilt, no Koin.
- Sin Room. El catálogo local es un JSON en `assets/`.

## Estructura del código

```
app/src/main/java/ec/edu/uteq/labscan/
  App.kt                      Application, crea el AppContainer
  MainActivity.kt             Host de Compose y navegación
  di/AppContainer.kt          Único punto de construcción de dependencias
  detection/
    Detection.kt              data class de una detección
    Detector.kt               INTERFAZ. Todo el resto de la app depende solo de esto
    StubDetector.kt           Cajas falsas para desarrollar sin modelo
    YoloTfliteDetector.kt     Implementación real
    Letterbox.kt              Redimensionado con relleno y sus offsets
    YoloDecoder.kt            Decodificación del tensor + NMS
    ModelConfig.kt            Lectura de assets/model_config.json
    DetectorFactory.kt        Decide stub vs real según la config
  camera/
    CameraBinder.kt           Bind de Preview + ImageAnalysis al ciclo de vida
    FrameAnalyzer.kt          ImageAnalysis.Analyzer, llama al Detector
  ui/
    scanner/                  Pantalla principal: cámara + overlay
      ScannerScreen.kt
      ScannerViewModel.kt
      DetectionOverlay.kt     Canvas que dibuja las cajas
      BoxMapper.kt            MAPEO DE COORDENADAS. Ver sección crítica abajo
    sheet/EquipmentSheet.kt   Ficha técnica del equipo
    chat/                     Chat con el asistente
    diagnostics/              Pantalla de diagnóstico del modelo
    theme/
  voice/
    TtsManager.kt
    SttManager.kt
  data/
    remote/                   Retrofit, DTOs, RagRepository
    local/EquipmentCatalog.kt Lee assets/catalog.json
app/src/main/assets/
  model.tflite                Lo provee Mario
  labels.txt                  Una clase por línea, MISMO ORDEN que el entrenamiento
  model_config.json           Parámetros de inferencia
  catalog.json                Fichas técnicas de respaldo sin conexión
```

## Reglas innegociables

1. **Nada fuera de `detection/` conoce TensorFlow.** El resto de la app solo ve la interfaz
   `Detector` y la data class `Detection`. Si necesitas importar `org.tensorflow` en `ui/`,
   estás haciendo algo mal.
2. **La app debe compilar y correr sin `model.tflite`.** Si el asset no existe o falla la carga,
   `DetectorFactory` devuelve `StubDetector` y muestra un aviso en pantalla. Nunca crashea.
3. **`ImageAnalysis` con `STRATEGY_KEEP_ONLY_LATEST`** y un `Executor` de un solo hilo.
   Siempre cerrar el `ImageProxy` en un `finally`.
4. **Nunca hardcodear el número de clases.** Se lee de `labels.txt` en tiempo de ejecución.
5. **Nunca enviar imágenes ni documentos completos al LLM desde Android.** La app envía solo
   `class_id` y el texto de la pregunta. El backend hace el RAG. Esto es requisito de la actividad.
6. **Toda respuesta del asistente muestra su fuente.** Si el backend devuelve `sources: []`,
   la UI muestra el mensaje de "información insuficiente, consultar al docente".
7. Textos de interfaz en **español**. Nombres de código en **inglés**.
8. No agregar dependencias sin registrarlo en `docs/DECISIONES.md`.

## Sección crítica: mapeo de coordenadas

Es donde este proyecto se rompe. La cadena de transformaciones es:

```
tensor del modelo (640×640 con letterbox)
  → quitar padding y reescalar al tamaño del frame de análisis
  → aplicar rotación (ImageProxy.imageInfo.rotationDegrees)
  → espejar en X si la cámara es frontal
  → escalar al tamaño de PreviewView según su ScaleType
  → coordenadas de Canvas de Compose
```

`BoxMapper` es el único lugar donde vive esta matemática. Recibe:
`modelInputSize`, `padX`, `padY`, `scale`, `analysisWidth`, `analysisHeight`, `rotationDegrees`,
`isFrontCamera`, `viewWidth`, `viewHeight`, y devuelve una `Matrix`.

`PreviewView` se fija en `ScaleType.FILL_CENTER` y `ImageAnalysis` usa el mismo
`AspectRatioStrategy` que `Preview`, para que el recorte sea idéntico en ambos.

**Verificación obligatoria antes de dar por buena esta parte:** con `StubDetector` devolviendo
una caja fija en el 25 %–75 % del frame, esa caja debe quedar exactamente centrada en pantalla
en vertical, en horizontal, y con la cámara frontal.

## Contratos con el resto del equipo

- El modelo lo entrena **Mario**. Contrato del tensor y pasos de integración: `docs/INTEGRACION_MODELO.md`.
- El backend RAG lo implementa **Mario**. Contrato HTTP: `docs/CONTRATO_API.md`.
- No cambies ninguno de esos dos contratos sin dejar constancia en `docs/DECISIONES.md`.

## Comandos

```
./gradlew assembleDebug
./gradlew installDebug
./gradlew lint
adb logcat -s LabScan
```

## Qué hacer al terminar una tarea

1. Compilar y verificar que no hay errores.
2. Actualizar `docs/PROGRESO.md`: marcar lo hecho, anotar lo pendiente y cualquier supuesto.
3. Si tomaste una decisión técnica no trivial, agregar una entrada a `docs/DECISIONES.md`.