# Integración del modelo de detección

Documento dirigido a quien entrena el modelo. Todo lo que la app espera está aquí.

## 1. Por qué no se usa Teachable Machine

El "Modelo de imagen estándar" de Teachable Machine es un **clasificador**: recibe una imagen
de 224×224 y devuelve una etiqueta con su probabilidad. No devuelve coordenadas, por lo tanto:

- no puede dibujar cuadros delimitadores,
- no puede detectar varios equipos a la vez en la misma imagen,
- no permite el etiquetado con *bounding boxes* que la actividad exige,
- no es un detector YOLO.

La actividad pide literalmente "Etiquetar cada equipo mediante cuadros delimitadores",
"Entrenar un detector YOLO" y "Cuadros delimitadores correctamente alineados". Teachable Machine
no cumple ninguno de los tres.

## 2. Camino recomendado

### 2.1 Etiquetado (Roboflow)

1. Crear un proyecto de tipo **Object Detection**.
2. Subir las fotografías (entre 6 y 10 clases, 80–150 fotos por clase).
3. Dibujar un cuadro por cada equipo visible, con su clase.
4. Nombres de clase en `snake_case` sin tildes: `microscopio_binocular`, `incubadora`,
   `vortex`, `camara_electroforesis`.
5. Split **70 / 15 / 15** (train / valid / test).
6. Aumentos recomendados: brillo ±25 %, rotación ±15°, desenfoque leve. Nada de volteo vertical.
7. Exportar en formato **YOLOv8** y copiar el snippet de descarga.

### 2.2 Entrenamiento (Google Colab, GPU T4 gratuita)

```python
!pip install ultralytics
from ultralytics import YOLO

model = YOLO("yolov8n.pt")          # nano: el más liviano para móvil
model.train(data="data.yaml", epochs=100, imgsz=640, batch=16, patience=20)
metrics = model.val(split="test")   # evaluación con imágenes nunca vistas
print(metrics.box.map50)            # este número va en el informe
```

Meta razonable: **mAP@50 ≥ 0.80**. Por debajo de 0.60, faltan datos o hay etiquetas inconsistentes.

### 2.3 Exportación

```python
model.export(format="tflite", int8=True, imgsz=640, nms=False)
```

`nms=False` es importante: la app hace su propio NMS y así el modelo queda más simple y portable.

## 3. Contrato de entrega

Copiar a `app/src/main/assets/`:

| Archivo | Contenido |
|---|---|
| `model.tflite` | El modelo exportado |
| `labels.txt` | Una clase por línea, **exactamente el orden de `data.yaml`** |
| `model_config.json` | Parámetros de inferencia |

`labels.txt`:
```
microscopio_binocular
incubadora
vortex
camara_electroforesis
```

`model_config.json`:
```json
{
  "useStubDetector": false,
  "modelAsset": "model.tflite",
  "labelsAsset": "labels.txt",
  "inputSize": 640,
  "quantized": true,
  "outputLayout": "TRANSPOSED",
  "coordsNormalized": true,
  "confidenceThreshold": 0.45,
  "iouThreshold": 0.50,
  "maxDetections": 20,
  "useGpuDelegate": false
}
```

## 4. Forma esperada del tensor de salida

Para YOLOv8/YOLO11 exportado a TFLite con `nms=False` y N clases:

- **Entrada:** `[1, 640, 640, 3]`, RGB, valores 0–1 (o int8 cuantizado con su `scale`/`zero_point`).
- **Salida:** `[1, 4 + N, 8400]` — 4 valores de caja (`cx, cy, w, h`, normalizados 0–1)
  seguidos de N puntajes de clase. **Sin dimensión de objectness** (YOLOv8 la eliminó).
- Está **transpuesto** respecto a lo intuitivo: el eje de candidatos es el último.

Si el export produce `[1, 8400, 4+N]`, cambiar `"outputLayout": "STANDARD"`. No hay que tocar código.

## 5. Verificación sin escribir código

La app trae una **pantalla de Diagnóstico** (menú superior derecho). Muestra:

- forma y tipo real del tensor de entrada y de salida,
- parámetros de cuantización (`scale`, `zero_point`),
- rango mínimo y máximo de los valores de salida,
- número de clases leídas de `labels.txt` vs. las inferidas del tensor,
- latencia por frame y FPS.

**Procedimiento:** copiar los tres archivos a `assets/`, compilar, abrir Diagnóstico, comparar
con la tabla de la sección 4. Si el número de clases no coincide, `labels.txt` está mal.
Si el rango de salida no está entre 0 y 1, `coordsNormalized` debe ser `false`.

## 6. Pasos de integración, resumidos

1. Copiar `model.tflite` y `labels.txt` a `app/src/main/assets/`.
2. Poner `"useStubDetector": false` en `model_config.json` y ajustar `inputSize` y `quantized`.
3. `./gradlew installDebug`.
4. Abrir Diagnóstico y verificar. Luego probar en el laboratorio real.

Ningún archivo `.kt` necesita modificarse.

## 7. Plan B, solo como red de seguridad

Si el entrenamiento YOLO no llega a tiempo: ML Kit *Object Detection & Tracking* en modo genérico
produce cajas sin clase, y acepta un clasificador TFLite personalizado (tipo Teachable Machine)
para etiquetar cada caja. Visualmente da cuadro + nombre + confianza.

**No cumple** el requisito de "detector YOLO" ni el de etiquetado con bounding boxes, así que
sirve para no quedarse sin demo, no como entregable. Requiere una tercera implementación de
`Detector` (`MlKitHybridDetector`) y unas 200 líneas adicionales.


---

## Rendimiento: qué exportar para que la app vaya fluida

Medido en un **Samsung SM-A566E** (Exynos 1380, 8 núcleos), con un YOLOv8n de COCO como sustituto,
mediana de 30 inferencias sobre el mismo frame:

| Export | Inferencia | Total por frame | FPS |
|---|---|---|---|
| 640, float32 (el que se midió) | 100 ms | 107 ms | **8,6 medidos en el teléfono** |
| 640, int8 | ~40-50 ms estimados | ~50-60 ms | ~16-20 |
| 416, int8 | ~20-25 ms estimados | ~30 ms | ~30 |

**Las dos filas de abajo son proyecciones**, no medidas: no se dispuso de un modelo int8 ni de uno a
416 para comprobarlas. Se calculan a partir del coste conocido de la cuantización int8 (entre 2 y 3
veces más rápida en CPU ARM) y del área de la entrada (416² es 2,37 veces menor que 640²).

### Recomendación

Exportar **int8 y a 416**, tal como ya dice la sección 2 de este documento:

```python
model.export(format="tflite", int8=True, imgsz=416, nms=False)
```

Y poner `"inputSize": 416` en `model_config.json` para que quede documentado.

**La app no necesita ningún cambio para ninguno de los dos casos.** El tamaño de entrada, la
cuantización y la disposición de la salida se leen del propio tensor en tiempo de ejecución
(D-009); `model_config.json` solo los declara. Si el archivo dice 640 y el modelo trae 416, manda
el modelo y la pantalla de Diagnóstico avisa de la discrepancia.

**Importante:** `inputSize` es una propiedad del archivo `.tflite`. Cambiarlo en
`model_config.json` **no** reduce un modelo de 640 a 416; hay que volver a exportarlo. El único
tamaño que sí se controla desde la app es el del frame que entrega la cámara, fijado en
`CameraBinder.ANALYSIS_RESOLUTION_STRATEGY`.

### Cómo comprobarlo cuando llegue el modelo

```bash
./gradlew connectedDebugAndroidTest   -Pandroid.testInstrumentationRunnerArguments.class=ec.edu.uteq.labscan.detection.DetectorBenchmarkTest
```

Imprime en logcat la mediana de latencia total, la de inferencia y los bytes reservados por frame.
Se salta solo si la app está en modo demostración. Es el mismo banco con el que se obtuvieron los
números de la tabla, así que los resultados son comparables.
