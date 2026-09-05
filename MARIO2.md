# MARIO2.md — Estado de las tareas de Mario

> Continuación de `MARIO.md`. Ese archivo dice **qué** había que hacer; este dice **qué quedó
> hecho**, con qué evidencia, y qué falta. Fecha de corte: **2026-09-03**.

## Resumen en una tabla

| Pieza | Estado | Qué falta |
|---|---|---|
| Detector YOLO entrenado y exportado | **Integrado y detectando** | Export int8 (rendimiento) y corregir clases en Roboflow |
| Backend RAG: PDF + índice + LLM | **Construido y probado** | Los PDF y la clave de API |
| Digitalizar manuales del laboratorio | **No empezado** | Es el cuello de botella real |

> **Un efecto colateral que le toca a Dariem:** pasar `labels.txt` de 4 clases a 50 dejó
> `catalog.json` cubriendo 2 de 50, y eso puso la suite de pruebas en rojo
> (`CatalogJsonTest`, 57 pruebas, 1 falla). No lo arreglé porque es código Kotlin. Está
> explicado en `PENDIENTE.md`.

**Lo que estaba en mis manos construir, está construido y verificado ejecutando.** Lo que falta
no es programación: son datos (los manuales), dinero (5 dólares de crédito) y una tarde de
laboratorio.

---

## Tarea 1 — El detector

### Hecho

Los tres archivos están en `app/src/main/assets/` y la app corre con el detector real.
**Ningún archivo `.kt` fue modificado**, como exige `MARIO.md`.

| Archivo | Qué es |
|---|---|
| `model.tflite` | YOLOv8n, 50 clases, 12,2 MB, float32 |
| `labels.txt` | Las 50 clases, en el orden del `data.yaml` |
| `model_config.json` | `useStubDetector: false`, `quantized: false`, `coordsNormalized: false` |

### Verificado en el teléfono (SM-A566E, por depuración inalámbrica)

```
Modelo cargado: entrada 1x640x640x3 FLOAT32, salida 1x54x8400 FLOAT32,
                50 clases, disposicion TRANSPOSED
Detector real activo con model.tflite
Camara enganchada, analisis=true
Detecciones (242 ms total, 234 ms inferencia): microcentrifuga 51%
Detecciones (285 ms total, 275 ms inferencia): microcentrifuga 57%
```

Es la primera vez en el proyecto que corre un modelo entrenado para la UTEQ, y detecta con
nombres reales de `labels.txt`.

### Los dos problemas que hubo que resolver por el camino

**1. El primer export venía en NCHW.** El modelo salía con entrada `[1, 3, 640, 640]`
(canal primero) y tanto `docs/INTEGRACION_MODELO.md` como `YoloTfliteDetector.kt` esperan
NHWC `[1, 640, 640, 3]`. La app no crasheaba —las excepciones se atrapan por frame— pero
**no detectaba nada y no mostraba ningún aviso**: el único síntoma era una línea en
`adb logcat`. Se resolvió reexportando sin pasar por el `format='tflite'` de Ultralytics:

```python
model.export(format='onnx', imgsz=640, opset=12, simplify=True, nms=False)
!onnx2tf -i best.onnx -o tflite_nhwc
```

`onnx2tf` existe precisamente para convertir NCHW a NHWC, así que el resultado no depende de
qué versión de `ultralytics` esté instalada.

**2. Las coordenadas venían en píxeles, no normalizadas.** Pasando ruido por el modelo,
`cx,cy,w,h` salían entre 3,97 y 643,94. Por eso `coordsNormalized` va en `false`. Con `true`
las cajas habrían salido 640 veces más grandes que la pantalla — invisibles — y el síntoma
habría sido idéntico al del problema anterior: ninguna caja, ningún error.

Los dos están documentados en `docs/DECISIONES.md` → **D-028**.

### Lo que falta en esta tarea

- **Export int8.** El modelo actual corre a **~3,2 FPS** (234–307 ms de inferencia), muy por
  debajo del objetivo de ≥10 FPS de F7. No es opcional. El procedimiento con dataset de
  calibración está preparado; hay que sacar las imágenes de Roboflow.
- **Corregir el dataset en Roboflow.** 5 clases sin fotos etiquetadas y 3 clases genéricas
  intrusas (`agitador`, `camara_electroforesis`, `refrigeradora`). El modelo actual sirve para
  probar el pipeline, no como entrega final.
- **Ver las cajas encima de equipos reales.** En la prueba detectó `microcentrifuga` al
  45–57 % apuntando a una escena cualquiera: probablemente un falso positivo, esperable en un
  v1 con pocas fotos por clase.

### Actualización del 2026-09-05 — YOLOv8m probado y descartado

El profesor recomendó `yolov8m`. Se entrenaron **los dos** sobre el mismo dataset, misma semilla
y mismas épocas, y se evaluaron sobre el mismo split de test:

| | mAP50 | mAP50-95 | Precisión | Recall | Inferencia | Tamaño |
|---|---|---|---|---|---|---|
| `yolov8n` | **0,8374** | 0,5102 | 0,7733 | 0,7989 | 275 ms | 12,2 MB |
| `yolov8m` | 0,8305 | **0,5283** | **0,8227** | 0,796 | ~2 290 ms | 103,7 MB |

**`yolov8m` no gana y cuesta 8,3 veces más cómputo y 8,5 veces más tamaño.** Y las diferencias
son ruido: el test tiene 48 imágenes y 48 instancias entre 28 clases, y 22 de las 50 clases no
aparecen. La recomendación del profesor no es mala en general —con datos suficientes `m` suele
ganar—; el problema es que **aquí el cuello de botella son los datos**. Ver **D-030**.

Se instaló el `yolov8n` del reentrenamiento, **por reproducibilidad y no por precisión**: el
modelo anterior tenía su `best.pt` perdido y su cuaderno tampoco existía, así que era un binario
de 12 MB imposible de regenerar.

**Sigue pendiente y ahora es lo único que importa:** corregir el dataset. El `labels.txt` del
reentrenamiento es idéntico byte por byte al anterior, así que nada de lo listado arriba se
arregló. Se nota en los resultados: `camara_electroforesis` (la genérica intrusa) se come las
detecciones de `camara_de_electroforesis_b2`, que quedó en **0 en las cuatro métricas**.

**Sin verificar en el teléfono:** no hubo dispositivo conectado. Los 275 ms de la tabla son del
modelo anterior; los del nuevo están por medir.

---

## Tarea 2 — El backend RAG

### Hecho

Repositorio **separado**, como manda `MARIO.md`. No va dentro de este repo:

```
C:\Users\Mario\PythonProjects\labscan-rag
commit 330ddfb — 24 archivos, 1981 líneas
```

Implementa los tres endpoints de `docs/CONTRATO_API.md` sin desviarse de un solo nombre de
campo; los verifiqué contra los DTO reales de la app, no contra el documento.

### Verificado con el servidor corriendo

```
GET  /api/health         → 200  {"status":"ok","indexedDocuments":0,"model":"claude-haiku-4-5"}
GET  /api/equipment/...  → 404  {"error":{"code":"EQUIPMENT_NOT_FOUND","message":"..."}}
POST /api/chat           → 200  {"answer":"No dispongo de información suficiente...",
                                 "hasSufficientContext":false,"sources":[]}
```

**20 pruebas en verde**, que corren sin clave y sin conexión. Cubren lo que pedía el encargo:
el troceado conserva el número de página, la recuperación de `incubadora` nunca devuelve
fragmentos de `autoclave`, y con índice vacío `/api/chat` responde `hasSufficientContext:
false` **verificando con un espía que el LLM no llega a llamarse**.

### Decisiones que conviene que sepas

- **Con la recuperación vacía no se llama al modelo.** Se responde directo con el objeto de
  contexto insuficiente. Ahorra dinero y hace imposible que invente cuando no hay documentos.
- **`hasSufficientContext` lo decide el código**, no el modelo: a un modelo al que se le
  pregunta si tuvo contexto suficiente tiende a decir que sí.
- **La recuperación filtra siempre por `equipment_id`.** Es la única salvaguarda contra
  explicar la autoclave citando el manual del microscopio. Tiene prueba dedicada.
- **Las fuentes salen de los metadatos**, nunca las escribe el modelo.

### Lo que falta en esta tarea

- **Los PDF.** Es lo que más desbloquea, y **es gratis**: indexar no cuesta un centavo, los
  vectores los genera un modelo local. Una subcarpeta por equipo en `manuals/`, con el nombre
  exacto de la clase en `labels.txt`. Tienen que tener texto seleccionable: un PDF escaneado
  es una imagen y no extrae nada.
- **La clave de la API.** Unos 5 dólares de crédito prepago en `console.anthropic.com` (la
  cuenta de claude.ai **no** incluye la API). El gasto real del proyecto ronda **1 dólar**:
  con `claude-haiku-4-5` cada pregunta cuesta medio centavo.

**Importante: la clave sirve de poco antes que los PDF.** Con el índice vacío el chat
responde "información insuficiente" sin llegar a llamar al modelo, así que el crédito no se
tocaría. El orden correcto es: manuales → indexar → comprar crédito → generar fichas.

---

## Aclaración sobre "entrenar el LLM"

Esto es **RAG, no entrenamiento**. Los PDF no se meten dentro del modelo ni se hace
*fine-tuning*:

```
PDF → texto por página → fragmentos de ~500 palabras → vectores → ChromaDB
                                                                     ↓
pregunta del estudiante → vector → los 4 fragmentos más parecidos
                                                                     ↓
                                    se le pasan a Claude EN EL PROMPT
```

Claude nunca ve los manuales completos ni aprende nada de ellos: lee solo los fragmentos que
se le entregan en cada consulta. Por eso puede citar la página exacta, y por eso indexar es
gratis.

---

## Qué hacer a continuación, en orden

1. **Conseguir los manuales** y digitalizarlos. Bloquea todo lo demás del backend.
2. **Sacar las imágenes de Roboflow** y hacer el export int8. Bloquea los FPS.
3. **Corregir las clases en Roboflow** y reentrenar. Bloquea la entrega final del modelo.
4. **Comprar el crédito** cuando ya haya PDF indexados.
5. **Ir al laboratorio** con el teléfono y ver si las cajas caen encima de los equipos.

Lo que le toca a Dariem está en `PENDIENTE.md`.
