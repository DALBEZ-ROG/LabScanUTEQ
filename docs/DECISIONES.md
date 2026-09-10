# Bitácora de decisiones técnicas

Toda decisión no trivial se registra aquí: qué se decidió, por qué, qué se descartó y qué
consecuencias tiene. Regla 8 de CLAUDE.md: **no se agrega ninguna dependencia sin una
entrada en este archivo.**

Formato: `D-NNN — título — fase — fecha`.

---

## D-001 — Se usa el `Interpreter` crudo de TFLite, no la Task Library — F0 — 2026-08-26

**Decisión.** La inferencia del modelo YOLO se hará con `org.tensorflow.lite.Interpreter`
directamente, escribiendo a mano el letterbox, la descuantización, la decodificación del
tensor y el NMS. Se descartan `tensorflow-lite-task-vision` (Task Library) y MediaPipe Tasks.

**Por qué.**

1. **La Task Library exige metadatos que un export de Ultralytics no trae.**
   `ObjectDetector.createFromFileAndOptions()` lee los *TFLite Metadata* incrustados en el
   `.tflite` para saber la normalización de entrada y el mapa de etiquetas. El export de
   `model.export(format="tflite", int8=True, nms=False)` no los escribe, así que la Task
   Library falla al cargar el modelo con un error de metadatos, no de inferencia.

2. **La Task Library asume la salida de un SSD, no la de un YOLOv8.** Espera cuatro
   tensores separados (cajas, clases, puntajes, número de detecciones), que es la firma de
   los detectores del Model Zoo de TensorFlow. YOLOv8 devuelve **un solo** tensor
   `[1, 4 + N, 8400]`, transpuesto y sin dimensión de *objectness*. No hay forma de que la
   Task Library lo interprete: no es un problema de configuración, es otra forma de salida.

3. **El NMS lo hace la app a propósito.** Se exporta con `nms=False`
   (docs/INTEGRACION_MODELO.md, 2.3) para que el modelo quede más simple y portable. Eso
   implica que alguien tiene que hacer la supresión de no máximos, y ese alguien es
   `YoloDecoder`. La Task Library no permite intervenir en ese punto.

4. **Se necesita ver el tensor crudo.** El riesgo número dos del proyecto es la
   decodificación de la salida (docs/PLAN_FASES.md, 3). La pantalla de Diagnóstico tiene
   que mostrar forma, tipo, `scale`, `zero_point` y rango real de los valores. El
   `Interpreter` expone todo eso con `getInputTensor()` / `getOutputTensor()`; la Task
   Library lo oculta por completo.

5. **MediaPipe Tasks tiene el mismo problema, agravado.** Su `ObjectDetector` también
   depende de metadatos y además arrastra su propio grafo y sus assets, lo que suma peso
   al APK sin resolver nada de lo anterior.

**Qué cuesta.** Aproximadamente 700 líneas propias entre `Letterbox`, `YoloDecoder`,
`ModelConfig` y `YoloTfliteDetector`, y la responsabilidad de la conversión YUV a RGB.
Es el costo previsto en docs/PLAN_FASES.md y se acepta.

**Qué se gana.** El modelo se puede cambiar sin tocar código Kotlin: basta reemplazar los
assets y editar `model_config.json` (`outputLayout`, `quantized`, `coordsNormalized`,
`inputSize`). Esa propiedad es la que permite que la app y el modelo avancen en paralelo.

**Consecuencia de diseño.** `org.tensorflow` solo puede importarse dentro de
`detection/`. Es la regla 1 de CLAUDE.md y hace que cambiar de motor de inferencia en el
futuro sea un cambio local.

---

## D-002 — `compileSdk 37` con `targetSdk 35` — F0 — 2026-08-26

**Decisión.** `compileSdk = 37`, `targetSdk = 35`, `minSdk = 26`.

**Por qué.** El plan de F0 pedía `compileSdk 35`, pero el proyecto ya venía generado con
AGP 9.3.2 y Gradle 9.5, y esa combinación no lo permite. El build lo rechaza de forma
explícita:

```
Dependency 'androidx.compose.ui:ui-android:1.12.0' requires libraries and applications
that depend on it to compile against version 37 or later of the Android APIs.
:app is currently compiled against android-36.
```

Se intentó primero con 36 y falló por lo mismo. Como `compileSdk` solo determina contra
qué APIs se **compila**, subirlo no cambia el comportamiento de la app en el dispositivo.

**Lo que sí importa se mantiene:** `targetSdk` sigue en 35 y `minSdk` en 26, tal como fija
CLAUDE.md. El comportamiento en tiempo de ejecución es exactamente el planificado.

**Alternativa descartada.** Bajar a AGP 8.x y Gradle 8.x para poder usar `compileSdk 35`.
Habría obligado a degradar el wrapper de Gradle y a pelear con la versión de Android
Studio instalada, a cambio de nada: ninguna API de 36 ni de 37 se usa en el código.

---

## D-003 — Kotlin integrado de AGP 9 en vez del plugin `kotlin-android` — F0 — 2026-08-26

**Decisión.** No se aplica `org.jetbrains.kotlin.android`. Se usa el soporte de Kotlin
integrado de AGP 9 (`android.builtInKotlin`, activo por defecto) y solo se aplican los dos
plugins de compilador que el proyecto necesita: `org.jetbrains.kotlin.plugin.compose` y
`org.jetbrains.kotlin.plugin.serialization`, ambos en 2.4.10.

**Por qué.** El plan pedía Kotlin 2.0.x con el plugin clásico. Con AGP 9 eso no compila.
El propio build lo dice:

```
The 'org.jetbrains.kotlin.android' plugin is not compatible with AGP's 9.0 new DSL
(`android.newDsl=true` is enabled by default).
Solution: Set `android.builtInKotlin=true` in `gradle.properties` and migrate to
built-in Kotlin.
```

Se probó con Kotlin 2.2.10 y con 2.4.10, y también con `android.builtInKotlin=false`: las
tres rutas fallan al aplicar el plugin. La ruta soportada es la integrada.

**Consecuencias.**

- No hay bloque `kotlinOptions`; el objetivo de bytecode se fija en `kotlin { compilerOptions
  { jvmTarget.set(JvmTarget.JVM_17) } }`. Verificado: las clases salen con *major version*
  61, es decir Java 17, como pedía el plan.
- El compilador de Compose ya no necesita `composeOptions`; lo gestiona el plugin
  `kotlin.plugin.compose`.
- Si en el futuro hiciera falta KSP o Room, habría que revisar su compatibilidad con el
  Kotlin integrado antes de agregarlos.

---

## D-004 — Dependencias declaradas por adelantado en F0 — F0 — 2026-08-26

**Decisión.** El version catalog declara ya todas las dependencias de las ocho fases,
aunque F0 no use ninguna salvo Compose y Navigation.

**Por qué.** Evita que cada fase se abra con una tanda de descargas y un build roto por
un conflicto de versiones descubierto tarde. Todo el árbol de dependencias queda resuelto
y compilado una sola vez, aquí.

**Registro de dependencias, según la regla 8 de CLAUDE.md:**

| Dependencia | Versión | Fase que la usa | Motivo |
|---|---|---|---|
| CameraX (core, camera2, lifecycle, view) | 1.4.2 | F1-F2 | Vista previa y `ImageAnalysis`. Se fija 1.4.x por CLAUDE.md aunque exista 1.6.x |
| `tensorflow-lite` | 2.17.0 | F3 | `Interpreter` crudo. Ver D-001 |
| `tensorflow-lite-gpu` | 2.17.0 | F3 | Delegado GPU opcional, según `useGpuDelegate` |
| Retrofit | 2.12.0 | F5 | Cliente HTTP del backend RAG |
| `retrofit2-kotlinx-serialization-converter` | 1.0.0 | F5 | Convertidor de kotlinx en vez de Gson o Moshi: no requiere reflexión |
| OkHttp + `logging-interceptor` | 4.12.0 | F5 | Timeouts y trazas HTTP solo en debug |
| `kotlinx-serialization-json` | 1.9.0 | F3, F4, F5 | `model_config.json`, `catalog.json` y los DTO del contrato |
| `navigation-compose` | 2.9.8 | F0 en adelante | Navegación entre scanner, ficha, chat y diagnóstico |
| `lifecycle-viewmodel-compose` | 2.9.4 | F2 en adelante | ViewModel sin framework de DI |
| `datastore-preferences` | 1.2.1 | F7 | Ajustes persistentes: umbrales, cámara, delegado GPU |

Se descartó Room (el catálogo es un JSON de solo lectura dentro del APK), Hilt y Koin (la
inyección es manual vía `AppContainer`), y Gson y Moshi (kotlinx.serialization ya entra con
el plugin de Kotlin).

**Efecto secundario a vigilar.** El APK de depuración pesa unos 44 MB, porque
`tensorflow-lite-gpu` incluye librerías nativas de todas las ABI. F7 debe recortarlo con
*splits* por ABI o `abiFilters`. En release, con R8 activo, el peso previsto vuelve al
rango de 18-26 MB de docs/PLAN_FASES.md.

---

## D-005 — La app fuerza el tema oscuro — F0 — 2026-08-26

**Decisión.** `LabScanTheme` usa la paleta oscura siempre, sin consultar
`isSystemInDarkTheme()`. El acento es el verde institucional #009B4C.

**Por qué.** La pantalla principal es una vista de cámara a pantalla completa. Un fondo
claro deslumbra en el laboratorio y le resta contraste a los cuadros de detección, que son
lo único que el usuario necesita leer. El tema XML de arranque también va en negro para que
no haya un destello blanco antes de que la cámara se enganche.

**Detalle de contraste.** #009B4C se usa como `primary` en rellenos, pero para texto fino y
bordes sobre el fondo oscuro se usa `UteqGreenLight` (#3ED184), que sí alcanza la relación
4.5:1. El parámetro `darkTheme` se deja expuesto para poder generar capturas en claro para
el informe.

---

## D-006 — Configuración de CameraX que F2 no puede cambiar — F1 — 2026-08-26

**Sin dependencias nuevas.** F1 se resolvió entera con lo declarado en F0. En particular,
`ProcessCameraProvider.awaitInstance()` ya existe en CameraX 1.4.2 como función `suspend`,
así que no hizo falta `kotlinx-coroutines-guava` ni envolver el `ListenableFuture` a mano.

Tres decisiones de F1 condicionan el mapeo de coordenadas de F2, que es la parte frágil del
proyecto. Quedan aquí para que no se toquen por descuido:

1. **`AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY`**, expuesta como
   `CameraBinder.ASPECT_RATIO_STRATEGY`. F2 **debe** construir su `ImageAnalysis` con esta
   misma constante. Si `Preview` e `ImageAnalysis` piden relaciones distintas, CameraX
   recorta cada uno por su lado y las cajas quedan desplazadas respecto a lo que se ve.

2. **`PreviewView.ScaleType.FILL_CENTER`.** Recorta para llenar la pantalla, y ese recorte
   es una de las transformaciones que `BoxMapper` tiene que deshacer.

3. **`PreviewView.ImplementationMode.COMPATIBLE`** (`TextureView`) en lugar de
   `PERFORMANCE` (`SurfaceView`). Cuesta algo más de memoria, pero un `SurfaceView` se
   dibuja en su propia capa, por debajo de la ventana, y superponerle un `Canvas` de
   Compose da problemas de orden de dibujo y de transformaciones. El overlay de F2 es
   exactamente eso, así que se paga el costo.

**Estado del permiso.** Se modela con tres valores y no con un booleano, porque
`shouldShowRequestPermissionRationale()` devuelve `false` en dos situaciones opuestas:
cuando nunca se preguntó y cuando el usuario denegó para siempre. La bandera "ya
preguntamos" va en `rememberSaveable`, de modo que sobreviva a la muerte del proceso; sin
eso, tras volver de segundo plano la app ofrecería un botón que el sistema ya no atiende.

**Orientación fija en vertical.** Se mantiene el `screenOrientation="portrait"` que puso
F0. La app se usa apuntando a un equipo y la rotación agrega una transformación más a la
cadena de `BoxMapper` sin aportar nada al caso de uso. El código de F1 es correcto igual
si se quita: el estado vive en el ViewModel y el enganche se rehace solo. Para permitir la
rotación basta con borrar esa línea del manifiesto.

---

## D-007 — La caja de calibración se define sobre el frame, no sobre el cuadrado del modelo — F2 — 2026-08-26

**Sin dependencias nuevas.** F2 se resolvió con lo declarado en F0.

**El problema.** El plan de F2 pedía que `StubDetector` devolviera una caja en
`(0.25, 0.25)-(0.75, 0.75)` del **espacio del modelo**, y a la vez que esa caja se viera
*"exactamente centrada y ocupando la mitad central de la pantalla"*. Las dos cosas juntas
no son posibles, porque el espacio del modelo incluye las bandas grises del letterbox.

**Los números.** Frame de análisis 1280×720, modelo de 640, pantalla 1080×2400:

| Caja | En el frame | En la pantalla |
|---|---|---|
| `0.25-0.75` del **modelo** | x 25,0 %-75,0 % · y 5,6 %-94,4 % | x **−5,6 %-105,6 %** · y 25,0 %-75,0 % |
| `0.25-0.75` del **frame** | x 25,0 %-75,0 % · y 25,0 %-75,0 % | x 18,8 %-81,3 % · y 25,0 %-75,0 % |

Tomada sobre el cuadrado del modelo, la caja se sale de la pantalla por los dos lados: sus
bordes verticales quedan fuera y no hay forma de comprobar el centrado a ojo, que es
justamente para lo que existe. La segunda caja del plan, `(0.05, 0.60)-(0.35, 0.90)`, sale
todavía peor: su borde inferior cae en `y = 121 %` del frame, o sea dentro del relleno gris,
fuera de la imagen real.

**Decisión.** `StubDetector` interpreta sus constantes como fracciones **del frame** y las
convierte al espacio del modelo aplicando el letterbox hacia adelante. Es lo que pide la
verificación obligatoria de CLAUDE.md, que dice literalmente *"una caja fija en el 25 %-75 %
**del frame**"*. El contrato de `Detection.box` no cambia: sigue siendo espacio del modelo,
que es lo que devolverá el YOLO real en F3.

**Lo que sigue sin poder cumplirse, y no es un fallo.** Ni siquiera con la caja relativa al
frame se ve *"la mitad central"* en los dos ejes: `FILL_CENTER` recorta el eje que sobra, así
que en un teléfono 20:9 la caja sale al 18,8 %-81,3 % en horizontal. El invariante que sí se
cumple siempre, y es el que detecta cualquier error de espejado, de relleno o de
desplazamiento, es que **la caja queda perfectamente centrada**. Por eso el overlay dibuja,
solo en depuración, una cruz en el centro exacto de la vista.

**Riesgo aceptado.** Que el stub aplique el letterbox hacia adelante y `BoxMapper` lo
invierta con la misma función `letterboxParams` significa que un error en esa función se
cancelaría y no se vería. Se cubre con una prueba unitaria propia de `letterboxParams`,
independiente de `BoxMapper`.

---

## D-008 — `Preview` e `ImageAnalysis` comparten `ResolutionSelector` — F2 — 2026-08-26

`CameraBinder` construye **un solo** `ResolutionSelector`, con
`CameraBinder.ASPECT_RATIO_STRATEGY` (16:9 con reserva automática), y se lo pasa a los dos
casos de uso.

**Por qué es obligatorio.** CameraX elige la resolución de cada caso de uso por separado. Si
`Preview` y `ImageAnalysis` piden relaciones de aspecto distintas, el sensor se recorta de
forma distinta para cada uno: el overlay dibujaría cajas calculadas sobre un encuadre que no
es el que el usuario está viendo. El desplazamiento resultante es pequeño y constante, que es
la peor clase de error: parece un problema de redondeo y no lo es.

**Cómo se garantiza.** La estrategia es una constante del `companion object` de
`CameraBinder`, y los dos `Builder` reciben la misma instancia de `ResolutionSelector`
construida en la misma línea. No hay forma de cambiar uno sin cambiar el otro.

**Consecuencia para F3.** El detector real no puede pedir otra resolución de entrada por su
cuenta. Su `inputSize` afecta al letterbox, no al `ImageAnalysis`.

---

## D-009 — La forma del tensor manda sobre `model_config.json` — F3 — 2026-08-26

**Sin dependencias nuevas.** `tensorflow-lite` y `tensorflow-lite-gpu` ya estaban declaradas
desde F0 (D-004).

**Decisión.** Cuando `model_config.json` y la forma real del tensor se contradicen, gana la
forma real, y la pantalla de Diagnóstico avisa de que hubo corrección.

Se aplica a tres cosas:

| Dato | De dónde sale de verdad |
|---|---|
| `inputSize` | `interpreter.getInputTensor(0).shape()[1]` |
| Número de clases | eje de canales de la salida menos 4 |
| `outputLayout` | el eje **corto** de `[1, a, b]` es siempre el de canales |

`model_config.json` sigue mandando en lo que no se puede deducir: umbrales, `maxDetections`,
`coordsNormalized` y `useGpuDelegate`.

**Por qué.** El error más frecuente al integrar un modelo entrenado por otra persona es un
`model_config.json` copiado de otro export. Si la app se fía de él, falla de formas confusas:
cajas en sitios absurdos, o un `IndexOutOfBounds` a mitad del decodificador. Deducirlo del
tensor convierte un error silencioso en un dato visible.

**Lo comprobado en el dispositivo.** El modelo COCO de prueba declara entrada y salida en
`FLOAT32`, mientras que `model_config.json` decía `"quantized": true`. El detector tomó el
camino float32 por introspección y funcionó a la primera. Si se hubiera fiado de la
configuración, habría escrito bytes int8 en un buffer float32.

**Excepción deliberada.** Si `labels.txt` no cuadra con el número de clases del tensor, la
app **no** intenta arreglarlo: lanza `ModelMismatchException`, cae a `StubDetector` y lo
enseña. Adivinar ahí significaría poner nombres equivocados a detecciones correctas, que es
peor que no detectar.

---

## D-010 — Reserva única de buffers, fuera del bucle de inferencia — F3 — 2026-08-26

`YoloTfliteDetector` reserva en su constructor, y nunca más: el `ByteBuffer` de entrada
(directo, orden nativo), el de salida, el `IntArray` de píxeles, el `FloatArray` de la salida
descuantizada y el `Bitmap` del letterbox, ahora en `LetterboxScaler`.

**Por qué.** Un bitmap de 640×640 en ARGB_8888 son 1,6 MB. Crearlo por frame, a 30 frames por
segundo, son 48 MB/s de basura: el recolector se dispara y se lleva por delante los FPS y la
fluidez de la vista previa. El `letterbox()` de F2 hacía justo eso, así que se sustituyó por
`LetterboxScaler`, que redibuja siempre sobre el mismo bitmap.

**Consecuencia.** `LetterboxScaler` y `YoloDecoder` **no** son seguros entre hilos. No pasa
nada porque el detector solo se usa desde el hilo único de `ImageAnalysis`, pero si alguna
vez se paraleliza la inferencia habrá que revisar esto primero.

**Lo que sí se asigna por frame,** y es aceptable: la lista de candidatos que superan el
umbral, un puñado de objetos pequeños, y los `Detection` que se devuelven. El `ArrayList` de
candidatos se reutiliza; lo que se crea son los `Detection` finales, que la UI necesita
inmutables.

---

## D-011 — Un solo modelo de datos para el backend y para el catálogo local — F5 — 2026-08-27

Los DTO viven en `data/remote/dto/` y son **los mismos** para las dos fuentes: la respuesta de
`GET /api/equipment/{classId}` y las fichas de `assets/catalog.json`. No hay modelo de dominio
intermedio ni conversión entre capas.

**Por qué.** `catalog.json` no es "otro formato de datos": son literalmente respuestas del
contrato guardadas dentro del APK. Un segundo modelo con su mapeador solo añadiría un sitio
donde los dos pueden dejar de coincidir en silencio, que es justo el fallo que se quiere
evitar cuando la mitad de las fichas llegan por red y la otra mitad no. La prueba
`CatalogJsonTest` deserializa el catálogo con estos DTO en modo estricto, así que si alguien
edita el JSON a mano y se equivoca en un campo, la compilación se cae.

**Ningún campo lleva `@SerialName`.** Se revisó el contrato entero: los diez campos de
`EquipmentDto`, los tres de `HealthDto`, los tres de `ChatResponseDto` y los dos del sobre de
error ya son camelCase y coinciden con el nombre idiomático en Kotlin. El único `@SerialName`
del proyecto está en `ChatRole`, para traducir `USER`/`ASSISTANT` a `"user"`/`"assistant"`.

**Lo que sí se envuelve.** `RagRepository` no devuelve el DTO pelado sino un `Equipment`, que
añade `origin` (red o caché) y el `RagError` que provocó la caída. Eso es estado de esta
consulta concreta, no un dato del equipo, y por eso no toca el contrato.

---

## D-012 — Cleartext solo en la variante debug, y sin subredes — F5 — 2026-08-27

`network_security_config.xml` vive en `app/src/debug/res/xml/`, enganchado por una superposición
de manifiesto en `app/src/debug/AndroidManifest.xml`. La variante de release no tiene el archivo,
no declara `networkSecurityConfig` y por tanto hereda el comportamiento por defecto de
targetSdk 35: **todo el tráfico en claro prohibido**.

**Por qué así y no con un `usesCleartextTraffic` en el manifiesto principal.** Un permiso de
desarrollo puesto en el manifiesto común acaba tarde o temprano en la app publicada. Poniéndolo
en el `sourceSet` de debug no hay forma de que se cuele: el archivo simplemente no existe en el
APK de release.

**Limitación conocida.** El formato de Android **no admite subredes**: `<domain>` acepta nombres
de dominio e IP literales, pero no `192.168.0.0/16`. La petición original pedía "10.0.2.2 y la
subred local"; la parte de la subred no se puede expresar. Están declarados `10.0.2.2` (host del
PC visto desde el emulador), `10.0.3.2` (Genymotion), `localhost` y `127.0.0.1`. Para probar
contra el PC desde un teléfono real hay que agregar a mano su IP LAN en ese archivo **y** en el
`BASE_URL` de la variante debug. Está escrito como comentario dentro del propio XML.

**Alternativa descartada.** `<base-config cleartextTrafficPermitted="true">` en debug habría
cubierto cualquier IP de la LAN sin listarla, pero también cualquier host de internet. Para una
app que en clase se conecta a wifi compartida, eso es un precio peor que editar una línea.

---

## D-013 — El mock es un interceptor de OkHttp, no un `MockWebServer` — F5 — 2026-08-27

`MockInterceptor` se instala en el cliente OkHttp cuando `BuildConfig.USE_MOCK_API` es `true` y
responde desde `assets/mock/`. El contrato mencionaba `MockWebServer` como alternativa.

**Por qué el interceptor.** `MockWebServer` es una dependencia de pruebas que hay que arrancar y
apagar, y que escucha en un puerto: para que la app de depuración instalada en el teléfono
hablara con él habría que levantarlo dentro del proceso de la app y reescribir `BASE_URL` en
caliente. El interceptor no necesita nada de eso: la petición sale por la misma `BASE_URL`
real, atraviesa Retrofit y el convertidor igual que siempre, y solo se corta en el último
tramo. Eso hace que lo que se prueba sea **todo el cableado menos el socket**, que es
exactamente lo que interesa verificar antes de que exista el backend.

**Lo que el mock cubre a propósito.**

- `equipment_camara_electroforesis.json` **no existe**. Es la única forma de ejercitar el camino
  404 → catálogo local sin apagar el wifi. `MockAssetsTest` falla si alguien completa las cuatro
  clases y deja ese camino sin probar.
- `/api/chat` **alterna** entre `chat.json` (`hasSufficientContext: true`) y
  `chat_sin_contexto.json` (`false`), por número de petición. F6 puede ver los dos estilos de la
  interfaz sin tocar código.
- Las fichas de mock traen **más fuentes** que las del catálogo local. Así, en pantalla, se
  distingue de un vistazo si el dato vino del servidor o del APK.
- Retardo artificial de 350 ms. Sin él, la respuesta llega tan rápido que el indicador de carga
  no se ve nunca y no hay manera de comprobar que funciona.

**Deuda para F7.** `assets/mock/` viaja también en el APK de release, donde nada lo lee. Son
unos 7 KB; conviene moverlo a `app/src/debug/assets/` al preparar la compilación de entrega.

---

## D-014 — La ficha técnica se desplaza; el `ModalBottomSheet` no basta — F5 — 2026-08-27

El `Column` interior de `EquipmentSheet` lleva `verticalScroll(rememberScrollState())`.

**Por qué se anota.** Es fácil creer que un `ModalBottomSheet` ya trae desplazamiento porque se
arrastra: lo que se arrastra es la hoja, no su contenido. Sin `verticalScroll`, la ficha se
corta por donde termine la pantalla y no hay ningún gesto que lleve más abajo. Se descubrió al
abrir la hoja por primera vez en un dispositivo, el 2026-08-27: la ficha del microscopio se
cortaba en el paso 1 del procedimiento, y **riesgos, prácticas, fuentes y los dos botones de
acción eran inalcanzables**.

**Por qué era grave y no cosmético.** La regla 6 de CLAUDE.md exige que toda información muestre
su fuente. Las fuentes se estaban dibujando, pero ningún usuario podía llegar a verlas. Una
regla que se cumple en el código y no en la pantalla no se cumple.

**Orden de los modificadores.** `verticalScroll` va antes del `padding` horizontal, para que la
zona de arrastre ocupe todo el ancho y no deje dos franjas muertas de 24 dp a los lados.

**Nota para F6.** Cuando el chat entre en esta misma hoja habrá que revisar esto: un área de
texto desplazable dentro de otra desplazable dentro de una hoja arrastrable son tres gestos
compitiendo por el mismo dedo.

---

## D-015 — El tope de 6 turnos vive solo en el repositorio — F6 — 2026-08-27

`ChatViewModel` manda el historial **completo** a `RagRepository.chat`, que es quien lo recorta
a los últimos seis turnos con `truncateForRequest()`.

**Por qué.** El tope de 6 lo fija `docs/CONTRATO_API.md`, no la pantalla. Si el ViewModel
también recortara, habría dos sitios que hay que cambiar el día que el backend admita ocho, y
uno de los dos se quedaría sin cambiar. El repositorio es la frontera con el contrato: el
recorte pertenece ahí, junto a la constante que lo nombra y a la prueba que lo verifica
(`RagErrorTest.el historial se recorta a los ultimos seis turnos`).

**Consecuencia que hay que recordar.** El ViewModel guarda la conversación entera en memoria
para poder pintarla; lo que se recorta es lo que **viaja**, no lo que se ve. Un estudiante
puede desplazarse hacia arriba y leer sus veinte preguntas anteriores aunque el backend solo
haya visto las seis últimas.

---

## D-016 — Un solo motor de voz para toda la app, soltado en `AppContainer.close()` — F6 — 2026-08-27

`TtsManager` y `SttManager` se construyen una vez en `AppContainer` y viven lo que vive el
proceso. Las pantallas llaman a `stop()`, nunca a `shutdown()`.

**Por qué.** `TextToSpeech` se enlaza con un servicio de otro proceso y tarda décimas de segundo
en estar listo. Si cada pantalla construyera el suyo, la primera respuesta de cada visita al
chat llegaría muda: el motor todavía estaría arrancando cuando se le pide hablar. Con
`SpeechRecognizer` el motivo es distinto pero apunta igual: es un recurso del sistema y dos
instancias escuchando a la vez se estorban con `ERROR_RECOGNIZER_BUSY`.

**El hueco que esto deja, y cómo se tapa.** Un motor compartido sigue hablando aunque la
pantalla desaparezca. Por eso hay tres puntos de corte explícitos, y los tres son
`DisposableEffect` o llamadas directas, no efectos secundarios de la navegación:

1. `ChatScreen` y el scanner cortan en `onDispose`.
2. `ChatViewModel.send()` corta antes de enviar la siguiente pregunta.
3. El micrófono corta antes de abrirse, porque el reconocedor se oiría a sí mismo.

**Peticiones antes de tiempo.** `TtsManager.speak()` llamado antes de `onInit` no se pierde: se
guarda en `pendingText` y se dice en cuanto el motor responde. Sin eso, pedir la lectura
automática de la primera respuesta justo al abrir la app no sonaría nunca, y no habría ningún
error que lo explicara.

---

## D-017 — El permiso en tiempo de ejecución se extrae a `ui/common` — F6 — 2026-08-27

`rememberRuntimePermission(permission, requestOnFirstAppearance)` vive en
`ui/common/RuntimePermission.kt`. `ScannerScreen` perdió su copia privada de F1 y ahora la usa.

**Por qué.** El micrófono necesitaba exactamente el mismo comportamiento que la cámara, con sus
dos trampas: `shouldShowRequestPermissionRationale` devuelve `false` tanto si nunca se preguntó
como si se denegó para siempre, y volver desde los ajustes del sistema no dispara ningún
callback. Copiar y pegar esa lógica habría garantizado que una de las dos copias se quedara sin
arreglar el día que aparezca la tercera trampa.

**La única diferencia entre los dos usos, y es deliberada:** la cámara se pide **al entrar**,
porque sin ella el escáner es una pantalla negra; el micrófono **no**, porque el chat funciona
escribiendo y asaltar con un diálogo de permiso nada más abrirlo sería grosero. Se pide en el
primer intento de dictar. Eso es lo que controla `requestOnFirstAppearance`.

---

## D-018 — El delegado GPU estaba roto, y aun arreglado la CPU gana — F7 — 2026-08-27

Se agregó `org.tensorflow:tensorflow-lite-gpu-api`, que faltaba. `useGpuDelegate` sigue en `false`.

**El defecto.** Desde F3, `GpuDelegate()` lanzaba `NoClassDefFoundError: GpuDelegateFactory$Options`
en cuanto se activaba la bandera. El artefacto `tensorflow-lite-gpu` trae la biblioteca nativa pero
**no** las clases Java que la envuelven; esas viven en `tensorflow-lite-gpu-api`. El repliegue a CPU
funcionó exactamente como manda la regla 2 de CLAUDE.md, así que el fallo nunca se notó: la app
seguía detectando y nadie miraba el log. Un delegado que no se puede construir es peor que no
tenerlo, porque la bandera del contrato miente.

**Lo medido, con el delegado ya funcionando.** SM-A566E, YOLOv8n float32 a 640, mediana de 30
inferencias:

| | Inferencia | Total | Veredicto |
|---|---|---|---|
| CPU, 4 hilos | 100 ms | **107 ms** | Gana |
| GPU | 73 ms | 120 ms | Pierde |

La inferencia baja de verdad, pero el total sube. El motivo es que con el delegado la llamada a
`Interpreter.run` devuelve antes de que la GPU haya terminado, y la espera reaparece al leer el
tensor de salida, fuera del cronómetro. Contando la operación completa, la GPU sale perdiendo en
este dispositivo.

**Se deja instalado igualmente.** La bandera es parte de `model_config.json` y del contrato con
quien entrena el modelo. Con un modelo int8 el reparto puede invertirse, y ahora se puede
comprobar cambiando un `true` en un JSON en lugar de descubrir que la opción nunca existió.

---

## D-019 — El coste no estaba donde parecía: 86 ms se iban copiando buffers — F7 — 2026-08-27

`writeInput` y `readOutput` pasaron de recorrer el `ByteBuffer` elemento a elemento a llenar un
arreglo plano y volcarlo de una sola llamada.

**Lo que se creía.** Que el cuello de botella era la inferencia y que la única salida era un modelo
más pequeño.

**Lo que se midió.** De los 188 ms por frame, la inferencia eran 102 y los otros **86 ms** se iban
en preparar la entrada y leer la salida. Con `inputSize = 640` eso son 1 228 800 llamadas a
`putFloat` y 705 600 a `get`, cada una con su comprobación de límites, por frame.

| | Antes | Después |
|---|---|---|
| Preparación del frame | 86 ms | **6 ms** |
| Inferencia | 102 ms | 100 ms |
| Total | 188 ms | **107 ms** |

**La lección, que es la razón de esta entrada.** La optimización que de verdad hacía falta no estaba
en la lista que uno escribe antes de medir. Por eso `PerformanceStats` ahora separa la latencia
total de la de inferencia y la pantalla de Diagnóstico enseña las dos: sin ese desglose, cualquiera
que retome el proyecto repetiría la misma suposición equivocada.

**Los arreglos de trabajo se reservan una vez** en el constructor, y solo el camino que el tensor
real necesita. Medido con `art.gc.bytes-allocated`: **9 833 bytes por frame**, muy por debajo del
tope de 256 KB que vigila `DetectorBenchmarkTest`.

---

## D-020 — Se suaviza el dibujo de las cajas, no la detección — F7 — 2026-08-27

`BoxSmoother` guarda una posición dibujada por caja y la acerca a la última posición detectada en
cada fotograma, con `1 - exp(-dt / tau)` y `tau = 80 ms`.

**Por qué hacía falta.** El detector produce unas 9 listas por segundo y la pantalla refresca a 60 o
120 Hz: la caja se quedaba quieta seis o siete fotogramas y después saltaba. Encima, dos
inferencias seguidas sobre la misma escena nunca dan el mismo rectángulo, así que además temblaba.
En video se ve peor de lo que la app realmente es.

**Por qué depende del tiempo y no del fotograma.** Un factor fijo por fotograma haría que la caja se
moviera al doble de velocidad en una pantalla de 120 Hz. Con la exponencial sobre el tiempo
transcurrido el resultado se ve igual en cualquier dispositivo.

**Lo que NO hace, y conviene tenerlo claro:** no mejora la detección ni los FPS. La caja llega
exactamente al mismo sitio; lo único que cambia es que recorre el camino en lugar de teletransportarse.

**Dos casos que se tratan aparte.** Un salto de más de media pantalla no se interpola, porque
deslizar una caja de un extremo a otro parece un fallo y no una detección nueva; y una pausa de más
de medio segundo sin dibujar (la app estuvo en segundo plano) coloca las cajas directamente en su
sitio.

---

## D-021 — Almacén de firma dentro del repositorio, a propósito — F7 — 2026-08-27

`keystore/labscan-demo.jks` viaja en el repositorio y su contraseña está escrita en
`app/build.gradle.kts`.

**Por qué.** La entrega exige un APK release instalable y que un tercero pueda clonar el proyecto y
continuarlo. Con un almacén fuera del control de versiones, ese tercero no puede generar un release
sin pedirlo, y el `debug.keystore` de cada máquina produce firmas distintas, así que las
instalaciones se pisan entre sí. Un almacén conocido y compartido resuelve las dos cosas.

**Lo que esto significa.** Esta firma **no protege nada**, y no debe pretenderlo: cualquiera puede
firmar un APK que se haga pasar por este. Es aceptable porque la app no se distribuye por ninguna
tienda ni recibe actualizaciones firmadas. Si algún día se publicara, habría que generar un almacén
nuevo, dejarlo fuera del repositorio y pasarlo por variables de entorno; está escrito así en el
README y en el propio `build.gradle.kts`, junto al bloque de firma, que es donde alguien lo va a
leer.

---

## D-022 — El mock del backend sale del APK de entrega — F7 — 2026-08-27

`assets/mock/` se movió de `app/src/main/assets/` a `app/src/debug/assets/`.

**Por qué.** En release nadie lee esos JSON: `USE_MOCK_API` es `false` y el interceptor ni siquiera
se instala. Pero seguían viajando dentro del APK, y son fichas técnicas de laboratorio con aspecto
de dato real. Un revisor que abriera el APK encontraría contenido de ejemplo indistinguible del
verdadero.

**El intento que no funcionó, y merece quedar escrito.** Primero se probó con
`packaging { resources { excludes += "/assets/mock/**" } }`. No hace nada: ese bloque filtra
recursos de Java, no `assets/`. La compilación pasó, el APK se generó, y los archivos seguían
dentro. Solo se descubrió al abrir el APK con `unzip -l`. Lo único que saca assets de una variante
es ponerlos en el `sourceSet` de la otra.

**Efecto de paso.** `MockAssetsTest` ahora lee de `src/debug/assets/mock`, y sigue validando los
JSON contra los DTO del contrato.

---

## D-023 — La escucha continua no existe: se simula con reinicios — F8 — 2026-08-27

`ContinuousSttManager` reinicia la sesión de `SpeechRecognizer` cada vez que el sistema la cierra,
con esperas crecientes de 200, 400 y 800 ms y un tope de tres intentos seguidos.

**Por qué.** `SpeechRecognizer` **no tiene modo continuo**. Está pensado para una locución: llama a
`onEndOfSpeech` y después a `onResults` o `onError`, y a partir de ahí el micrófono está muerto
hasta que alguien vuelva a llamar a `startListening`. No hay ninguna bandera que lo cambie. La
escucha continua que pide F8, por tanto, no se puede activar: se construye.

**Lo que cuesta.** Cada reinicio abre un hueco de unos 200 ms en el que el micrófono no oye. Es la
razón por la que el fin de turno **no** se delega en `onResults` del sistema, que además tarda más
de dos segundos en decidir: se detecta por cuenta propia con 900 ms sin parciales nuevos.

**El tope de tres no es una optimización.** Sin él, un motor que falla siempre deja la app
reintentando para siempre, con la pantalla diciendo "Escuchando" y el micrófono cerrado. Es el peor
fallo posible en esta pantalla, porque desde fuera es idéntico a estar funcionando. El contador se
pone a cero en `onReadyForSpeech`: que el motor acepte la sesión demuestra que está sano, y sin eso
una sala en silencio agotaba el tope en medio minuto.

---

## D-024 — El cancelador de eco del sistema no se puede usar con `SpeechRecognizer` — F8 — 2026-08-27

`EchoControl` existe, comprueba disponibilidad y sabe engancharse a una sesión de captura, pero
**no es la defensa contra el eco** de este proyecto.

**Por qué.** `AcousticEchoCanceler` y `NoiseSuppressor` se enganchan a una sesión de audio concreta:
la del `AudioRecord` que captura. `SpeechRecognizer` crea y gestiona su `AudioRecord` por dentro, en
el proceso del motor de reconocimiento, y **no expone el identificador de esa sesión** por ninguna
vía pública. Sin ese identificador, `create()` no tiene a qué engancharse.

**Lo que habría que hacer para usarlos de verdad**: abandonar `SpeechRecognizer` y capturar con un
`AudioRecord` propio, lo que a su vez obliga a reconocimiento propio o en la nube. Las dos cosas
están prohibidas en este proyecto (CLAUDE.md: voz con APIs de plataforma; F8: nada de servicios de
voz en la nube).

**Lo que sí protege contra el eco**, y es lo que se verificó con el altavoz al máximo:

1. Mientras el asistente habla, el reconocedor pasa a `ListenMode.WATCH` y su texto **se descarta
   entero**. Aunque transcriba al altavoz, ese texto no llega a ninguna parte.
2. El umbral de amplitud para dar por buena una interrupción sube a 0,55 mientras habla el
   asistente, por encima del nivel que el altavoz devuelve al micrófono.
3. La interrupción exige 300 ms seguidos por encima de ese umbral, que una sílaba devuelta por el
   altavoz no alcanza a sostener.

En el Samsung A56 de prueba los dos efectos **sí** están disponibles (`eco=true, ruido=true` en el
log) y el propio motor de reconocimiento los aplica por su cuenta al capturar de
`VOICE_RECOGNITION`. Esa es la razón de que el eco se comporte razonablemente incluso antes de las
tres medidas de arriba.

---

## D-025 — El aviso de "sin conexión" es el único error que se dice en voz alta — F8 — 2026-08-27

F6 dejó escrito que los errores de red **nunca** se leen en voz alta, y sigue siendo la regla en el
chat escrito. En el modo de conversación por voz hay una excepción, y solo una: cuando no hay
conexión, la app lo dice una vez y sale del modo.

**Por qué la contradicción es deliberada.** El estudiante está con las manos ocupadas y puede no
estar mirando la pantalla; ese es el supuesto entero de la pantalla. Callarse equivaldría a dejarlo
hablándole a una app muerta sin ninguna señal de que no le oye. El resto de errores —fallo del
backend, contexto insuficiente— sí siguen la regla de F6: se muestran y no se leen, porque en esos
casos la app sí responde algo y el estudiante lo nota.

---

## D-026 — La app se interrumpía a sí misma: el foco de audio no se pide para escuchar — F8 — 2026-08-27

`VoiceCallViewModel` **no** pide foco de audio al entrar al modo de voz. Lo pide `TtsManager`, y
solo mientras lee una respuesta. Además, la pausa por pérdida de foco espera 500 ms antes de
ejecutarse.

**El síntoma.** La primera versión pedía el foco al entrar y lo mantenía durante toda la llamada,
que es lo que parece correcto para una conversación. En el teléfono, la app se apagaba y se
encendía sola varias veces por segundo. El log lo explicó:

```
openMicrophone(): active=true pausado=false
Foco de audio: cambio=-2   <- AUDIOFOCUS_LOSS_TRANSIENT
Foco de audio: cambio=1    <- AUDIOFOCUS_GAIN, 3 ms después
openMicrophone(): active=true pausado=false
```

**La causa.** El servicio de reconocimiento de voz **pide el foco de audio para grabar**. Al
pedirlo nos lo quitaba; nosotros lo leíamos como "entró una llamada telefónica" y cerrábamos el
micrófono; el servicio lo soltaba, lo recuperábamos, reabríamos el micrófono, y vuelta a empezar.

**Los dos cambios.** El foco de audio es para **reproducir**, no para grabar: se pide solo cuando
hay algo sonando que puede molestar a otra app. Y como durante la lectura el micrófono sigue
abierto para poder interrumpir, el reconocedor sigue robando el foco cada pocos segundos; esas
pérdidas se recuperan en menos de 30 ms medidos, mientras que una llamada telefónica dura minutos.
Medio segundo de gracia distingue las dos sin ambigüedad y no se percibe como retraso.

---

## D-027 — Vigilante de sesión: `SpeechRecognizer` puede quedarse mudo — F8 — 2026-08-27

`ContinuousSttManager` arma una cuenta atrás de 2500 ms que se reinicia con cada `onRmsChanged`. Si
vence, la sesión se da por muerta y se reinicia.

**Por qué.** El reconocedor puede no llamar a ningún callback: ni resultado, ni error, ni nada. En
el Samsung A56 de prueba ocurre **siempre** en la primera sesión con `EXTRA_PREFER_OFFLINE` activo y
sin el paquete de español descargado. Medido:

```
17:28:34.260  STT startSession running=true muted=false bloqueado=false
17:28:34.802  rec stop ... VOICE_RECOGNITION          (dumpsys audio)
(35 segundos de silencio absoluto: ningún callback)
```

**Por qué importa tanto.** F8 pide `EXTRA_PREFER_OFFLINE` en `true` "con reintento en línea si
falla", y ese reintento se dispara desde `onError`. Sin vigilante no se disparaba nunca: la pantalla
se quedaba en "Escuchando" para siempre, con el micrófono cerrado y sin una sola línea de log que lo
delatara. Es el mismo tipo de fallo que el bucle infinito de D-023 y por el mismo motivo: desde
fuera es indistinguible de estar funcionando.

**El latido** son las medidas de volumen de `onRmsChanged`, que un reconocedor sano entrega varias
veces por segundo incluso en una sala en silencio.

**Se recuerda para todo el proceso.** Que el reconocimiento sin conexión no sirva se guarda en una
bandera estática: la comprobación cuesta 2,5 s y no hay motivo para repetirla cada vez que el
estudiante entra al modo de voz. Vuelve a intentarse al reiniciar la app, que es cuando podría haber
cambiado porque el estudiante descargó el idioma desde los ajustes del sistema.

## D-028 — Primer `model.tflite` real de Mario: entrada NCHW, el contrato pide NHWC — 2026-09-03

Mario entregó `best.tflite` (YOLOv8n, 50 clases, exportado sin NMS) y `labels.txt`. Se copiaron a
`assets/` como `model.tflite` y `labels.txt`, y `useStubDetector` pasó a `false` en
`model_config.json`, siguiendo al pie de la letra docs/INTEGRACION_MODELO.md.

**Verificación antes de dar el modelo por bueno.** Con `ai-edge-litert` (Python) se inspeccionó el
tensor real, sin fiarse del documento de acompañamiento:

```
INPUT:  serving_default_args_0    [1, 3, 640, 640]  float32
OUTPUT: serving_default_output_0  [1, 54, 8400]      float32
```

La salida cuadra con el contrato (54 = 4 + 50 clases, disposición `TRANSPOSED`, sin objectness). La
**entrada no**: es `NCHW` (canal primero), y tanto docs/INTEGRACION_MODELO.md como
`YoloTfliteDetector.kt` asumen `NHWC` — `[1, 640, 640, 3]`. El código lee
`inputSize = inputShape[1]`, que con este tensor da **3**, no 640, y escribe el buffer de entrada
como RGB intercalado por píxel en vez de tres planos de canal separados.

**Qué falla y qué no.** `DetectorFactory` no lo detecta al construirse (el `require` solo comprueba
que el tensor tenga 4 dimensiones) y `FrameAnalyzer.analyze()` atrapa cualquier excepción por
frame, así que la app **no crashea** (regla 2 de CLAUDE.md se cumple) pero tampoco muestra ningún
aviso: sencillamente no detecta nada, con "Fallo al analizar un frame" repitiéndose en
`adb logcat` como única pista. La pantalla de Diagnóstico sí lo expone: entrada `1x3x640x640` en
vez de `1x640x640x3`.

**Decisión.** Se integró el modelo tal cual (Mario lo pidió así, para confirmar el hallazgo en el
teléfono vía Diagnóstico y probar el resto del pipeline) en lugar de esperar un reexport. Ningún
`.kt` se tocó — CLAUDE.md prohíbe modificar código Kotlin cuando se trabaja con Mario, y el arreglo
de fondo (soportar NCHW o forzar el reexport en NHWC) es una decisión de Mario, no un parche de
turno.

**Hipótesis del origen.** Ultralytics puede exportar TFLite por dos caminos: el clásico vía ONNX +
`onnx2tf` (da NHWC) o, en versiones recientes, vía `ai-edge-torch` (conserva el NCHW original de
PyTorch). Los nombres de tensor (`serving_default_args_0` / `..._output_0_output`) son típicos de
`ai-edge-torch`. La recomendación pendiente de confirmar con Mario: forzar el camino `onnx2tf` o
fijar una versión de `ultralytics` que lo use por defecto, y volver a exportar.

**Clases.** `labels.txt` trae 50 líneas, cuadra con las 50 clases del tensor de salida. Sigue
pendiente lo que ya anotó Mario en su documento: 5 clases sin datos y 3 genéricas intrusas por
corregir en Roboflow antes de la versión final — no bloquea esta integración, sí bloquea el
entrenamiento definitivo.

### Resuelto el mismo día: reexport vía `onnx2tf`

Se reexportó saltándose por completo el `format='tflite'` de Ultralytics, que es el que elegía el
backend equivocado. En su lugar, dos pasos explícitos en Colab:

```python
model.export(format='onnx', imgsz=640, opset=12, simplify=True, nms=False)
!onnx2tf -i /content/best.onnx -o /content/tflite_nhwc
```

`onnx2tf` existe precisamente para convertir NCHW→NHWC, así que el resultado es determinista y no
depende de qué versión de `ultralytics` esté instalada. Tensor verificado:

```
INPUT : images   [1, 640, 640, 3]  float32     ← NHWC, como pide el contrato
OUTPUT: output0  [1, 54, 8400]      float32
```

Los nombres de tensor (`images` / `output0`, en vez de `serving_default_args_0` /
`..._output_0_output`) confirman de paso la hipótesis del camino de export.

**Segundo hallazgo, y por poco se cuela.** Este export **no normaliza las coordenadas**: pasando
ruido aleatorio por el modelo, `cx,cy,w,h` salen en el rango 3,97 – 643,94, o sea píxeles del
cuadrado de 640, no 0..1. El export propio de Ultralytics sí las normaliza; `onnx2tf` entrega la
salida cruda del ONNX. Por eso `model_config.json` va con **`"coordsNormalized": false`**, que es
lo que hace a `YoloDecoder` dividir entre `inputSize`. Con `true` las cajas habrían salido 640
veces más grandes que la pantalla, es decir invisibles, y el síntoma —ninguna caja dibujada— es
idéntico al del problema NCHW. Lo cubre la sección 5 de docs/INTEGRACION_MODELO.md; se comprobó
por inferencia antes de instalar, no en el teléfono.

**Estado final de `model_config.json`:** `useStubDetector: false`, `quantized: false` (el modelo es
float32), `coordsNormalized: false`, `outputLayout: "TRANSPOSED"`, `inputSize: 640`.
`./gradlew assembleDebug` → BUILD SUCCESSFUL. Ningún `.kt` tocado en toda la integración.

**Pendiente de rendimiento.** El modelo es float32 a 640, que en el SM-A566E costaba ~100 ms de
inferencia con el YOLOv8n de COCO (F7). El export int8 que recomienda docs/INTEGRACION_MODELO.md
sigue sin hacerse; si los FPS no alcanzan, ese es el siguiente paso, no un cambio de código.

## D-029 — Un modelo con la forma equivocada ahora falla fuerte, y el catálogo deja de exigir lo imposible — 2026-09-03

**Autorización.** `CLAUDE.md` prohíbe tocar código Kotlin cuando se trabaja con Mario. Dariem
levantó esa restricción expresamente para esta sesión, transmitido por Mario, a cambio de dejar
documentado lo que se cambió. Esta entrada es esa constancia. Cambios en `YoloTfliteDetector.kt`,
`CatalogJsonTest.kt`, `catalog.json` y una prueba nueva, `YoloInputShapeTest.kt`.

### 1. La forma del tensor de entrada se valida al construir

`YoloTfliteDetector` leía `inputSize = inputShape[1]` dando por sentado NHWC. Con el export NCHW
de D-028 eso valía **3**: se reservaba un buffer para una imagen de 3x3 píxeles y `writeInput`
volcaba RGB intercalado donde el modelo esperaba tres planos de canal.

**Lo que importa no es el fallo, es cómo se manifestaba.** El intérprete reventaba dentro de
`detect()`, que corre en `FrameAnalyzer.analyze()`, donde toda excepción se atrapa por frame para
que un frame malo no tumbe la cámara (regla 3 de CLAUDE.md). El resultado era la peor combinación
posible:

- el Diagnóstico decía "Detector real activo",
- **no** salía la banda de modo demostración,
- la app no detectaba absolutamente nada,
- y la única pista era una línea repitiéndose en `adb logcat`.

Es decir: indistinguible, desde la pantalla, de un modelo que simplemente no reconoce nada de lo
que tiene delante. Se perdió una sesión entera antes de encontrarlo.

Ahora `validateInputShape()` comprueba las 4 dimensiones, que el eje de canales sea el último,
que valga 3 y que la entrada sea cuadrada; si algo falla lanza `ModelMismatchException`, que
`DetectorFactory` ya atrapa para caer al `StubDetector` **con el motivo visible en pantalla**. Eso
es lo que la regla 2 de CLAUDE.md pretendía desde el principio.

El mensaje distingue el caso NCHW y dice cómo arreglarlo (`onnx2tf`), pero solo cuando el patrón
encaja: un modelo de 1 canal se rechaza sin mencionar NCHW, para no mandar a nadie a reexportar
por el motivo equivocado.

La función es `internal` y pura —aritmética sobre un `IntArray`— para poder probarla en la JVM,
igual que `BoxMapper`, y por el mismo motivo: son las dos partes del proyecto donde un error no
se ve, se sufre. `YoloInputShapeTest` la cubre con 5 casos, incluido el `[1, 3, 640, 640]` real.
El `companion object` pasó de `private` a `internal`; sigue sin salir del módulo.

**Comportamiento con el modelo actual: idéntico.** Para `[1, 640, 640, 3]` la función devuelve
640, exactamente lo que hacía `inputShape[1]`.

### 2. `CatalogJsonTest` exigía una cobertura que ya no puede existir

La prueba `los classId son unicos y coinciden con labels punto txt` exigía que **toda** clase de
`labels.txt` tuviera ficha en `catalog.json`. Con las 4 clases de ejemplo se cumplía; con las 50
clases reales del modelo pasó a ser imposible y dejó la suite en rojo (57 pruebas, 1 fallo).

**No se rellenó el catálogo con 48 fichas inventadas, y es una decisión deliberada.** Fabricar
EPP, riesgos y procedimientos de encendido para equipos reales de laboratorio, que va a leer un
estudiante de primer semestre sin experiencia, es peligroso: es exactamente el tipo de contenido
donde equivocarse tiene consecuencias físicas. Además contradice el principio que sostiene todo
el diseño —la regla 6 de CLAUDE.md, "toda respuesta muestra su fuente"— y `catalog.json` es
justamente el único sitio donde la app enseña contenido sin que el estudiante vea de dónde salió.
Quien tiene que responder de las 50 clases es el backend RAG, con los manuales reales y citando
página.

Se invirtió la comprobación a lo que sí es un invariante y sí detecta podredumbre real:
**ninguna ficha puede apuntar a una clase que el modelo no detecta.** Una ficha huérfana no se
mostrará jamás y nadie se entera. Así murieron `incubadora` y `vortex`, que sobrevivieron al
cambio de `labels.txt` sin que ninguna prueba chistara; se eliminaron de `catalog.json`, que
queda con `microscopio_binocular` y `camara_electroforesis`, las dos únicas que sí son clases
reales del modelo.

La cobertura completa del catálogo **no** es un invariante del proyecto: es un respaldo sin
conexión, y la app ya resuelve la clase sin ficha con la ficha mínima "Ficha no disponible".

### Verificación

`./gradlew testDebugUnitTest --rerun-tasks` → **63 pruebas, 0 fallos** (eran 57 con 1 en rojo).
`assembleDebug` y `lint` en verde. **No se pudo verificar en el teléfono**: la depuración
inalámbrica se cayó al terminar. El comportamiento con el modelo actual es demostrablemente el
mismo, pero conviene reinstalar y confirmar que sigue detectando.

## D-030 — Se descarta YOLOv8m y se queda YOLOv8n, medido — 2026-09-05

El profesor de Mario recomendó entrenar con `yolov8m` en lugar de `yolov8n`. Se entrenaron **los
dos** sobre el mismo dataset, con la misma semilla y el mismo número de épocas, y se evaluaron
sobre el mismo split de test. **Se queda `yolov8n`.**

### Los números

| | mAP50 | mAP50-95 | Precisión | Recall | Inferencia | Tamaño `.tflite` |
|---|---|---|---|---|---|---|
| `yolov8n` | **0,8374** | 0,5102 | 0,7733 | 0,7989 | 275 ms (medido en SM-A566E) | 12,2 MB |
| `yolov8m` | 0,8305 | **0,5283** | **0,8227** | 0,796 | ~2 290 ms (proyectado) | 103,7 MB |

El modelo mediano **no es mejor**: pierde en mAP50, gana por poco en mAP50-95 y precisión, empata
en recall. A cambio cuesta 8,3 veces más cómputo y 8,5 veces más tamaño.

### Cómo se obtuvo el coste, para que nadie lo repita mal

La cifra de `yolov8m` es una **proyección, no una medida**, y conviene decir de dónde sale porque
en este proyecto ya hubo una tabla de rendimiento construida sobre una base equivocada (ver la
bitácora del 2026-09-03). Se ejecutaron los dos `.tflite` reales en la CPU de la PC de desarrollo,
misma entrada y mismo número de hilos: mediana de **44,6 ms** para `n` y **371,6 ms** para `m`, o
sea **8,32x**. Esa relación se aplicó a los 275 ms que `n` costaba **medidos en el teléfono**.
Coincide con la relación teórica de FLOPs (8,7 contra 78,9 GFLOPs, 9,1x), lo que da confianza en
el orden de magnitud. Falta la medida directa de `m` en el dispositivo.

A 2,3 s por frame la app es inservible para dibujar cajas sobre la cámara en vivo, sea cual sea
el mAP.

### Por qué el modelo grande no ganó, que es lo que hay que entender

**El dataset es demasiado pequeño para que la capacidad extra se note.** El split de test tiene
**48 imágenes y 48 instancias** repartidas entre 28 clases, la mayoría con 1 o 2 ejemplos. Con un
solo ejemplo por clase el mAP de esa clase es casi binario: sale 0,995 o sale 0. Diferencias de
0,7 puntos entre dos modelos sobre esa base **no son distinguibles del ruido**, y así hay que
leerlas: no es que `n` sea mejor que `m`, es que con estos datos no se puede saber.

Además, de las 50 clases del modelo solo **28 aparecen en el test**. Sobre las otras 22 no hay
ninguna evidencia.

La recomendación del profesor no es incorrecta en general —con un dataset grande `yolov8m` suele
ganar—; lo que ocurre es que aquí **el cuello de botella son los datos, no el modelo**. Ocho
veces más parámetros necesitan más datos, no los mismos.

### El dataset sigue sin corregir, y se nota en la tabla por clase

`labels.txt` del reentrenamiento es **idéntico byte por byte** al anterior: las 3 clases genéricas
intrusas y las 5 sin anotar siguen ahí. El efecto es visible:

```
camara_de_electroforesis_b2      1  1      0      0      0      0
camara_electroforesis            2  2      1      0  0.495  0.297
```

La clase genérica `camara_electroforesis` se está comiendo las detecciones de la específica
`camara_de_electroforesis_b2`, que colapsó a cero. Es exactamente el fallo que la nota de
integración de Mario predijo en agosto.

### Qué modelo quedó instalado, y por qué no fue por precisión

Se cambió el `model.tflite` del proyecto por el `yolov8n` del reentrenamiento (150 épocas). **No
porque detecte mejor** —las métricas del anterior eran sobre *validación* y las nuevas sobre
*test*, así que no son comparables y la diferencia real es desconocida— sino por
**reproducibilidad**: el `best.pt` del modelo anterior se perdió al reiniciarse el runtime de
Colab y su cuaderno tampoco existe, con lo que era un binario huérfano de 12 MB imposible de
regenerar. El nuevo tiene los pesos en Drive, métricas documentadas sobre test y un cuaderno
repetible.

Para una entrega académica, poder decir de dónde salió el modelo pesa más que dos décimas de mAP
que además están dentro del ruido.

### Verificación

`testDebugUnitTest --rerun-tasks` → 63 pruebas, 0 fallos. `assembleDebug` en verde. Tensores
comprobados antes de copiar: entrada `[1, 640, 640, 3]` float32, salida `[1, 54, 8400]`, cajas en
píxeles (7,1–648,9) → `coordsNormalized: false`, igual que antes. **Sin verificar en el
teléfono**: no hubo dispositivo conectado en esta sesión.

## D-031 — La variante de depuración apunta al backend real, y la IP del PC es configurable — 2026-09-09

Al preparar la primera prueba del chat contra el backend real desde el teléfono, ninguna de las
dos variantes servía:

| | `USE_MOCK_API` | HTTP en claro |
|---|---|---|
| `debug` | `true` → respuestas del mock, la red no se usaba | Solo `10.0.2.2`, `10.0.3.2`, `localhost`, `127.0.0.1` |
| `release` | `false` | Prohibido del todo: no declara `networkSecurityConfig` y `targetSdk 35` bloquea el tráfico en claro |

El fallo del `debug` era el peligroso: la app habría contestado con los JSON de `assets/mock/`
y nadie se habría enterado de que el backend no se estaba usando. Se habría dado por probado
algo que no se probó.

### Qué se cambió

1. **`USE_MOCK_API` en `debug` pasa a `false`.** El backend simulado existía porque el backend
   real no existía; ya existe, está indexado con 3628 fragmentos y responde. Se conserva el
   camino de vuelta con `-Plabscan.useMock=true` para desarrollar sin backend.
2. **`BASE_URL` de `debug` sale de `labscan.devHost`** en `gradle.properties`, con `10.0.2.2`
   como valor por defecto para quien trabaje contra el emulador.
3. **La IP LAN del PC se añade a `network_security_config.xml`.** Android exige IP literal para
   permitir HTTP en claro y no admite subredes; es la limitación que ya anotaba D-012 y que el
   propio archivo documentaba como pendiente.

La IP vive por tanto en **dos sitios que tienen que coincidir**: `gradle.properties` y el
`network_security_config.xml`. No hay forma de tener una sola fuente: el primero es Kotlin
generado y el segundo es un recurso XML que Android lee antes de que exista `BuildConfig`.
Si el router reparte otra IP hay que cambiarla en los dos y recompilar.

`BASE_URL` sigue siendo solo el valor inicial: Ajustes → URL del servidor lo sobrescribe en
caliente, sin recompilar (F7).

### Verificación

`BuildConfig` generado: `BASE_URL = "http://192.168.100.25:8000/"`, `USE_MOCK_API = false`.
`testDebugUnitTest --rerun-tasks` → 63 pruebas, 0 fallos. `assembleDebug` correcto.
**Sin probar en el teléfono**: sigue sin haber dispositivo en esta máquina.

## D-032 — Halo oscuro bajo el borde de la caja y etiqueta legible fuera de la barra de estado — 2026-09-09

Petición de Mario tras verlo en el teléfono: el cuadro de detección se veía mal.

**El problema no era el grosor, era el contraste.** Un borde verde de 3 dp sobre imagen de
cámara en vivo desaparece en cuanto el fondo es claro y, peor, cuando el propio equipo es
verdoso — pasa con las cabinas y con varias carcasas del laboratorio. Subir el grosor tapa más
el objeto y no resuelve nada.

Se dibuja un **trazo negro al 55 % por debajo** del borde de color, 1,5 dp más ancho por cada
lado. El borde de color va encima y lo tapa por el centro, así que del halo solo se ven los dos
filos. El cuadro se lee igual de bien sobre cualquier fondo sin cambiar de color ni engordar.

**La etiqueta** pasa de 13 sp a 15 sp, de `Medium` a `SemiBold`, el relleno de 6/3 dp a 9/5 dp y
el fondo de 0,65 a 0,82 de opacidad.

**Y se cierra el defecto cosmético que quedó abierto en F7**: la etiqueta de una caja pegada al
techo se dibujaba sobre la barra de estado y se solapaba con el reloj. El `Canvas` ocupa toda la
pantalla y no sabe nada de las barras del sistema, así que ahora recibe `topInset`
—`WindowInsets.statusBars`, leído en composición porque no se puede consultar desde el
`DrawScope`— y nunca dibuja por encima de esa altura: si no cabe arriba respetando el límite, la
etiqueta pasa a dibujarse dentro de la caja. Con 50 clases y varias cajas a la vez ese solape se
veía mucho más que cuando se anotó.

### Verificación

`assembleDebug`, `lint` y 63 pruebas en verde. **Sin ver en pantalla**: no hay teléfono conectado
a esta máquina y el resultado de un cambio visual hay que juzgarlo a ojo. Que lo mire Dariem.

## D-033 — Por qué el modelo acierta en las fotos y falla en el laboratorio — 2026-09-09

Mario apuntó la app a una microcentrífuga **Labnet Prism R** en el laboratorio y la etiquetó como
`espectrofotometro_visible_digital_unico_1205` al **89 %**. Su lectura fue "solo detecta
espectrómetro". La medición dice otra cosa.

### Lo que se midió

Se pasó el `model.tflite` instalado por 30 fotos de las carpetas originales de Mario, 3 de cada
uno de 10 equipos distintos:

```
espectrofotómetro → espectrofotometro_visible_digital_unico_1205   0.90 0.80 0.93
microcentrífuga   → microcentrifuga                                1.00 1.00 0.97
NanoDrop          → nanodrop_lite_plus                             0.92 0.97 0.96
Ohaus Frontier    → ohaus_frontier_5718r                           0.89 0.93 0.97
Qubit             → qubit_quantitation                             0.96 0.92 0.93
microscopio       → microscopio_binocular                          0.81 0.79 0.94
autoclave         → autoclave                                      0.94 0.82 0.53
termociclador     → termociclador                                  0.96 0.97 0.92
```

**29 aciertos de 30, con confianza alta, y sin colapsar a ninguna clase.** El modelo no está roto.

### Por qué falla igualmente en el laboratorio

Esas 30 fotos **son las de entrenamiento**: las carpetas de `OneDrive\Documentos\ProyectoMobil`
son las que se subieron a Roboflow. El modelo las reconoce porque las memorizó.

La prueba concluyente: la carpeta `Microcentrifuga` **es esa misma Labnet Prism R**. El modelo la
clasifica bien, con 1.00 de confianza, en la foto que memorizó — y se equivoca sobre el mismo
aparato visto desde otro ángulo.

### Sobre las marcas de tiempo: conclusión retirada

La primera versión de esta entrada afirmaba que cada clase se había fotografiado en una ráfaga de
uno o dos segundos, porque las 11 fotos de `Microcentrifuga` llevan la hora 18:57:53–18:57:54 y
las 11 de `Qubit` las 18:58:49.

**Mario corrigió el dato: esa hora es la de recepción por WhatsApp, no la de captura.** Copió las
fotos a las carpetas de golpe, así que todas quedaron con la misma marca. El nombre de archivo de
WhatsApp no dice nada sobre cuándo se tomó la foto, y se usó como si lo dijera.

Queda retirada la afirmación sobre las ráfagas. **La cantidad real de vistas distintas por equipo
está sin medir**, y habría que mirarlo sobre las imágenes, no sobre sus nombres.

Lo que sí se midió y sigue en pie es lo de arriba: el modelo clasifica la Prism R al 1.00 en la
foto que ya vio y falla sobre el mismo aparato desde otro ángulo. Eso es memorización, y no
depende de cuándo se tomaran las fotos. El espectrofotómetro sí tiene solo 4 imágenes, y una es
de catálogo bajada de internet.

### Qué NO lo arregla

- **Más épocas.** El modelo ya ajusta perfectamente lo que tiene.
- **Un modelo más grande.** Ya se probó: `yolov8m` no mejoró a `yolov8n` (D-030), por esto mismo.
- **Bajar el umbral de confianza.** Da la respuesta equivocada con menos seguridad.

### Qué sí

**Fotos de verdad distintas por equipo: 20–30, dando la vuelta al aparato.** Distintos ángulos,
alturas, distancias, con y sin luz de ventana, con el equipo encendido y apagado, con objetos
alrededor y sin ellos. Una vuelta completa de un minuto por equipo produce más variedad útil que
mil fotogramas del mismo encuadre.

Y las fotos que Mario está tomando ahora con el teléfono en el laboratorio **son exactamente el
tipo de dato que falta**: capturadas con la misma cámara, a la misma altura y con la misma luz
con la que la app va a trabajar. Añadirlas a Roboflow es la mejora más barata disponible.

### Lo que esto implica para las métricas del informe

El mAP50 de 0,837 (D-030) se midió sobre un split de test tomado del mismo lote de ráfagas, así
que **mide memorización, no generalización**. La prueba honesta del sistema es exactamente lo que
hizo Mario: apuntar el teléfono a un equipo real. Conviene decirlo así en el informe en vez de
citar el mAP a secas.

## D-034 — La depuración permite tráfico en claro a cualquier host — 2026-09-09

Mario cambió de red, el router le dio otra IP (`192.168.100.25` → `172.20.135.199`) y la app dejó
de alcanzar el backend: *"El asistente no está disponible en este momento"*.

**Lo grave no era la IP, era que cambiarla en Ajustes no arreglaba nada.** La pantalla de Ajustes
sobrescribe la URL en caliente (F7), pero el `network_security_config` llevaba una **lista blanca
de IP literales compilada dentro del APK**. Android bloqueaba el tráfico en claro hacia cualquier
dirección que no estuviera en esa lista, así que la única salida era editar el XML, editar
`gradle.properties` y **recompilar** — cada vez que cambiara de red.

Android no admite subredes en este archivo (D-012), así que no hay forma de escribir
"todo 192.168.x.x". La única alternativa que no obliga a recompilar es permitirlo todo.

La variante de depuración pasa a `<base-config cleartextTrafficPermitted="true" />`.

**El permiso sigue sin poder colarse en la app publicada:** el archivo vive en `app/src/debug/`,
la variante de release no lo tiene ni declara `networkSecurityConfig`, y por tanto hereda el
comportamiento por defecto de `targetSdk 35`, que es prohibir todo el tráfico en claro. Esa
separación era el motivo original del diseño y se conserva intacta.

`labscan.devHost` queda como simple valor por defecto del APK, ya sin obligación de coincidir con
ningún otro archivo.

## D-035 — El corpus del RAG pasa a las 54 clases del dataset v2 — 2026-09-09

Mario entregó `ProyectoMobil`, un paquete con **54 clases**, 746 fotos organizadas para subir a
Roboflow, y para cada clase dos documentos: una guía de referencia y unas normas de seguridad.

El corpus anterior cubría las 50 clases viejas con 18 manuales de fabricante y 28 copias de la
norma de bioseguridad de la OMS. Varias clases se renombraron, se dividieron o desaparecieron,
así que las carpetas de `manuals/` ya no correspondían a ninguna clase detectable.

**No se eligió entre un corpus y el otro: conviven.** `top_k` es 4, así que la recuperación se
queda con los cuatro fragmentos más parecidos a la pregunta, vengan del documento que vengan.
Medido sobre el índice real:

| Pregunta | Qué gana |
|---|---|
| "qué protección necesito para abrir la autoclave" | las normas de seguridad, 0,610 |
| "cómo se equilibra el rotor antes de centrifugar" | el manual Ohaus, páginas 125, 59, 63 y 9 |

Cada tipo de documento gana el tipo de pregunta para el que sirve. Los 21 manuales reales de la
entrega anterior se recolocaron bajo los nombres de clase nuevos con
`tools/migrar_manuales_v2.py`, que lleva la tabla de equivalencias explícita.

**Los documentos del paquete nuevo no son documentación de fabricante**, y su propia primera
página lo dice. Como `ingest.py` usa el nombre del archivo como título de la cita, y ese título
es lo que el estudiante lee en pantalla, los archivos se nombraron para que la diferencia se vea
sin abrir nada: `Manual del fabricante - …`, `Referencia de familia - …`, `Guía de referencia
general - …`, `Normas de seguridad - …`.

Resultado: 54 clases, 129 documentos, 1888 fragmentos. 15 clases con manual del modelo exacto,
6 con referencia de familia, las 54 con guía y normas.

### Tres documentos se descartaron a propósito

- **Manual del vortex Labnet VX-200.** Esa clase era una identificación equivocada: al leer las
  fotos resultó ser una microcentrífuga Labnet Spectrafuge 24D. El manual es de un aparato que
  el laboratorio no tiene.
- **Referencia Cleaver multiSUB.** Son cubetas **horizontales** de tipo submarino; el equipo del
  laboratorio es la maxFILL CSU33, que es **vertical**. Citarlo enseñaría el procedimiento
  equivocado para montar el gel.
- **Manual de bioseguridad de la OMS.** Ahora las 54 clases tienen normas propias, y 125 páginas
  de texto genérico por carpeta desplazarían a los fragmentos específicos del equipo. Sigue
  guardado en `manuals_v1_2026-09-05/`.

## D-036 — El troceado baja a 200 palabras porque los documentos nuevos son de dos páginas — 2026-09-09

`chunk_words` valía 500, heredado de cuando el corpus eran manuales de fabricante de 150 páginas.
Con la entrega v2 la mayoría de los documentos ocupan dos páginas, así que **un documento entero
cabía en un solo fragmento**: su vector acababa siendo el promedio de descripción, componentes,
uso, mantenimiento y especificaciones a la vez. Parecido a todo y a nada.

Síntoma que lo destapó: `microscopio_compuesto_binocular_amscope_b120` devolvía **cero
fragmentos** a "cómo enfoco la muestra", teniendo la respuesta escrita en su documento.

Se midió sobre 14 clases al azar, con 6 preguntas típicas de un estudiante y 3 ajenas al
laboratorio, la similitud del mejor fragmento de cada clase:

| Troceado | Pregunta útil, mediana | Útiles bajo 0,35 | Pregunta ajena, mediana |
|---|---|---|---|
| 500 palabras | 0,356 | 49 % | 0,172 |
| 300 palabras | 0,390 | 36 % | 0,185 |
| **200 palabras** | **0,412** | **21 %** | 0,217 |

`chunk_words` pasa a 200, `chunk_overlap_words` a 60, y `similarity_threshold` de 0,35 a 0,30.

**Ningún umbral separa del todo los dos grupos.** Se elige equivocarse hacia recuperar de más
porque la búsqueda ya está filtrada por `equipment_id`: lo peor que puede pasar es entregarle al
modelo cuatro fragmentos del equipo correcto que no responden la pregunta, y la regla 2 del
prompt hace que entonces conteste que la documentación no lo cubre. Equivocarse hacia el otro
lado deja sin respuesta a un estudiante que preguntó algo legítimo, y eso no se ve en ningún log.

**El umbral vive en dos sitios y el `.env` manda.** `app/config.py` trae el valor por defecto,
pero `SIMILARITY_THRESHOLD` del `.env` lo pisa. Cambiar solo `config.py` no tuvo ningún efecto y
costó una vuelta entera de reindexado entender por qué. Los dos quedan en 0,30, y `config.py` lo
avisa en un comentario.

### Lo que sigue sin resolver

"pasos para encender el equipo" sigue devolviendo cero fragmentos en el microscopio, aunque su
documento diga *"Encender la fuente de luz y ajustar la intensidad al mínimo antes de iniciar"*.
Es límite del modelo de embeddings multilingüe con esa formulación concreta, no falta de
documento. No se bajó más el umbral por esto: a 0,25 empiezan a colarse preguntas ajenas.

## D-037 — `labels.txt` no se adelanta al modelo — 2026-09-09

Las 54 clases del dataset v2 esperan en `docs/labels_v2.txt`, **no** en `assets/labels.txt`.

El detector compara las líneas de `labels.txt` con el ancho del tensor de salida y lanza
`ModelMismatchException` si no cuadran (D-029). El modelo que hay hoy en la app emite 50 clases:
poner las 54 líneas sin cambiar el `.tflite` haría que `DetectorFactory` cayera al `StubDetector`
y la app dibujara cuadros falsos. Dariem tiene el teléfono con la versión que funciona.

Los tres archivos cambian a la vez o ninguno: `model.tflite`, `labels.txt` y `catalog.json`. El
procedimiento completo está en `docs/ENTRENAMIENTO_COLAB.md`, celda 12.

Cuando se haga el cambio, `CatalogJsonTest` va a fallar a propósito: sus dos fichas usan
`microscopio_binocular` y `camara_electroforesis`, que no son clases del dataset v2. Sus
equivalentes son `microscopio_compuesto_binocular_amscope_b120` y
`camara_de_electroforesis_owl_easycast_b2`.

## D-038 — Las fichas de los equipos mejor documentados salían sin equipo de protección — 2026-09-09

Al validar las 54 fichas generadas con el corpus v2, siete no tenían nada en `ppe`. No eran
siete cualesquiera: eran **exactamente las siete clases con manual de fabricante**, que además
son de las más peligrosas del laboratorio. Centrífugas, termocicladores, esterilizadores.

La causa está en `all_fragments`, que alimenta la generación de la ficha. Pedía los primeros 40
fragmentos del equipo, sin más. En una clase con un manual de 152 páginas conviviendo con unas
normas de seguridad de dos, el manual se llevaba la cuota entera:

| Documento | Fragmentos que llegaban a la ficha |
|---|---|
| Manual del fabricante Ohaus | 35 |
| Guía de referencia general | 5 |
| Normas de seguridad | **0** |

El documento que contiene los guantes, las gafas y las prohibiciones nunca entraba al prompt.

**Esto no lo enseña ningún error.** El campo `ppe` quedaba como lista vacía, y la app oculta
sola las secciones vacías, tal como está pedido en el prompt de la ficha. Una ficha de centrífuga
sin sección de protección se ve exactamente igual de bien que una completa.

El reparto pasa a ser por turnos entre los documentos del equipo: uno de cada uno, luego otro de
cada uno. Un documento corto aporta todo lo que tiene y se agota; el resto de la cuota se la
quedan los largos. Con la centrífuga da 5 fragmentos de la guía, 4 de las normas y 31 del manual,
que es el reparto que se quiere sin perder detalle de procedimiento.

Dentro de cada documento se respeta el orden de lectura, para que los pasos no lleguen barajados.

Quedan dos pruebas de regresión en `tests/test_retrieval.py`. La primera siembra un manual de 60
fragmentos junto a unas normas de 3 y exige que las normas lleguen a la ficha.

**Es el segundo fallo silencioso del mismo tipo en este backend.** El primero fue la colisión de
identificadores de fragmento, que decía 3628 indexados cuando había 739. Los dos se comportaban
como si todo funcionara. Conviene desconfiar de cualquier parte de este sistema cuyo fallo se
manifieste como "salió menos de lo que esperaba" en vez de como una excepción.

## D-039 — AGP baja a 9.2.1 porque Android Studio no sincroniza con 9.3.2 — 2026-09-10

El IDE fallaba el sync con *"The project is using an incompatible version (AGP 9.3.2) of the
Android Gradle plugin. Latest supported version is AGP 9.2.1"*. Lo desconcertante era que
`./gradlew assembleDebug` funcionaba: el problema es solo del modelo de sincronización de
Android Studio, no de la compilación.

La versión máxima no estaba en el panel de Build, que la cortaba a media línea. Se sacó del log
del IDE, en `%LOCALAPPDATA%\Google\AndroidStudio2026.1.1\log\idea.log`.

Se baja el AGP en vez de exigir que todo el equipo actualice el IDE. Dariem trabaja sobre este
mismo repositorio y una versión de AGP que su Studio no soporte lo deja sin poder abrir el
proyecto.

AGP 9.2 no acepta el bloque `optimization { }` sin `android.r8.gradual.support=true`, así que
esa bandera va a `gradle.properties`. **No se tocó la configuración de R8**: el APK de release
sigue compilando ofuscado y reducido igual que antes, verificado con `assembleRelease`.

## D-040 — La app encuentra el backend sola por mDNS — 2026-09-10

El backend corre en el portátil de Mario, que recibe una IP en la universidad y otra en su casa.
Cada cambio de red obligaba a mirar `ipconfig` y teclear la dirección en Ajustes; el 2026-09-09
se perdió una sesión de pruebas entera por eso (D-034).

El servidor se anuncia como `_labscan._tcp` con `zeroconf` y la app lo busca con `NsdManager`.

**Prioridad: lo escrito en Ajustes, luego lo encontrado, luego la URL de compilación.** Si
alguien se tomó la molestia de escribir una dirección, esa gana. Un descubrimiento automático
que pisa lo escrito a mano es imposible de depurar, porque la app deja de ir al sitio que el
propio Ajustes muestra en pantalla. Ajustes indica si encontró servidor y si lo está ignorando,
con un botón para borrar el campo y usarlo.

Tres cosas que costaron encontrarse y conviene no repetir:

- **Se anuncia una sola dirección, no todas.** Este portátil tiene `192.168.100.25` (el wifi) y
  `172.23.208.1` (un adaptador virtual, inalcanzable desde el teléfono). Anunciando las dos, el
  resolutor de Android elige la que quiera, y cuando elige mal el fallo se ve como un tiempo de
  espera agotado, no como un error de configuración.
- **Hay que usar la API asíncrona de zeroconf.** La síncrona, llamada desde el ciclo de vida de
  FastAPI que ya corre sobre asyncio, falla con `EventLoopBlocked`.
- **`allow_name_change=True`.** Al reiniciar el servidor, el anuncio anterior sigue unos
  segundos en la red y zeroconf lanza `NonUniqueNameException`, cuyo mensaje viene **vacío** y
  deja un aviso imposible de interpretar.

**mDNS no atraviesa routers**, y muchas redes de campus bloquean multicast o aíslan a los
clientes. Esto es una comodidad, no un reemplazo del campo manual.

## D-041 — El asistente busca en internet cuando los manuales no cubren la pregunta — 2026-09-10

Hasta ahora el backend no contestaba lo que no estuviera en los manuales. Esa regla no era
timidez: quien pregunta es un estudiante de primer semestre delante de una autoclave, y una
respuesta inventada con aire de autoridad es peor que un "no lo sé".

Pero "no lo sé" también tiene un costo. Al Qubit se le preguntaba cómo se apaga y contestaba
información insuficiente, teniendo respuesta.

Se busca en internet con la herramienta de búsqueda web de la API de Anthropic, bajo cuatro
condiciones:

1. **Solo cuando los manuales no dan la respuesta.** Dos caminos llevan ahí: la recuperación no
   devolvió fragmentos, o devolvió fragmentos que no contestan. El segundo caso no se puede
   detectar desde fuera, así que el modelo lo declara escribiendo `SIN_RESPUESTA_EN_DOCUMENTOS`
   y nada más (regla 2 del prompt). Sin esa marca, una pregunta cuyos fragmentos rondan el tema
   sin contestarlo se quedaba sin respuesta y sin buscar, que es justo lo que pasaba con "cómo
   limpio los objetivos con aceite de inmersión".
2. **Nunca con el índice vacío.** Un índice vacío no significa que la pregunta no esté cubierta,
   significa que nadie ejecutó `ingest`. Buscar entonces mandaría todas las preguntas a internet
   y el sistema parecería funcionar con el RAG entero apagado.
3. **Solo se devuelven las páginas CITADAS, nunca las consultadas.** Medido sobre tres preguntas
   reales, las citas resultaron ser el mejor indicador de si la búsqueda sirvió:

   | Pregunta | Citas | Resultado |
   |---|---|---|
   | Qubit Q32857, "cómo se apaga" | 0 | no encontró el procedimiento |
   | Purificador DO2, "cambio de filtro" | 1 | encontró algo general |
   | Microscopio B120, "limpiar objetivos" | 5 | respuesta completa |

   Cero citas significa que el modelo no apoyó la respuesta en ninguna página, y las que
   consultó son anuncios de eBay y fichas de tienda. Sin citas se cae al mensaje de siempre.
4. **La respuesta va marcada.** `fromWeb: true` y las fuentes como `Web: dominio`. La app lo
   avisa con un encabezado antes del texto, no solo en las fuentes.

Detalles que hicieron falta y no son evidentes:

- **`tool_choice` obligatorio.** Sin forzar la herramienta, el modelo decidía por su cuenta y la
  mitad de las veces contestaba de memoria sin buscar, que es lo que este backend no debe hacer.
- **Solo se toma el texto posterior a la última búsqueda.** El modelo intercala frases de
  trámite entre búsqueda y búsqueda ("Necesito buscar el manual del Q32857"), y juntando todos
  los bloques esa frase encabezaba lo que leía el estudiante.
- **Un conjunto de "vistas" por lista al deduplicar.** Compartirlo vaciaba las citas siempre: el
  bloque de resultados llega antes que el texto, así que cada URL entraba primero como
  consultada y luego se descartaba por repetida al aparecer como cita. El síntoma era que la
  búsqueda no devolvía nada nunca. Hay prueba de regresión.

**El riesgo que queda.** Internet está lleno de páginas de otro modelo del mismo aparato: al
Qubit Q32857 los primeros resultados eran del Qubit 4. La consulta incluye marca y modelo
exactos y el prompt obliga a avisarlo, y en las pruebas avisó. Para procedimientos de seguridad
sigue siendo peor que el manual, y la respuesta remite al docente.

Se apaga con `WEB_SEARCH_FALLBACK=false` en el `.env`. Puede interesar para la entrega, si se
quiere demostrar que el sistema solo habla de los manuales del laboratorio.

## D-042 — Las fuentes citadas se rehacen para que se puedan leer — 2026-09-10

La ficha del Qubit mostraba once líneas grises idénticas, todas el mismo manual, cambiando solo
el número de página del final. Ocupaban media pantalla y no informaban de nada.

Dos cambios, uno en cada lado:

- **El backend cita un documento por línea en las fichas**, no una página por línea. Una ficha es
  el resumen de toda la documentación del equipo, no la respuesta a una pregunta concreta, así
  que la página no aporta: nadie va a ir a comprobar once sitios. En total, 494 líneas de fuente
  pasaron a 129, y ninguna ficha supera las tres. **La respuesta del chat sí conserva la
  página**, que es donde de verdad sirve.
- **La app las dibuja como un bloque con fondo propio**, cada fuente con su icono según el
  origen, el título en el color del texto normal y la página debajo en pequeño.

El icono es lo que hace visible de un vistazo que una fuente es de internet y no un manual del
laboratorio, que es la parte que importa de D-041.

## D-043 — Modelo v2: 54 clases, mAP50 0,912, y lo que ese número no dice — 2026-09-10

Entra el modelo entrenado sobre el dataset v2, con las cajas preetiquetadas por
`tools/preetiquetar.py` y revisadas a mano por Mario.

| Métrica | v1 (50 clases) | v2 (54 clases) |
|---|---|---|
| mAP@50 | 0,837 | **0,912** |
| mAP@50-95 | — | 0,685 |
| precisión | — | 0,813 |
| exhaustividad | — | 0,844 |

Verificado antes de integrar con `tools/integrar_modelo.py`: entrada NHWC `[1,640,640,3]`,
salida `[1,58,8400]`, donde 58 = 4 coordenadas + 54 clases.

### Tres razones para no citar el 0,912 a secas

**Diez clases no se midieron.** El conjunto de prueba tiene 111 imágenes para 54 clases, así
que a estas no les tocó ninguna: analizador DBO, NanoDrop, estufa Memmert, Incu-Shaker,
luxómetro, microondas, pH metro Oakton, refractómetro Atago, refrigeradora Indurama y
ultracongelador Haier. No fallan: no hay dato sobre ellas.

**Con una o dos instancias por clase, la métrica salta de 0 a 1 con un solo acierto.** Muchas
clases marcan exactamente 0,995, que significa "acertó las dos que había".

**El reparto es aleatorio sobre fotos casi idénticas**, así que hay imágenes muy parecidas en
entrenamiento y en prueba. Mide memorización además de generalización. Es lo mismo que pasó
con el 0,837 del v1 (D-033), que luego en el laboratorio no se sostuvo.

### Cinco clases que sí fallan, y el patrón

| Clase | mAP50 | Con qué se confunde |
|---|---|---|
| `termociclador_tr_mgl48g` | 0,247 | los otros dos termocicladores |
| `destilador_de_agua_metalico_ac_l4` | 0,249 | exhaustividad 0, no lo encuentra |
| `agitador_orbital_elmi_sky_line_dos_20l` | 0,495 | el otro agitador orbital |
| `camara_de_electroforesis_owl_easycast_b2` | 0,497 | la B1-BP, son casi idénticas |
| `gps_portatil_de_mano_garmin_gpsmap_78` | 0,497 | exhaustividad 0 |

No es casualidad: son los grupos de equipos que se parecen entre sí, y que en el dataset v1
estaban fundidos en una sola clase. Separarlos era lo correcto, pero exige más fotos de cada
uno. Se arregla fotografiando, no entrenando más.

### El dato que más explica el comportamiento en el laboratorio

**111 imágenes y 111 instancias: exactamente una caja por foto.** El dataset no tiene ni un
solo caso de dos aparatos en el mismo encuadre. En el laboratorio la cámara apunta a mesas con
varios equipos, y para eso el modelo no ha entrenado nunca.

Es la explicación más probable de por qué el v1 se comportaba peor en el teléfono que en las
métricas, y va a seguir pasando con el v2 mientras el dataset sea de un aparato por foto.

### El int8 quedó pendiente

`onnx2tf` genera `best_integer_quant.tflite` y `best_full_integer_quant.tflite`, pero ninguno
carga: el delegado XNNPACK rechaza cuatro nodos TRANSPOSE cuantizados y falla al preparar. No
se investigó más porque es una optimización de rendimiento, no un requisito, y el float32
estaba verificado.

Sigue siendo la única palanca grande para el problema de los 2,7 FPS (D-033). Cuando se
retome: comprobar primero si el modelo carga con
`OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES`, que dice si el problema es del modelo o
solo del acelerador en Python.

## D-044 — Las 54 fichas viajan dentro del APK — 2026-09-10

`catalog.json` tenía **dos** fichas de las 54 clases. No rompía nada, porque la app resuelve
una clase sin ficha con "Ficha no disponible", pero dejaba la app inservible fuera de la red
del backend.

Y ese caso no es el raro, es el normal. El backend corre en el portátil de Mario con una IP de
red local. **Un teléfono con datos móviles, o en otra red, no puede alcanzarlo**: no hay ruta
hacia una 192.168.x.x desde fuera de esa red, y el descubrimiento por mDNS tampoco cruza
routers (D-040). Sin esto, quien tuviera el APK en su casa veía los cuadros de detección y
nada más.

Con las 54 fichas dentro del APK, sin conexión funcionan la detección, el nombre del equipo,
la descripción, la función, los componentes, el procedimiento, el equipo de protección, los
riesgos y las fuentes citadas. Lo único que sigue exigiendo servidor es el **chat**, y eso no
se puede evitar: la regla 5 de CLAUDE.md prohíbe que la app hable con el modelo directamente,
porque la clave de la API acabaría dentro del APK y un APK se descompila en minutos.

Se generan con `tools/generar_catalogo.py` a partir de `storage/equipment_cards.json` del
backend. La herramienta descarta las fichas que no correspondan a ninguna línea de
`labels.txt` y las que tengan vacío alguno de los campos que `CatalogJsonTest` exige, porque
una ficha sin procedimiento o sin riesgos es peor que ninguna: parece completa.

`catalog.json` pasa de 3,7 KB a 154 KB. Es texto en un APK de 38 MB.

**Sigue pendiente la revisión humana de estas fichas.** Están generadas por un modelo a partir
de los documentos indexados, y ahora se muestran en pantalla aunque no haya backend que las
corrija. Las de autoclave, centrífuga, esterilizadores y cabinas las tiene que leer una
persona antes de la entrega.

## D-044 — El asistente pasa a hablar con Claude desde el teléfono, con la clave del estudiante — 2026-09-10

En la revisión el docente rechazó que la app dependa de un servidor. El caso que lo destapó:
la presentación terminó, se cerró el portátil, él abrió el APK en su teléfono y el asistente
decía que no estaba disponible.

Ahora, si el estudiante escribe su clave de Anthropic en Ajustes, la app llama a Claude
**directamente** y no hace falta que nadie tenga un PC encendido. La app se la pide al abrirse
por primera vez, y el diálogo se puede posponer porque la cámara y las fichas no la necesitan.

### Lo que se pierde, y por qué no había alternativa

El backend buscaba entre **1888 fragmentos** de los manuales. Sin servidor no se puede: el
corpus completo son unos **837 000 tokens** medidos, contra los 200 000 de ventana del modelo.
No es que sea caro, es que no cabe.

Lo que sí cabe, y de sobra, es la **ficha del equipo que el estudiante está mirando**, que ya
viajaba dentro del APK desde que se metieron las 54. Son unos 670 tokens de entrada por
pregunta, medidos contra la API real. La respuesta sale de ahí y cita las fuentes que la propia
ficha declara, así que la regla 6 de CLAUDE.md se sigue cumpliendo.

| | Backend RAG | Camino directo |
|---|---|---|
| Fuente | 1888 fragmentos de manuales | la ficha del equipo |
| Búsqueda | semántica sobre el corpus | ninguna, va la ficha entera |
| Necesita | un PC encendido y alcanzable | solo la clave del estudiante |
| Coste | lo pagaba Mario | lo paga cada estudiante |

**El backend no se borra.** Sin clave configurada, la app sigue usándolo exactamente como
antes. Es el camino mejor cuando existe; ya no es el único.

### Sobre la clave

Es la clave **del estudiante**, escrita por él en su teléfono. Eso es distinto de lo que se
rechazó siempre en este proyecto, que era meter la clave del proyecto en el APK: un APK se
descompila en minutos y sería la cuenta de todos la que se gasta. Aquí cada uno consume la
suya.

Se guarda en DataStore, en el almacenamiento privado de la app, en claro. Cifrarla exigiría
`androidx.security`, una dependencia nueva, y protege poco más: en un teléfono sin rootear ese
directorio ya no es legible por otras aplicaciones. **No se escribe nunca en el registro.**

### Detalles que costaron encontrarse

**La comprobación de salud miraba al sitio equivocado.** El aviso de "el asistente no está
disponible" salía de preguntarle al backend, así que seguía apareciendo con la clave puesta y
todo funcionando. Ahora, con clave, el asistente está disponible por definición.

**El cliente HTTP del asistente directo es propio**, no el común de la app. El común lleva el
`BaseUrlInterceptor`, que reescribe el destino de cada petición hacia el backend elegido en
Ajustes; el destino aquí tiene que ser siempre `api.anthropic.com`.

**No se añadió el SDK de Anthropic.** Para una sola llamada POST no compensa arrastrar una
dependencia pensada para servidor (regla 8 de CLAUDE.md). El cuerpo se arma a mano con
kotlinx.serialization, que el proyecto ya usa.

**Sin equipo seleccionado no se llama al modelo**, igual que hacía el backend: sin ficha no hay
fuente que citar, y un modelo al que se pregunta sin fuentes responde de memoria y suena igual
de seguro.

### Verificado

Con el backend **apagado** y el túnel cerrado, la app arranca, no muestra ningún aviso y no
vuelve a pedir la clave. Y la misma petición que arma la app, enviada a la API real, devuelve
200 con el equipo de protección correcto de la autoclave y la advertencia de esperar a que baje
la presión.

Pendiente: la app solo detecta bien en horizontal. Se mira después.
