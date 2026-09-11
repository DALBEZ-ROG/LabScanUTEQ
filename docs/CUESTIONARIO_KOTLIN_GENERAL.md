# Cuestionario general de Kotlin

Conceptos que aparecen en cualquier sistema hecho en Kotlin, no solo en este. Pregunta y
respuesta corta.

Las marcadas con **▲** son las que más se preguntan, porque son donde Kotlin se separa de Java
y donde más se equivoca la gente.

---

## Variables y tipos

**1. ¿Diferencia entre `val` y `var`?**
`val` es de solo lectura una vez asignada, `var` se puede reasignar. `val` no significa que el
objeto sea inmutable: una lista mutable declarada `val` sigue admitiendo elementos nuevos; lo
que no se puede es apuntar la variable a otra lista.

**2. ¿Hay que declarar siempre el tipo?**
No, Kotlin lo infiere. `val n = 5` es `Int`. Se declara cuando el tipo no es obvio o cuando se
quiere forzar uno concreto: `val n: Long = 5`.

**3. ▲ ¿Qué es una plantilla de cadena?**
Meter valores dentro de un texto con `$`. `"Hola $nombre"`, y con expresión
`"Total: ${a + b}"`. Evita concatenar con `+`.

**4. ¿Qué es `const val`?**
Una constante resuelta en tiempo de compilación. Solo para tipos básicos y cadenas, y solo a
nivel superior o dentro de un `companion object`.

---

## Nulos

**5. ▲ ¿Cómo trata Kotlin los nulos?**
El tipo dice si algo puede ser nulo. `String` nunca lo es; `String?` sí, y el compilador obliga
a tratarlo antes de usarlo. Es la diferencia práctica más grande con Java: muchos
`NullPointerException` pasan de ser un fallo en ejecución a un error de compilación.

**6. ▲ ¿Qué hacen `?.`, `?:` y `!!`?**
`?.` llama solo si no es nulo, y devuelve nulo si lo era. `?:` (elvis) da un valor alternativo
cuando lo de la izquierda es nulo. `!!` afirma que no es nulo y lanza excepción si lo es; es la
forma de renunciar a la seguridad y conviene justificar cada uso.

**7. ¿Qué es `lateinit`?**
Una variable no nula que se inicializa después de construir el objeto, típico de marcos que
inyectan valores. No sirve para tipos básicos, y leerla antes de asignarla lanza excepción.

---

## Funciones

**8. ¿Qué son los argumentos por defecto y los nombrados?**
Un parámetro puede tener valor por defecto, así que no hacen falta varias sobrecargas. Y al
llamar se puede nombrar cada argumento: `crear(ancho = 10, alto = 5)`, que además deja claro
qué es cada número en la llamada.

**9. ▲ ¿Qué es una función de extensión?**
Agregar un método a una clase que no es tuya, sin heredar de ella.
`fun String.primeraMayuscula(): String = ...`. Se resuelven de forma estática: no son
polimórficas y no pueden acceder a lo privado de la clase.

**10. ¿Qué es una función de orden superior?**
Una que recibe o devuelve otra función. `list.filter { it > 3 }` recibe una lambda.

**11. ¿Qué es `it`?**
El nombre implícito del único parámetro de una lambda, cuando no se le pone otro.

**12. ¿Para qué sirve `inline` en una función?**
Para que el compilador copie el cuerpo de la función en el sitio de la llamada en vez de crear
un objeto por cada lambda. Se usa en funciones de orden superior muy llamadas. Permite además
`reified`, que es conservar el tipo genérico en ejecución.

---

## Clases

**13. ▲ ¿Qué genera una `data class`?**
`equals`, `hashCode`, `toString`, `copy` y los `componentN` para desestructurar. Es para clases
que transportan datos.

**14. ▲ ¿Por qué hay que escribir `open` para heredar?**
Porque en Kotlin las clases y los métodos son finales por defecto. Heredar tiene que ser una
decisión explícita de quien escribe la clase, no algo que pase por descuido.

**15. ¿Diferencia entre `object` y `companion object`?**
`object` declara un singleton: una única instancia creada por el lenguaje. `companion object`
es el bloque de miembros asociados a la clase, el equivalente de lo estático de Java.

**16. ▲ ¿Qué es una clase o interfaz `sealed`?**
Una jerarquía cerrada: todas las implementaciones son conocidas en tiempo de compilación. Su
ventaja real es que un `when` sobre ella tiene que cubrir todos los casos, así que agregar un
caso nuevo y olvidarse de tratarlo **no compila**.

**17. ¿Y un `enum`?**
Un conjunto fijo de valores constantes. Frente a `sealed`, el `enum` tiene instancias únicas y
sin estado propio; con `sealed` cada caso puede llevar datos distintos.

**18. ¿Qué niveles de visibilidad hay?**
`public` (el de por defecto), `private`, `protected` e `internal`, que significa visible dentro
del mismo módulo.

**19. ¿Qué es el bloque `init`?**
Código que se ejecuta al construir el objeto, después del constructor primario.

---

## Colecciones y control

**20. ▲ ¿Diferencia entre `listOf` y `mutableListOf`?**
`listOf` devuelve una lista de solo lectura, `mutableListOf` una que admite cambios. Kotlin
separa la interfaz de lectura de la de escritura, cosa que Java no hace.

**21. ¿Qué hacen `map`, `filter` y `forEach`?**
`map` transforma cada elemento y devuelve otra lista. `filter` se queda con los que cumplen una
condición. `forEach` recorre sin devolver nada. También hay `first`, `any`, `groupBy`,
`sortedBy` y compañía.

**22. ▲ ¿Qué es `when` y en qué se diferencia del `switch`?**
Es el selector de casos, pero además es una **expresión**: puede devolver un valor. Admite
rangos, tipos y condiciones, no solo constantes. Cuando se usa como expresión, tiene que cubrir
todos los casos.

**23. ¿`if` puede devolver un valor?**
Sí. `val mayor = if (a > b) a else b`. Por eso Kotlin no tiene el operador ternario.

---

## Funciones de ámbito

**24. ▲ ¿Para qué sirven `let`, `run`, `apply`, `also` y `with`?**
Son cinco formas de trabajar sobre un objeto en un bloque. La diferencia está en cómo se
nombra el objeto y en qué devuelve el bloque:

| Función | El objeto se llama | Devuelve |
|---|---|---|
| `let` | `it` | lo último del bloque |
| `run` | `this` | lo último del bloque |
| `with` | `this` | lo último del bloque |
| `apply` | `this` | el propio objeto |
| `also` | `it` | el propio objeto |

Regla práctica: `apply` para configurar y devolver lo configurado, `let` para transformar o para
ejecutar algo solo si no es nulo.

---

## Corrutinas

**25. ▲ ¿Qué es una corrutina?**
Una tarea que puede pausarse y reanudarse sin bloquear el hilo. Permite escribir código que
espera (red, disco, base de datos) de forma secuencial y legible, sin callbacks anidados.

**26. ▲ ¿Qué significa `suspend`?**
Que la función puede pausarse, y por tanto solo se puede llamar desde otra `suspend` o desde una
corrutina. No crea hilos por sí sola.

**27. ¿Diferencia entre `launch` y `async`?**
`launch` lanza y no devuelve resultado, solo un `Job` para cancelar o esperar. `async` devuelve
un `Deferred` del que se obtiene el resultado con `await()`, y sirve para lanzar varias tareas
en paralelo y juntar los resultados.

**28. ▲ ¿Qué son los dispatchers?**
Deciden en qué hilos corre la corrutina. `Main` para la interfaz, `IO` para red y disco,
`Default` para cálculo intensivo. Se cambia con `withContext(Dispatchers.IO) { ... }`.

**29. ¿Qué es el ámbito estructurado?**
Que cada corrutina pertenece a un ámbito, y al cancelarse el ámbito se cancelan todas sus hijas.
Es lo que evita el trabajo huérfano: si la pantalla se cierra, sus peticiones se cancelan solas.

**30. ▲ ¿Diferencia entre `Flow` y `StateFlow`?**
`Flow` es frío: no produce nada hasta que alguien lo recoge, y cada recolector recibe su propia
ejecución. `StateFlow` es caliente y siempre tiene un valor actual, que entrega de inmediato a
quien se suscriba. Para estado de pantalla se usa `StateFlow`; para un flujo de eventos que
ocurren una vez, `Flow` o `SharedFlow`.

---

## Delegación

**31. ¿Qué es `by lazy`?**
Retrasa la creación del valor hasta el primer uso y la hace una sola vez. Solo para `val`.

**32. ¿Qué es delegar una propiedad con `by`?**
Dejar que otro objeto se encargue de leer y escribir esa propiedad. `by lazy` es un caso; otros
son `by viewModels()` en Android o guardar el valor en un mapa.

---

## Preguntas trampa, donde Kotlin no se parece a Java

**33. ▲ ¿`==` compara referencias?**
No. En Kotlin `==` llama a `equals`, y `===` es el que compara si son el mismo objeto. Es al
revés de lo que espera quien viene de Java.

**34. ▲ Una `data class` con `val`, ¿es inmutable?**
Sus propiedades no se reasignan, pero si alguna contiene una lista mutable, su contenido sí
puede cambiar. La inmutabilidad no se hereda hacia dentro.

**35. ¿Se puede sobrescribir una función de extensión?**
No. Se resuelven por el tipo declarado, no por el objeto real en ejecución.

**36. ¿Qué es una excepción comprobada en Kotlin?**
No existen. Kotlin no obliga a declarar ni capturar excepciones, a diferencia del `throws` de
Java.

**37. ¿`try` puede devolver un valor?**
Sí, también es una expresión: `val n = try { texto.toInt() } catch (e: Exception) { 0 }`.
