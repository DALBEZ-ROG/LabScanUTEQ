# Reentrenamiento en Google Colab, de cero

Guia para Mario. Cada bloque es **una celda** de Colab, en este orden.

Entorno: Colab con GPU T4 (Entorno de ejecucion -> Cambiar tipo de entorno -> T4 GPU).

## Por que no se usa `model.export(format="tflite")`

Porque produce un tensor de entrada **NCHW** `[1,3,640,640]`, y el interprete de Android
necesita **NHWC** `[1,640,640,3]`. Se comprobo en el telefono: el modelo cargaba y no detectaba
nada. El camino que si funciona es ONNX -> `onnx2tf` -> TFLite. Ver `docs/DECISIONES.md`.

## Por que yolov8n y no yolov8m

Se midieron los dos con el mismo dataset. El `m` no mejoro lo suficiente para justificar
que es unas tres veces mas lento en el telefono, y el cuello de botella ya es la inferencia
(254 ms de los 263 ms totales). Ver `docs/DEPURACION_2026-09-09.md`.

---

## Celda 1 — GPU y Google Drive

Montar Drive **no es opcional**. Colab borra `/content` al reiniciar el entorno y ya se
perdio un `best.pt` entrenado por no hacerlo.

```python
!nvidia-smi
from google.colab import drive
drive.mount('/content/drive')
!mkdir -p /content/drive/MyDrive/labscan
print("Los pesos se guardaran en /content/drive/MyDrive/labscan")
```

## Celda 2 — Instalar ultralytics

```python
!pip install -q ultralytics
import ultralytics; ultralytics.checks()
```

## Celda 3 — Descargar el dataset de Roboflow

Pega aqui el snippet de Roboflow (Export -> YOLOv8 -> show download code -> Jupyter).
**Agrega la ultima linea**, que el snippet de Roboflow no la trae y sin ella falla la
celda de entrenamiento con `NameError: DATA is not defined`.

```python
!pip install -q roboflow
from roboflow import Roboflow
rf = Roboflow(api_key="TU_API_KEY")
project = rf.workspace("...").project("...")
dataset = project.version(N).download("yolov8")

DATA = dataset.location + "/data.yaml"      # <-- ESTA LINEA LA AGREGAS TU
print(DATA)
```

## Celda 4 — Verificar las clases ANTES de entrenar

**Esta es la celda mas importante del cuaderno.** El dataset v2 tiene **54 clases**, y la
lista que se espera esta en `docs/labels_v2.txt`. La celda compara las dos.

Si no coinciden, el modelo detectara bien y la app pondra **el nombre equivocado** en cada
cuadro. Es el peor tipo de fallo, porque parece que todo funciona.

Ojo con una cosa que Roboflow no hace solo: subir las fotos en carpetas por clase **no crea
los cuadros delimitadores**. Roboflow puede tomar el nombre de la carpeta como etiqueta, pero
alguien tiene que dibujar la caja de cada equipo. Si el proyecto tiene fotos sin anotar, no
entran al entrenamiento y esta celda te lo va a ensenar en el conteo por split.

```python
import yaml, glob
cfg = yaml.safe_load(open(DATA))
names = cfg["names"]
names = [names[i] for i in range(len(names))] if isinstance(names, dict) else names
print(len(names), "clases\n")
for i, n in enumerate(names):
    print(i, "\t", n)

open("/content/drive/MyDrive/labscan/labels.txt", "w").write("\n".join(names) + "\n")

for split in ["train", "valid", "test"]:
    print(split, len(glob.glob(f"{dataset.location}/{split}/images/*")))
```

Lo importante de la ultima parte: cuantas imagenes hay por split. Si `test` da 0, la
celda 6 no puede evaluar y hay que usar `split="val"`.

Y la comparacion contra lo que el backend ya tiene indexado. Pega el contenido de
`docs/labels_v2.txt` en la variable `ESPERADAS` y ejecuta:

```python
ESPERADAS = """
agitador_calentador_thermo_scientific_cimarec
agitador_calentador_thermo_scientific_cimarec_plus
...pega aqui las 54 lineas de docs/labels_v2.txt...
""".split()

sobran = [n for n in names if n not in ESPERADAS]
faltan = [n for n in ESPERADAS if n not in names]
print("en Roboflow y no esperadas:", sobran or "ninguna")
print("esperadas y no en Roboflow:", faltan or "ninguna")
print("orden identico:", names == ESPERADAS)
```

Las tres lineas tienen que decir "ninguna", "ninguna" y "True". Si el orden no coincide
pero las clases si, no pasa nada grave: se regenera `labels.txt` con el orden de Roboflow,
que es el que manda.

## Celda 4b — Comprobar que el dataset trae CUADROS, y no solo etiquetas

Esta celda existe por lo que paso el 2026-09-09 y costo una sesion entera.

```python
import glob, os
etiquetas = glob.glob(f"{dataset.location}/**/labels/*.txt", recursive=True)
yamls = glob.glob("/content/**/data.yaml", recursive=True)
print("archivos de etiquetas:", len(etiquetas))
print("data.yaml encontrados:", yamls)
if not etiquetas or not yamls:
    print("
PARA. El dataset NO es de deteccion de objetos.")
    print("Mira si la estructura es train/<clase>/*.jpg: eso es una exportacion")
    print("de CLASIFICACION y sus fotos no tienen cuadros delimitadores.")
```

Tiene que imprimir miles de archivos de etiquetas y una ruta de `data.yaml`.

**Si imprime 0 etiquetas y ningun yaml**, el proyecto de Roboflow se creo como
**Classification** en vez de **Object Detection**. Las fotos estan subidas pero nadie
dibujo las cajas, y con eso no se puede entrenar un detector. La estructura delatora es
`train/<nombre_de_clase>/foto.jpg`; la correcta es `train/images/` y `train/labels/`.

No hay atajo de codigo para esto. Hay que crear el proyecto como Object Detection y anotar.
Lo unico que acelera de verdad es usar **Label Assist con un modelo propio**: el detector
v1 que ya esta entrenado sabe reconocer casi los mismos aparatos y puede predibujar las
cajas para luego corregirlas.

**Lo que NO se debe hacer:** generar una caja que ocupe la foto entera para cada imagen.
Es tentador porque son 746 archivos de texto en un minuto, pero le ensena al modelo que el
objeto siempre ocupa toda la pantalla. El resultado es un detector que dibuja un cuadro
alrededor de todo lo que ve y que no sirve para lo que hace esta app, que es senalar cada
equipo dentro de la escena.

## Celda 5 — Entrenar

```python
from ultralytics import YOLO

model = YOLO("yolov8n.pt")
model.train(
    data=DATA,
    epochs=150,
    imgsz=640,
    batch=16,
    patience=30,
    project="/content/drive/MyDrive/labscan",   # los pesos caen directo en Drive
    name="v8n_run",
    seed=0,
)
```

`patience=30` corta solo si deja de mejorar, asi que poner 150 epocas no cuesta tiempo
de mas. Con GPU T4 y unas 2000 imagenes son entre 1 y 2 horas.

Si Colab te desconecta a medias, la celda 1 y esta misma con `resume=True` retoman
desde el ultimo checkpoint que quedo en Drive.

## Celda 6 — Evaluar

Estos numeros van al informe. Anotalos.

```python
best = "/content/drive/MyDrive/labscan/v8n_run/weights/best.pt"
model = YOLO(best)
m = model.val(split="test")          # si test esta vacio, usa split="val"
print("mAP50    ", round(m.box.map50, 4))
print("mAP50-95 ", round(m.box.map, 4))
print("precision", round(m.box.mp, 4))
print("recall   ", round(m.box.mr, 4))
```

Meta: **mAP@50 mayor o igual a 0.80**. Por debajo de 0.60 faltan datos o hay etiquetas
inconsistentes.

Y lo mas util para saber que arreglar, el rendimiento **por clase**:

```python
import numpy as np
names = model.names
ap50 = m.box.ap50
for i in np.argsort(ap50)[:15]:
    print(round(float(ap50[i]), 3), "\t", names[int(m.box.ap_class_index[i])])
```

Las clases del fondo de esa lista son las que hay que fotografiar mas.

## Celda 7 — Exportar a ONNX

Se hace **antes** de instalar onnx2tf, porque onnx2tf cambia versiones de numpy y
tensorflow y puede dejar el entorno en un estado donde ultralytics ya no arranca.

```python
model = YOLO(best)
onnx_path = model.export(format="onnx", imgsz=640, opset=13, simplify=True, nms=False)
print(onnx_path)

!cp {onnx_path} /content/drive/MyDrive/labscan/best.onnx
```

`nms=False` es a proposito: la app hace su propio NMS.

## Celda 8 — Datos de calibracion para int8

La cuantizacion int8 necesita ver imagenes reales para elegir las escalas. Si se le dan
imagenes al azar, o ninguna, el modelo pierde precision de forma brutal. Estas imagenes
tienen que pasar por el **mismo letterbox** que usa la app: relleno gris 114, canales RGB
y valores entre 0 y 1.

```python
import cv2, glob, random, numpy as np

def letterbox(im, size=640):
    h, w = im.shape[:2]
    r = min(size / h, size / w)
    nh, nw = int(round(h * r)), int(round(w * r))
    im = cv2.resize(im, (nw, nh), interpolation=cv2.INTER_LINEAR)
    canvas = np.full((size, size, 3), 114, dtype=np.uint8)
    top, left = (size - nh) // 2, (size - nw) // 2
    canvas[top:top + nh, left:left + nw] = im
    return canvas

paths = sorted(glob.glob(f"{dataset.location}/valid/images/*"))
random.seed(0); random.shuffle(paths)
paths = paths[:300]

batch = np.stack([
    letterbox(cv2.cvtColor(cv2.imread(p), cv2.COLOR_BGR2RGB)).astype(np.float32) / 255.0
    for p in paths
])
np.save("/content/calib.npy", batch)
print(batch.shape, batch.dtype, batch.min(), batch.max())
# esperado: (300, 640, 640, 3) float32 0.0 1.0
```

## Celda 9 — De ONNX a TFLite, float32 e int8

```python
!pip install -q onnx onnxsim onnx2tf onnx_graphsurgeon sng4onnx ai_edge_litert

!onnx2tf -i /content/drive/MyDrive/labscan/best.onnx \
         -o /content/tfl \
         -oiqt \
         -cind images /content/calib.npy "[[[[0.,0.,0.]]]]" "[[[[1.,1.,1.]]]]"

!ls -la /content/tfl/*.tflite
```

`-oiqt` es lo que genera las variantes cuantizadas. Vas a obtener varios archivos:

| Archivo | Que es |
|---|---|
| `best_float32.tflite` | El seguro. Es el que hay en la app hoy. |
| `best_float16.tflite` | La mitad de tamano, misma velocidad en CPU. Sirve poco aqui. |
| `best_integer_quant.tflite` | int8 por dentro, entrada y salida float32. **El candidato.** |
| `best_full_integer_quant.tflite` | int8 tambien en la entrada y la salida. |

Los dos ultimos sirven: **el detector de Android elige el camino segun el tipo real del
tensor, no segun lo que diga `model_config.json`** (ver `writeInput` y `readOutput` en
`YoloTfliteDetector.kt`). No hay que tocar nada de Kotlin.

## Celda 10 — Verificar la forma de los tensores

No te saltes esta celda. Es la que atrapa el error que costo la primera integracion.

Se usa `ai_edge_litert` y no `tensorflow` a proposito: onnx2tf suele dejar el
`import tensorflow` roto por la version de numpy, y aqui solo hace falta leer el modelo.

```python
from ai_edge_litert.interpreter import Interpreter

for f in ["best_float32", "best_integer_quant", "best_full_integer_quant"]:
    try:
        it = Interpreter(model_path=f"/content/tfl/{f}.tflite")
        it.allocate_tensors()
        i, o = it.get_input_details()[0], it.get_output_details()[0]
        ok = list(i["shape"]) == [1, 640, 640, 3]
        print(("OK  " if ok else "MAL "), f)
        print("     entrada", i["shape"], i["dtype"].__name__, "cuant", i["quantization"])
        print("     salida ", o["shape"], o["dtype"].__name__, "cuant", o["quantization"])
    except Exception as e:
        print("MAL ", f, e)
```

Tiene que decir:

- entrada `[1 640 640 3]`. Si sale `[1 3 640 640]` **el modelo no sirve**, se exporto por
  el camino equivocado.
- salida `[1 54 8400]`, donde 54 = 4 coordenadas + numero de clases. Si cambiaste la
  cantidad de clases ese 54 cambia, y tiene que cuadrar con las lineas de `labels.txt`.

## Celda 11 — Descargar

```python
from google.colab import files
!cp /content/tfl/best_float32.tflite /content/drive/MyDrive/labscan/
!cp /content/tfl/best_integer_quant.tflite /content/drive/MyDrive/labscan/
files.download("/content/tfl/best_float32.tflite")
files.download("/content/tfl/best_integer_quant.tflite")
files.download("/content/drive/MyDrive/labscan/labels.txt")
```

---

## Que hace falta de vuelta para integrar

1. La lista de clases de la celda 4.
2. Los numeros de la celda 6, incluida la tabla de las 15 peores clases.
3. La salida de la celda 10.
4. Los `.tflite` en `C:\Users\Mario\Downloads\`.

Con eso se integran los dos modelos y se mide en el telefono cual conviene.

---

## Celda 12 — La integracion en la app, y por que va al final

Tres archivos de `app/src/main/assets/` cambian **a la vez o ninguno**:

| Archivo | Cambia a |
|---|---|
| `model.tflite` | El modelo nuevo de 54 clases |
| `labels.txt` | Las 54 lineas de `docs/labels_v2.txt` |
| `catalog.json` | Sus dos fichas, con los `classId` nuevos |

**No se puede adelantar el cambio de `labels.txt`.** El detector compara las lineas de
`labels.txt` con el ancho del tensor de salida:

```kotlin
if (labels.size != spec.numClasses) { close(); throw ModelMismatchException(...) }
```

El modelo que hay hoy en la app emite 50 clases. Poner 54 lineas sin cambiar el `.tflite`
lanza esa excepcion, `DetectorFactory` cae al `StubDetector` y la app se queda dibujando
cuadros falsos con un aviso. Dariem tiene el telefono con la version que funciona, asi que
`labels.txt` se queda con las 50 clases viejas hasta que llegue el modelo nuevo.

Por eso la lista de 54 esta esperando en `docs/labels_v2.txt` y no en `assets/`.

Cuando llegue el modelo, el cambio completo es:

```bash
cp ~/Downloads/best_float32.tflite app/src/main/assets/model.tflite
cp docs/labels_v2.txt              app/src/main/assets/labels.txt
./gradlew test          # CatalogJsonTest avisa de las fichas huerfanas
./gradlew installDebug
```

`CatalogJsonTest` va a fallar a proposito la primera vez: las dos fichas de `catalog.json`
usan `microscopio_binocular` y `camara_electroforesis`, que ya no son clases del modelo.
Sus equivalentes en el dataset v2 son `microscopio_compuesto_binocular_amscope_b120` y
`camara_de_electroforesis_owl_easycast_b2`.

Si eliges el `.tflite` int8, ademas pon `"quantized": true` en `model_config.json`. Es solo
informativo, porque el detector mira el tipo real del tensor, pero deja la configuracion
diciendo la verdad.

El backend RAG **ya esta indexado con las 54 clases**, asi que no hay que tocarlo.
