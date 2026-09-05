# PENDIENTE.md — Para Dariem

> Escrito el **2026-09-03**, después de integrar el primer modelo real y construir el backend
> RAG. Estado de las tareas de Mario: `MARIO2.md`. Bitácora completa: `docs/PROGRESO.md`.
>
> **La app ya no está en modo demostración.** Detecta con un modelo entrenado para la UTEQ.
>
> **Actualizado esa misma tarde:** diste permiso para tocar Kotlin, así que la sección 2.1
> (catálogo y suite en rojo) **ya está resuelta**, y de paso se cerró el agujero que hizo que el
> modelo NCHW fallara en silencio. Todo en **D-029**. Lo que queda para ti está más abajo, y es
> bastante menos de lo que decía la primera versión de este archivo.

---

## 1. Lo que cambió en este repositorio

Cuatro archivos modificados y uno nuevo. **Ningún `.kt` fue tocado**, tal como dice `CLAUDE.md`
para el trabajo de Mario:

| Archivo | Cambio |
|---|---|
| `app/src/main/assets/model.tflite` | **Nuevo.** YOLOv8n de la UTEQ, 50 clases, 12,2 MB |
| `app/src/main/assets/labels.txt` | De 4 clases de ejemplo a **las 50 reales** |
| `app/src/main/assets/model_config.json` | `useStubDetector: false`, `quantized: false`, `coordsNormalized: false` |
| `docs/DECISIONES.md` | Entrada **D-028** |
| `docs/PROGRESO.md` | Bitácora del 2026-09-03 y tabla de dependencias actualizada |

Verificado en un SM-A566E: `./gradlew assembleDebug` en verde, el detector real carga y
detecta con nombres reales de `labels.txt`.

---

## 2. Lo que te toca a ti

### 2.1 `catalog.json` quedó obsoleto — ✅ RESUELTO, pero léelo y opina

**Ya está arreglado y la suite volvió a verde (63 pruebas, 0 fallos).** Se deja escrito porque
tomé una decisión de criterio en tu terreno y quiero que la revises, no porque quede trabajo.

Lo que pasó y qué se hizo, en corto:

```
Fichas en catalog.json:  4   (microscopio_binocular, incubadora, vortex, camara_electroforesis)
Clases en labels.txt:   50
Clases SIN ficha:       48
```

Peor: **dos de las cuatro fichas ya no sirven.** `incubadora` y `vortex` no existen en el
modelo entrenado, así que nunca se van a detectar. Solo `microscopio_binocular` y
`camara_electroforesis` siguen siendo clases válidas.

**Qué significa en pantalla:** el estudiante toca casi cualquier caja y le sale *"Ficha no
disponible. Este equipo aún no está en el catálogo"*. El respaldo sin conexión está, en la
práctica, vacío.

`CatalogJsonTest` exigía que **toda** clase de `labels.txt` tuviera ficha. Con 4 clases de
ejemplo se cumplía; con las 50 reales pasó a ser imposible y la suite quedó en rojo
(57 pruebas, 1 fallo). Además dos de las cuatro fichas —`incubadora` y `vortex`— eran de clases
que el modelo ya no puede emitir: no se mostrarían jamás, y ninguna prueba lo detectaba.

**Se invirtió la comprobación** a lo que sí es un invariante y sí detecta podredumbre:
*ninguna ficha puede apuntar a una clase que el modelo no detecta.* Se eliminaron las dos fichas
muertas; quedan `microscopio_binocular` y `camara_electroforesis`.

**Lo que deliberadamente NO se hizo: rellenar el catálogo con 48 fichas inventadas.** Fabricar
EPP, riesgos y procedimientos de encendido para equipos reales de laboratorio, que va a leer un
estudiante de primer semestre, es peligroso — es justo el tipo de contenido donde equivocarse
tiene consecuencias físicas. Y contradice la regla 6 de `CLAUDE.md`: `catalog.json` es el único
sitio de la app donde se muestra contenido sin que el estudiante vea la fuente. Las 50 clases las
tiene que responder el backend RAG, con los manuales reales y citando página.

**Si no estás de acuerdo, es tu llamada** y el cambio es de una línea en la prueba. Pero antes de
volver a exigir cobertura completa, ten en cuenta que eso obliga a inventar 48 fichas o a dejar
la suite en rojo indefinidamente.

Consecuencia que sí queda en pie: **el respaldo sin conexión cubre 2 de 50 clases.** Sin backend,
el estudiante toca casi cualquier caja y ve "Ficha no disponible". Es honesto, pero conviene
saberlo antes de la demostración.

### 2.2 La alineación de las cajas nunca se ha visto con un modelo real

Las tres pruebas visuales de F2 (a, b, c) se hicieron con `StubDetector`, que emite cajas
**fijas en el frame**. Comprueban el centrado, el giro y el espejado, pero **no demuestran que
una caja caiga encima del objeto que la provocó** con este modelo.

Hubo una comprobación parcial en su momento con el YOLOv8n de COCO (una persona con la cámara
frontal), pero nunca con el modelo de la UTEQ ni con `coordsNormalized: false`, que es un
camino del decodificador que hasta ahora **jamás se había ejecutado contra un modelo real**.

Es media hora con el teléfono apuntando a un equipo. Si las cajas salen corridas o con la
escala mal, el sospechoso número uno es esa ruta de `YoloDecoder`.

### 2.3 Cuando el backend esté levantado

No hay que recompilar nada: **Ajustes → URL del servidor**, y escribir `http://<IP-del-PC>:8000/`.

Puedes probarlo **hoy mismo**, antes de que existan los manuales: el backend arranca con el
índice vacío y responde el contrato completo. Va a decir siempre "información insuficiente",
que es lo correcto, pero valida toda la ruta de red de punta a punta.

```
GET  /api/health  → {"status":"ok","indexedDocuments":0,"model":"claude-haiku-4-5"}
POST /api/chat    → {"answer":"No dispongo de información suficiente...",
                     "hasSufficientContext":false,"sources":[]}
```

Está en el repositorio aparte `C:\Users\Mario\PythonProjects\labscan-rag`, con su README.
Hace falta abrir el puerto 8000 en el firewall de Windows o el teléfono no llega.

### 2.4 Defecto cosmético que sigue abierto desde F7

La etiqueta de una caja pegada al borde superior se dibuja sobre la barra de estado y se
solapa con el reloj. El overlay respeta los límites del `Canvas` pero no los *insets* del
sistema. No afecta al mapeo. Con 50 clases y cajas por toda la pantalla se va a ver más que
antes.

---

## 3. Dos cosas que corrigen supuestos de la documentación

### 3.1 La línea base de rendimiento de F3/F7 estaba mal

La bitácora dice que el YOLOv8n de COCO pesaba **3,2 MB** y daba ~100 ms de inferencia en
float32 a 640. **Ese modelo no podía ser float32**: 3,2 M de parámetros a 4 bytes son ~12,8 MB.
Era un modelo con pesos cuantizados y tensores de E/S en float32 — de ahí que el detector
tomara el camino float32 por introspección y todo pareciera coherente.

El modelo de Mario pesa **12,2 MB** y sí es float32 completo. Corre a **234–307 ms**, ~3,2 FPS.

**No es una regresión: es el primer float32 real que ejecuta este proyecto.** Las proyecciones
de la tabla de rendimiento de `docs/INTEGRACION_MODELO.md` (las filas de int8 y de 416) están
calculadas sobre esa base equivocada y hay que rehacerlas cuando exista el export int8. Mario
lo tiene en su lista.

### 3.2 El contrato de `docs/INTEGRACION_MODELO.md` da NHWC por sentado

La sección 4 dice que la entrada es `[1, 640, 640, 3]`, y `YoloTfliteDetector.kt` lo asume:
lee `inputSize = inputShape[1]`. El primer export de Mario vino en **NCHW** `[1, 3, 640, 640]`,
con lo que `inputSize` habría valido **3**.

**El fallo es silencioso**, y eso es lo que merece tu atención: `DetectorFactory` no lo detecta
al construir (el `require` solo comprueba que el tensor tenga 4 dimensiones) y
`FrameAnalyzer.analyze()` atrapa la excepción por frame. Resultado: la app dice *"Detector real
activo"*, no muestra la banda de modo demostración, **y no detecta nada**. La única pista es
`adb logcat`.

**✅ Resuelto.** Además de reexportar en NHWC, `YoloTfliteDetector.validateInputShape()` ahora
comprueba al construir que sean 4 dimensiones, que el eje de canales sea el último, que valga 3
y que la entrada sea cuadrada. Si algo no encaja lanza `ModelMismatchException`, que
`DetectorFactory` ya atrapa para caer al `StubDetector` **con el motivo visible en pantalla** —
que es lo que la regla 2 de `CLAUDE.md` pretendía desde el principio.

La función es `internal` y pura para poder probarla en la JVM, igual que `BoxMapper`.
`YoloInputShapeTest` la cubre con 5 casos, incluido el `[1, 3, 640, 640]` que causó el problema.
Para el modelo actual el comportamiento es idéntico: devuelve 640, lo mismo que `inputShape[1]`.

Revísalo si quieres — es el cambio más invasivo que hice en tu código.

---

## 4. Lo que NO te toca, para que no lo dupliques

Todo esto es de Mario y está en `MARIO2.md`:

- Export int8 del modelo (los ~3,2 FPS actuales).
- Corregir las clases del dataset en Roboflow y reentrenar.
- Digitalizar los manuales del laboratorio.
- Comprar el crédito de la API de Anthropic.
- El backend RAG: ya está construido, probado y commiteado.

---

## 5. Al bajarte los cambios

**La app instalada en el teléfono está firmada con la clave de depuración de la PC de Mario**,
así que tu `installDebug` va a fallar con `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Se arregla
desinstalando primero:

```
adb uninstall ec.edu.uteq.labscan
```

**`model.tflite` son 12,2 MB de binario y quedaron versionados en el repositorio.** No estaba en
`.gitignore` y la app no arranca en modo real sin él, así que se commiteó para que puedas
compilar y probar sin pedirle el archivo a nadie. Si prefieres sacarlo de la historia y
distribuirlo aparte, es una decisión razonable — pero hazla pronto, porque cada versión nueva del
modelo añade otros 12 MB, y déjala escrita en `docs/DECISIONES.md`.

## 6. Lo primero que deberías hacer — y solo lo puedes hacer tú

**El teléfono es tuyo.** Mario no lo tiene, así que **nada de lo hecho el 3 y el 5 de septiembre
está verificado en dispositivo**. Ni el endurecimiento del detector, ni el cambio de
`catalog.json`, ni el modelo nuevo del día 5. Las 63 pruebas JVM pasan y `assembleDebug` está en
verde, pero eso no sustituye a verlo funcionando, y en este proyecto ya hubo un fallo que
**solo** se manifestaba en el teléfono y era invisible desde las pruebas (D-028).

```
adb uninstall ec.edu.uteq.labscan
./gradlew installDebug
adb logcat -s LabScan
```

**1. Que carga y detecta.** Tiene que aparecer:

```
Modelo cargado: entrada 1x640x640x3 FLOAT32, salida 1x54x8400 FLOAT32, 50 clases, TRANSPOSED
Detector real activo con model.tflite
```

Si en vez de eso sale la banda de modo demostración, mira el motivo en Diagnóstico: desde D-029
un modelo con la forma equivocada **sí** avisa en pantalla en lugar de fallar callado.

**2. Los FPS del modelo nuevo, que es la cifra que falta en la tabla de D-030.** El log de
detecciones los trae:

```
Detecciones (NNN ms total, NNN ms inferencia): <clase> NN%
```

Los 275 ms documentados son del modelo **anterior**. Anota los del nuevo en `docs/PROGRESO.md`.

**3. Que las cajas caen encima del equipo.** Es la verificación que nunca se ha hecho con un
modelo real: las tres pruebas visuales de F2 se hicieron con `StubDetector` y cajas fijas, y el
camino `coordsNormalized: false` del decodificador **jamás se ha ejecutado contra un modelo real
en pantalla**. Apunta a un equipo del laboratorio y mira si el recuadro lo envuelve o está
corrido.

Si algo de esto sale mal, avísale a Mario: los tres puntos dependen del modelo, no de tu código.
