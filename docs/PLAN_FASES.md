# LabScan UTEQ — Análisis de alcance y plan de construcción

## 1. Qué se está construyendo realmente

No es una app. Son **tres sistemas** que se conectan por contratos:

| Sistema | Responsable | Entregable |
|---|---|---|
| A. App Android (cámara, detección, overlay, voz, chat) | Dariem | APK + repositorio |
| B. Modelo de detección YOLO → `.tflite` | Mario | `model.tflite` + `labels.txt` + notebook |
| C. Backend RAG (índice vectorial + LLM + fuentes) | Mario / compartido | API HTTP desplegada |

La clave del plan: **los tres avanzan en paralelo sin bloquearse**, porque A habla con B por un archivo de configuración y con C por un contrato HTTP fijo. Ninguno espera al otro.

## 2. Corrección crítica sobre el modelo

Teachable Machine (modelo de imagen estándar, 224×224, ~5 MB) es un **clasificador de imagen completa**. Devuelve `{clase, confianza}`. No devuelve coordenadas. Por lo tanto:

- No puede dibujar cuadros delimitadores.
- No puede detectar varios equipos simultáneamente.
- No permite el etiquetado con *bounding boxes* que la actividad exige.
- No es "un detector YOLO".

**Plan A (el correcto):** Roboflow (etiquetado + split 70/15/15 + export YOLO) → Ultralytics YOLOv8n o YOLO11n en Google Colab → `model.export(format='tflite', int8=True, imgsz=640)`.

**Plan B (solo si el entrenamiento falla a último momento):** ML Kit *Object Detection & Tracking* en modo genérico (produce cajas sin clase) + un clasificador TFLite estilo Teachable Machine aplicado a cada caja. Da cajas + nombre + confianza en pantalla, pero **no cumple** el requisito literal de "detector YOLO". Sirve como red de seguridad para la demo, no como entregable.

La app se construye con una interfaz `Detector` que soporta ambos, así que la decisión no bloquea el desarrollo.

## 3. Tamaño estimado

| Módulo | Archivos Kotlin aprox. | Líneas aprox. | Dificultad |
|---|---|---|---|
| Bootstrap, Gradle, permisos, tema | 8 | 400 | Baja |
| CameraX (preview + análisis) | 4 | 450 | Media |
| Overlay + mapeo de coordenadas | 3 | 400 | **Alta** |
| Inferencia TFLite (letterbox, decode, NMS, cuantización) | 6 | 700 | **Alta** |
| Ficha técnica + catálogo local | 5 | 400 | Baja |
| Capa de red + repositorio RAG | 7 | 500 | Media |
| Chat + TTS + STT | 6 | 650 | Media |
| Pantalla de diagnóstico, ajustes, pulido | 5 | 400 | Baja |
| **Total** | **~44** | **~3.900** | |

Peso del APK: ~18–26 MB (LiteRT ~4 MB + modelo int8 ~6–12 MB + Compose).
Tiempo realista: **8 sesiones de trabajo** con Claude Code, una por fase.

Los dos puntos donde este tipo de proyecto suele fracasar son:
1. **Cajas desalineadas** con la vista previa (rotación, espejo de cámara frontal, `FILL_CENTER` recortando, letterbox del modelo). Por eso la fase 2 dibuja cajas *falsas* y no se avanza hasta que estén perfectas.
2. **Decodificación del tensor de salida de YOLO** (formato `[1, 4+N, 8400]`, transpuesto, normalizado, cuantizado int8). Por eso la fase 3 empieza obligatoriamente registrando en log la forma y el rango real del tensor antes de escribir el decodificador.

## 4. Fases

| # | Fase | Objetivo verificable | Depende de |
|---|---|---|---|
| F0 | Bootstrap y memoria del proyecto | El repo compila, existen CLAUDE.md y docs/ | — |
| F1 | Cámara en vivo | PreviewView a pantalla completa, permisos en runtime, sin fugas de ciclo de vida | F0 |
| F2 | Pipeline de análisis + overlay con `StubDetector` | Cajas falsas fijas sobre objetos reales, alineadas en vertical, horizontal y con cámara frontal | F1 |
| F3 | Detector TFLite real | El modelo real reemplaza al stub cambiando una línea de config | F2 + modelo de Mario (o modelo COCO de prueba) |
| F4 | Selección de detección + ficha técnica | Tocar una caja abre bottom sheet con datos del catálogo local | F2 |
| F5 | Capa de red + contrato RAG | La app consume `/api/chat` contra un mock local | F4 |
| F6 | Chat + voz (TTS/STT) | Preguntar hablando y escuchar la respuesta | F5 |
| F7 | Rendimiento, diagnóstico, evidencias | ≥10 FPS en teléfono real, APK release, video | Todas |

**Regla de oro:** F1 a F2 y F4 a F7 **no requieren el modelo de Mario en absoluto**. Si él se atrasa, tú terminas el 80 % de la app igual.

## 5. Qué se le entrega a Mario

Cuando él clone el repo encuentra:
- `CLAUDE.md` con las reglas del proyecto y un mapa de dónde está todo.
- `docs/INTEGRACION_MODELO.md` con el procedimiento de entrenamiento, el contrato exacto del tensor, y los 4 pasos de integración.
- `docs/CONTRATO_API.md` con los endpoints que debe implementar el backend RAG.
- `docs/PROGRESO.md` con el estado real de cada fase.
- Una pantalla de diagnóstico dentro de la app que imprime la forma del tensor, el rango de valores y los FPS, para que él verifique su modelo sin tocar código.
