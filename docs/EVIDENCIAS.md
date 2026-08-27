# Evidencias de la actividad — LabScan UTEQ

Estado real a **2026-08-27**. Cada ítem dice dónde está y, cuando algo no está hecho, por qué.

Leyenda: `[x]` hecho y verificado · `[~]` hecho pero sin verificar en dispositivo · `[ ]` pendiente

---

## 1. Aplicación Android

| | Requisito | Dónde está | Verificado |
|---|---|---|---|
| `[x]` | Detección de equipos en tiempo real con la cámara | `detection/`, `camera/` | SM-A566E, 8,6 FPS con YOLOv8n COCO |
| `[x]` | Cuadros con nombre y porcentaje de confianza | `ui/scanner/DetectionOverlay.kt` | `docs/capturas/01-escaner.png` |
| `[x]` | Inferencia **en el dispositivo**, sin enviar imágenes | `YoloTfliteDetector` | Ninguna llamada de red en el bucle |
| `[x]` | Selección tocando un cuadro | `DetectionOverlay` + `ScannerViewModel` | Bitácora 2026-08-27 |
| `[x]` | Ficha técnica con función, componentes, procedimiento, EPP, riesgos y prácticas | `ui/sheet/EquipmentSheet.kt` | `docs/capturas/02-ficha.png` |
| `[x]` | Chat con el asistente | `ui/chat/` | `docs/capturas/03-chat.png` |
| `[x]` | Pregunta **por voz** (mantener pulsado y hablar) | `voice/SttManager.kt` | Probado a mano por el autor |
| `[x]` | **Conversación manos libres** con escucha continua e interrupción | `ui/voice/`, `voice/ContinuousSttManager.kt` | 5/5 criterios de F8 con voz real, bitácora F8 |
| `[x]` | Respuesta **leída en voz alta** | `voice/TtsManager.kt` | `com.google.android.tts is now playing` en logcat |
| `[x]` | Toda respuesta muestra su fuente | `ui/chat/ChatBubbles.kt` | `docs/capturas/04-respuesta-con-fuente.png` |
| `[x]` | Aviso distinto cuando no hay información suficiente | Burbuja ámbar sin fuentes | `docs/capturas/05-informacion-insuficiente.png` |
| `[x]` | Textos de interfaz en español | `res/values/strings.xml`, 109 cadenas | Ninguna cadena suelta en los composables |
| `[x]` | Funciona **sin modelo** y **sin backend** | `DetectorFactory`, `EquipmentCatalog` | Release instalado en modo demostración |

## 2. APK instalable

| | Requisito | Dónde está | Verificado |
|---|---|---|---|
| `[x]` | APK de entrega firmado | `app/build/outputs/apk/release/app-release.apk` (25,8 MB) | `apksigner verify` → *Verifies*, esquema v2 |
| `[x]` | Keystore documentado | `keystore/labscan-demo.jks`, explicado en README y D-021 | Genera el APK sin configuración externa |
| `[x]` | Ofuscación activada sin romper nada | `minifyEnabled` + `app/proguard-rules.pro` | Tres pruebas independientes tras R8: `ModelConfig`, `catalog.json` y el mapeo de `RagError` |
| `[x]` | Instala en un teléfono real | SM-A566E, Android 16 | `adb install` → *Success* |
| `[x]` | El backend simulado **no** viaja en el APK de entrega | `app/src/debug/assets/mock/` | `unzip -l` sobre el release: solo 3 assets reales |

## 3. Evidencias de funcionamiento en teléfono real

Todas tomadas en un **Samsung SM-A566E, Android 16**.

| | Evidencia | Dónde está |
|---|---|---|
| `[x]` | Capturas de las pantallas principales | `docs/capturas/` (8 imágenes) |
| `[x]` | Detección real de objetos con un modelo YOLOv8n COCO | Bitácora F3 y F7: `person 79%`, `laptop 76%`, `keyboard 83%` |
| `[x]` | Verificación de alineación de cajas en vertical, horizontal y cámara frontal | `docs/PROGRESO.md`, bitácora 2026-08-26 |
| `[x]` | Medición de FPS y latencia antes y después de optimizar | `docs/PROGRESO.md`, bitácora F7 |
| `[x]` | Pruebas de estabilidad: rotación, minimizar, ficha abierta | `docs/PROGRESO.md`, bitácora F7 |
| `[x]` | 57 pruebas en la JVM + 14 instrumentadas en verde | Ejecutadas el 2026-08-27: 0 fallos, 0 errores, 2 omitidas (banco de rendimiento, sin modelo) |
| `[x]` | Recorrido completo del **APK de release** en el teléfono | Bitacora 2026-08-27: escaner, ficha, chat, voz, ajustes y diagnostico |
| `[ ]` | **Fotografías del laboratorio real con los equipos de la UTEQ** | Falta la autorización para fotografiar el laboratorio |

## 4. Video de demostración

| | Requisito | Dónde está |
|---|---|---|
| `[x]` | Guion de 4 minutos con los momentos exactos a grabar | `docs/GUION_VIDEO.md` |
| `[ ]` | **Video grabado** | Pendiente: requiere el laboratorio y el modelo entrenado |

El guion incluye la lista de diez tomas obligatorias, la preparación previa y los errores que
obligan a regrabar. Con el modelo cargado y el laboratorio disponible, la grabación es una sesión
de media hora.

## 5. Repositorio documentado

| | Requisito | Dónde está |
|---|---|---|
| `[x]` | README con descripción, capturas, arquitectura, compilación y créditos | `README.md` |
| `[x]` | Contrato del modelo y pasos de integración | `docs/INTEGRACION_MODELO.md` |
| `[x]` | Contrato HTTP del backend | `docs/CONTRATO_API.md` |
| `[x]` | Registro de decisiones técnicas con su porqué | `docs/DECISIONES.md`, D-001 a D-022 |
| `[x]` | Bitácora del proyecto con lo medido en cada fase | `docs/PROGRESO.md` |
| `[x]` | Plan de fases original | `docs/PLAN_FASES.md` |
| `[x]` | Instrucciones para el siguiente que toque el código | `CLAUDE.md` |

## 6. Requisitos explícitos de la actividad

| | Requisito | Cómo se cumple |
|---|---|---|
| `[x]` | El RAG lo hace el **backend**, no el teléfono | `ChatRequestDto` solo lleva `classId`, `message` e `history`. No hay ningún campo binario |
| `[x]` | La app **nunca** envía imágenes ni documentos al LLM | Verificable en `data/remote/`: el único cuerpo que se envía es `ChatRequestDto` |
| `[x]` | El historial se trunca a los últimos 6 turnos | `RagRepository.truncateForRequest()`, con prueba en `RagErrorTest` |
| `[x]` | Toda respuesta cita su fuente | `ChatBubbles.SourceChips` y `EquipmentSheet.SourcesFooter` |
| `[x]` | Si no hay contexto suficiente, se avisa y **no** se citan fuentes | El repositorio vacía `sources`; la burbuja va en ámbar |
| `[x]` | Voz con APIs de plataforma, nada en la nube | `android.speech.tts.TextToSpeech` y `android.speech.SpeechRecognizer` |
| `[x]` | Sin Hilt, sin Koin, sin Room | `di/AppContainer.kt`, catálogo en `assets/catalog.json` |
| `[x]` | `Interpreter` crudo de TFLite, sin Task Library ni MediaPipe | `YoloTfliteDetector`, ver D-001 |

---

## Lo que falta, y de qué depende

| Pendiente | Depende de | Bloquea |
|---|---|---|
| `model.tflite` entrenado con las clases de la UTEQ | Mario, y del dataset etiquetado | Detección real de los equipos del laboratorio |
| Backend RAG desplegado | Mario | Respuestas reales; hoy responde el mock |
| Manuales y guías digitalizados | Coordinación del laboratorio | Contenido real del RAG |
| Autorización para fotografiar el laboratorio | Coordinación del laboratorio | Dataset y evidencias fotográficas |
| Video de demostración | Las tres anteriores | Entregable de video |
| Revisar `catalog.json` contra los manuales reales | Manuales digitalizados | Que las fichas sean ciertas y no de ejemplo |

**Ninguno de estos pendientes requiere tocar código.** El modelo y el backend entran por
configuración; las fichas son un JSON en `assets/`.
