# Mini cuestionario: qué se usó de Kotlin en LabScan UTEQ

Preguntas con respuesta, todas sobre código que existe en este proyecto. Al final de cada
respuesta va el archivo donde mirarlo.

Las marcadas con **★** son las más probables en una defensa, porque son decisiones de diseño y
no detalles de sintaxis.

---

## 1. ★ ¿Por qué la app usa `interface Detector` y no directamente la clase de TensorFlow?

Para que el resto de la app no sepa nada de TensorFlow. Solo `detection/` conoce el motor de
inferencia; el resto ve la interfaz y la clase de datos `Detection`.

Eso permitió desarrollar toda la interfaz con `StubDetector`, que devuelve cajas falsas, antes
de que existiera el modelo entrenado. Y hoy permite que si el modelo falla al cargar, la app
caiga al detector de prueba en vez de cerrarse.

`detection/Detector.kt`, `detection/DetectorFactory.kt`

## 2. ★ ¿Qué es una `data class` y dónde se usa?

Una clase cuyo propósito es transportar datos. Kotlin le genera solo `equals`, `hashCode`,
`toString` y `copy`.

En el proyecto hay 42. La central es `Detection`, que lleva qué equipo, con cuánta confianza y
dónde. Se eligió que no dependa de TensorFlow, de CameraX ni de Compose, precisamente para
poder probarla con JUnit sin un dispositivo.

`detection/Detection.kt`

## 3. ★ ¿Qué es `sealed interface` y para qué sirvió aquí?

Una jerarquía cerrada: el compilador conoce todas las implementaciones posibles, así que en un
`when` obliga a cubrirlas todas.

Se usa para los estados que no admiten un valor "cualquiera": el resultado de enganchar la
cámara, el estado del detector, el estado de la conversación por voz. Si mañana alguien agrega
un estado nuevo y olvida tratarlo en la pantalla, **no compila**.

`camera/CameraBinder.kt`, `detection/DetectorFactory.kt`, `ui/voice/VoiceState.kt`

## 4. ¿Qué diferencia hay entre `val` y `var`?

`val` es de solo lectura después de asignarse, `var` se puede reasignar. En el proyecto casi
todo es `val`; las pocas `var` que hay llevan comentario explicando por qué.

## 5. ★ ¿Qué son las corrutinas y dónde hacen falta?

La forma de Kotlin de escribir código que espera sin bloquear el hilo. Una función marcada
`suspend` puede pausarse y reanudarse.

Aquí se usan para todo lo que tarda: pedir la ficha del equipo, preguntarle al asistente,
guardar preferencias. Sin ellas, cada una de esas esperas congelaría la cámara.

Hay 22 funciones `suspend`. `viewModelScope` las ata al ciclo de vida de la pantalla: si el
estudiante sale mientras se espera una respuesta, la petición se cancela sola.

`ui/scanner/ScannerViewModel.kt`, `data/remote/RagRepository.kt`

## 6. ★ ¿Qué es `StateFlow` y por qué no una variable normal?

Un flujo que siempre tiene un valor actual y avisa cuando cambia. La interfaz lo observa y se
redibuja sola.

Es la pieza que conecta el hilo de análisis de la cámara con la pantalla. El analizador escribe
las detecciones en un `MutableStateFlow` y Compose las recoge; nadie llama a "actualizar
pantalla" a mano.

Hay 87 usos. El patrón es siempre el mismo: un `MutableStateFlow` privado y un `StateFlow`
público de solo lectura, para que nadie de fuera pueda escribirlo.

`ui/scanner/ScannerViewModel.kt`

## 7. ¿Qué hace `by lazy`?

Retrasa la construcción hasta el primer uso, y la hace una sola vez.

Se usa en las 18 dependencias del contenedor. Así abrir la app no paga el coste de construir el
detector, leer los assets y armar el cliente HTTP: cada cosa se crea cuando alguien la pide.

`di/AppContainer.kt`

## 8. ★ ¿Cómo se inyectan las dependencias sin Hilt ni Koin?

A mano, por constructor, desde un único punto: `AppContainer`. Es una regla fija del proyecto.

Cada ViewModel recibe lo que necesita a través de una `ViewModelProvider.Factory` escrita a
mano. La ventaja para un proyecto académico es que no hay magia: se puede seguir con el dedo de
dónde sale cada objeto.

`di/AppContainer.kt`, la `companion object` con `factory(...)` de cada ViewModel

## 9. ¿Qué es una función de extensión?

Agregar un método a una clase que no es tuya, sin heredar de ella.

Ejemplo real: `NavHostController.volverAtras()`, que agrega al controlador de navegación un
retroceso seguro que nunca deja la pila vacía. Y `ConnectivityManager.hayRed()`, que traduce la
API de Android a la pregunta que le interesa a la app.

`MainActivity.kt`, `data/remote/ConnectivityObserver.kt`

## 10. ¿Qué es `companion object`?

El equivalente de los miembros estáticos de Java. Aquí guarda constantes y las factorías de los
ViewModel. Hay 26.

## 11. ★ ¿Cómo se manejan los errores de red?

Con `Result<T>` de Kotlin y una jerarquía `sealed class RagError`. El repositorio es la frontera
donde mueren las excepciones: hacia arriba solo salen `Result` y errores tipados.

Así la pantalla no ve nunca una excepción de Retrofit ni de OkHttp, solo un error con un mensaje
ya en español y la decisión de si se puede reintentar.

`data/remote/RagRepository.kt`, `data/remote/RagError.kt`

## 12. ¿Qué significa `?` y `?:` en Kotlin?

Kotlin distingue en el tipo si algo puede ser nulo. `String?` puede serlo, `String` no. El
compilador obliga a tratarlo.

`?.` llama solo si no es nulo, y `?:` da un valor alternativo cuando lo es. Hay 67 y 35 usos.
El beneficio concreto: los `NullPointerException` se detectan al compilar, no en el laboratorio
con el teléfono en la mano.

## 13. ¿Para qué se usa `@Volatile`?

Para valores que escribe un hilo y lee otro, garantizando que el segundo vea el cambio.

Caso real: la URL del servidor y la preferencia de abrir la ficha sola. Las escribe el hilo
principal desde Ajustes y las leen los hilos de red y de análisis, que no pueden suspenderse
para consultar la base de preferencias.

`data/remote/BaseUrlInterceptor.kt`, `ui/scanner/ScannerViewModel.kt`

## 14. ★ ¿Qué es Jetpack Compose y en qué cambia respecto a los XML?

La interfaz se describe con funciones de Kotlin marcadas `@Composable`, no con archivos de
diseño XML. Hay 56.

En vez de "buscar la vista y cambiarle el texto", se declara cómo se ve la pantalla para un
estado dado, y cuando el estado cambia Compose vuelve a dibujar lo que haga falta. Encaja
directamente con `StateFlow`.

Todo `ui/`

## 15. ¿Qué es `remember` en Compose?

Conserva un valor entre redibujados. Sin él, cualquier cosa calculada dentro de una función
composable se recrearía en cada recomposición.

Ejemplo donde importó de verdad: recordar el objeto de la conversación **anclado a la pantalla**
y no a la navegación actual. Estaba mal y la app se cerraba al colgar la llamada de voz.

`MainActivity.kt`, función `conversationViewModel`

## 16. ★ ¿Cómo se probó el código sin un teléfono?

Con JUnit sobre las clases que no dependen de Android. 77 pruebas.

El ejemplo que más se trabajó es `AutoOpenDecider`: la decisión de abrir la ficha sola vivía
dentro del ViewModel y no se podía probar, porque trabajaba con `RectF`, que es de Android. Se
sacó a una clase que solo recibe números y cadenas, y quedó cubierta con 13 pruebas.

`ui/scanner/AutoOpenDecider.kt` y su prueba

## 17. ¿Qué se usó para la lectura y el dictado de voz?

Las clases de la plataforma Android, `TextToSpeech` y `SpeechRecognizer`, envueltas en
`TtsManager` y `SttManager`. No se usó ninguna biblioteca externa.

`voice/`

---

## Si preguntan "¿por qué Kotlin y no Java?"

Tres razones con ejemplo en este proyecto, no genéricas:

**Los nulos se comprueban al compilar.** En una app que lee un modelo de disco, habla con una
cámara y con un servidor, la mitad de los fallos posibles son "esto vino vacío".

**Las corrutinas.** La app espera respuestas de red mientras la cámara sigue analizando 2,8
fotogramas por segundo. Con hilos a mano eso es mucho más código y mucho más fácil de romper.

**Compose es solo Kotlin.** La interfaz se observa directamente de los `StateFlow` del
ViewModel, sin capa intermedia de XML ni de búsqueda de vistas.
