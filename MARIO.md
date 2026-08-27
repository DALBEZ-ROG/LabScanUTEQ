# MARIO.md — Punto de entrada para Mario

> **Agente: si el usuario NO se identificó como Mario, ignora este archivo.** Este documento no
> describe trabajo sobre el proyecto Android. Es el encargo de la otra mitad del sistema, que
> vive en un repositorio distinto. Vuelve a `CLAUDE.md`.

---

Hola Mario. Este archivo te dice exactamente qué te toca y qué ya está resuelto.

## Reparto del trabajo

| Pieza | Quién | Estado |
|---|---|---|
| App Android: cámara, cuadros, ficha técnica, chat, modo de voz | Dariem | Terminada |
| Contratos y documentación de integración | Dariem | Terminada |
| Detector YOLO entrenado y exportado a `.tflite` | **Mario** | En curso |
| Backend RAG: PDF + índice + LLM | **Mario** | Pendiente |
| Digitalizar manuales y guías del laboratorio | **Mario** | Pendiente |

La app ya funciona completa en modo demostración: con un detector simulado y con respuestas
falsas desde un mock. Solo faltan tus dos piezas para que sea real. Dariem no puede avanzar más
sin ellas, y tú no necesitas nada de él para empezar.

## Tus dos tareas

### Tarea 1 — El detector

Todo el procedimiento está en **`docs/INTEGRACION_MODELO.md`**. Léelo completo.

Resumen: etiquetar en Roboflow o Label Studio, entrenar YOLOv8n en Colab con Ultralytics,
exportar a TFLite int8, y copiar tres archivos a `app/src/main/assets/`. Ningún archivo `.kt`
se modifica. La app trae una pantalla de Diagnóstico para que verifiques tu modelo sin programar.

**Importante:** Teachable Machine no sirve aquí. Entrena un clasificador, no un detector: no
devuelve coordenadas, así que no puede dibujar cuadros ni detectar varios equipos a la vez.
La sección 1 de ese documento lo explica.

### Tarea 2 — El backend RAG

Es un proyecto Python **separado**, en su propio repositorio. No va dentro del repo Android.

El contrato HTTP que debe cumplir está en **`docs/CONTRATO_API.md`**. Ese contrato está cerrado:
la app ya está programada contra él. Si algo no te calza, avísale a Dariem antes de cambiarlo.

Al final de este archivo tienes el prompt completo para construirlo con tu agente.

## La regla que conecta todo

El único puente entre el detector y el RAG es una cadena de texto: **el identificador de clase**.

```
labels.txt del modelo   →  "incubadora"
carpeta de manuales     →  manuals/incubadora/
metadato en el índice   →  equipment_id: "incubadora"
lo que envía la app     →  { "classId": "incubadora", ... }
```

Los cuatro tienen que coincidir carácter por carácter. `snake_case`, sin tildes, sin espacios,
sin mayúsculas. Si el modelo dice `incubadora` y tú indexaste como `incubadora_memmert`, la app
va a preguntar por un equipo que tu backend no conoce y el estudiante recibirá "no dispongo de
información suficiente" siempre.

**Define la lista de clases con Dariem antes de entrenar y antes de indexar.**

## Sobre la API de Claude

- La cuenta de claude.ai **no** incluye acceso a la API. Hay que crear una cuenta aparte en
  `console.anthropic.com` y cargar créditos prepago.
- Modelo a usar: `claude-haiku-4-5`. Rápido y barato, que es lo que necesita un chat en vivo.
- Costo estimado del proyecto completo, entre desarrollo, pruebas y demo: alrededor de 1 dólar.
- **La API key vive solo en tu backend, en un archivo `.env` que nunca se sube a git.** Jamás
  en la app Android: un APK se descompila en minutos y cualquiera te vaciaría el crédito.

## Todavía no tienes los PDF, y no importa

El backend debe arrancar y responder correctamente con el índice vacío, devolviendo el mensaje
de información insuficiente que define el contrato. Así lo especifica el prompt. Construyes hoy,
indexas cuando consigas los manuales.

---

## Prompt para tu agente — Backend RAG

Copia todo lo que sigue y pásalo a Claude Code en una carpeta nueva y vacía.

```
ROL
Eres un ingeniero backend senior en Python, con experiencia en FastAPI, sistemas RAG,
bases de datos vectoriales y la API de Anthropic. Trabajas de forma autónoma: creas el entorno,
instalas dependencias, ejecutas el servidor y verificas los endpoints tú mismo.

CONTEXTO
Proyecto académico "LabScan UTEQ", Universidad Técnica Estatal de Quevedo. Una app Android ya
terminada detecta equipos de laboratorio con un modelo YOLO en tiempo real y muestra cuadros
con el nombre y la confianza. Cuando el estudiante toca un equipo, puede preguntar por escrito
o por voz cómo se usa, cómo se enciende, qué riesgos tiene.

Tú construyes el servicio que responde esas preguntas. Consulta exclusivamente los manuales,
guías de práctica, protocolos y normas de seguridad del laboratorio, y cita siempre la fuente.
Si la información no está en los documentos, debe decirlo y recomendar consultar al docente.

La app Android NO se modifica y NO envía imágenes ni documentos: solo manda el identificador de
clase que detectó el modelo y el texto de la pregunta. Tú haces la recuperación y armas el
contexto. Esto es un requisito explícito de la actividad académica.

OBJETIVO
Un servicio HTTP que cumpla al pie de la letra el contrato descrito abajo, más una herramienta
de línea de comandos para indexar PDF. Debe funcionar desde el primer arranque aunque todavía
no haya ningún documento indexado.

STACK TÉCNICO
- Python 3.11, FastAPI, uvicorn, pydantic v2
- ChromaDB con persistencia en disco (carpeta ./storage)
- sentence-transformers, modelo "paraphrase-multilingual-MiniLM-L12-v2" (gratuito, funciona en
  español, corre local). Anthropic NO tiene endpoint de embeddings, no lo busques.
- pymupdf para extraer texto con número de página
- SDK oficial "anthropic", modelo claude-haiku-4-5
- python-dotenv. La API key se lee de ANTHROPIC_API_KEY en .env
- pytest para las pruebas

CONTRATO HTTP (cerrado, no lo modifiques)

GET /api/health
  200 -> { "status": "ok", "indexedDocuments": 34, "model": "claude-haiku-4-5" }
  indexedDocuments es el número de fragmentos en el índice. Si es 0, status sigue siendo "ok".

GET /api/equipment/{classId}
  classId es un identificador en snake_case, por ejemplo "microscopio_binocular".
  200 -> {
    "classId": "microscopio_binocular",
    "displayName": "Microscopio binocular",
    "shortDescription": "...",
    "function": "...",
    "components": ["Oculares", "Platina", "..."],
    "basicProcedure": ["Paso 1...", "Paso 2..."],
    "ppe": ["Bata de laboratorio", "Guantes de nitrilo"],
    "risks": ["..."],
    "relatedPractices": ["Práctica 3: ..."],
    "sources": [ { "title": "...", "page": 12, "documentId": "..." } ]
  }
  404 si no hay documentos de ese equipo, con el cuerpo de error estándar.

POST /api/chat
  Petición -> {
    "classId": "incubadora" | null,
    "message": "¿cómo la enciendo?",
    "history": [ { "role": "user"|"assistant", "content": "..." } ],
    "voiceMode": false
  }
  200 -> {
    "answer": "...",
    "hasSufficientContext": true,
    "sources": [ { "title": "...", "page": 8, "documentId": "...", "snippet": "..." } ]
  }

  Cuando no haya contexto suficiente:
  {
    "answer": "No dispongo de información suficiente sobre eso en los documentos del laboratorio. Se recomienda consultar al docente o al responsable del laboratorio.",
    "hasSufficientContext": false,
    "sources": []
  }

Error estándar para cualquier 4xx o 5xx:
  { "error": { "code": "EQUIPMENT_NOT_FOUND", "message": "texto legible en español" } }
  Códigos: EQUIPMENT_NOT_FOUND, INDEX_NOT_READY, LLM_UNAVAILABLE, RATE_LIMITED, INTERNAL_ERROR

TAREA EXACTA

1. Estructura del proyecto:
   app/main.py            FastAPI, CORS abierto, arranque en 0.0.0.0:8000
   app/config.py          settings desde .env
   app/models.py          modelos pydantic que espejan el contrato exactamente
   app/ingest.py          extracción de PDF, troceado, embeddings, escritura en Chroma
   app/retrieval.py       búsqueda filtrada por equipment_id
   app/llm.py             cliente de Anthropic y construcción del prompt
   app/cards.py           generación y caché de las fichas técnicas
   app/routes/            un archivo por endpoint
   manuals/               PDF de entrada, una subcarpeta por equipo
   storage/               persistencia de Chroma (en .gitignore)
   tests/
   .env.example, README.md, requirements.txt

2. Indexación (app/ingest.py, ejecutable como CLI):
   - Convención de carpetas: manuals/<equipment_id>/*.pdf. El nombre de la carpeta ES el
     equipment_id y debe coincidir exactamente con el labels.txt del modelo YOLO.
   - Extrae el texto con pymupdf conservando el número de página de cada bloque.
   - Trocea en fragmentos de unas 500 palabras con 100 de solapamiento, sin cortar oraciones
     a la mitad.
   - Cada fragmento se guarda con estos metadatos: equipment_id, doc_title (nombre del PDF sin
     extensión), document_id (slug del nombre), page.
   - Reindexar el mismo PDF reemplaza sus fragmentos, no los duplica.
   - Comandos: `python -m app.ingest --all` y `python -m app.ingest --equipment incubadora`.
   - Al terminar imprime un resumen: equipos indexados, documentos, fragmentos por equipo.

3. Recuperación (app/retrieval.py):
   - Filtra SIEMPRE por metadato equipment_id igual al classId recibido. Este filtro es lo que
     garantiza que nunca se responda con el manual de otro equipo.
   - Sobre ese subconjunto, busca los 4 fragmentos más similares a la pregunta.
   - Umbral de similitud configurable, por defecto 0.35. Si ningún fragmento lo supera, o si no
     hay fragmentos para ese equipment_id, devuelve lista vacía.
   - Si classId es null, busca en todo el índice.

4. Generación (app/llm.py):
   - Si la recuperación devuelve vacío, NO llames al LLM: responde directamente con el objeto de
     contexto insuficiente. Ahorra dinero y evita respuestas inventadas.
   - System prompt en español que exija: responder únicamente con los fragmentos entregados;
     nunca inventar ni completar con conocimiento general; si los fragmentos no alcanzan,
     decirlo; tratar al usuario como estudiante de primer semestre sin experiencia en el
     laboratorio; ser concreto en los procedimientos; advertir sobre riesgos cuando aplique.
   - Los fragmentos se pasan numerados con su título y página para que la respuesta pueda
     referenciarlos.
   - history se recorta a los últimos 6 turnos.
   - Si voiceMode es true, agrega al system prompt: responder en registro hablado, máximo 3
     oraciones, sin listas, sin viñetas, sin markdown, sin mencionar números de página ni URLs.
   - hasSufficientContext se determina en el código por el resultado de la recuperación, no
     preguntándole al modelo.
   - sources se arma con los metadatos de los fragmentos realmente usados, sin duplicados.

5. Fichas técnicas (app/cards.py):
   - GET /api/equipment/{classId} no debe llamar al LLM en cada petición.
   - Al indexar un equipo, genera su ficha una sola vez con una llamada a Claude sobre sus
     fragmentos, pidiendo salida JSON estricta con los campos del contrato, y guárdala en
     storage/equipment_cards.json.
   - El endpoint sirve desde ese archivo. Comando `python -m app.cards --rebuild` para regenerar.
   - Si un campo no se puede derivar de los documentos, devuélvelo como lista o cadena vacía.
     La app oculta las secciones vacías.

6. Robustez:
   - Índice vacío: /api/health responde ok con indexedDocuments 0, /api/chat responde el objeto
     de contexto insuficiente, /api/equipment responde 404. El servidor nunca falla al arrancar.
   - Errores de la API de Anthropic: mapea a LLM_UNAVAILABLE o RATE_LIMITED, con reintento y
     backoff exponencial hasta 2 veces.
   - Ninguna excepción cruda llega al cliente. Manejador global que devuelve el error estándar.
   - Registra en log cada petición con classId, número de fragmentos recuperados y latencia.

7. Pruebas (pytest) que cubran como mínimo:
   - el troceado conserva el número de página correcto
   - la recuperación con equipment_id "incubadora" nunca devuelve fragmentos de otro equipo
   - con índice vacío, /api/chat devuelve hasSufficientContext false y no llama al LLM
   - las respuestas validan contra los modelos pydantic del contrato

8. README.md con: instalación, cómo crear el .env, cómo colocar los PDF, comandos de indexación,
   cómo levantar el servidor, y cómo probar desde el teléfono en la misma red wifi
   (uvicorn en 0.0.0.0, la IP local del PC, y el firewall de Windows permitiendo el puerto 8000).

9. Ejecuta el servidor, prueba los tres endpoints con curl, y muestra las respuestas reales.

RESTRICCIONES
- Prohibido enviar documentos completos al LLM. Solo los fragmentos recuperados.
- Prohibido que el LLM responda con conocimiento general cuando no hay contexto.
- Prohibido cambiar nombres de campo del contrato. Si algo no calza, déjalo anotado en el README
  y avísalo en tu resumen, pero no lo cambies.
- Prohibido subir la API key. .env va en .gitignore y se entrega .env.example sin valores.
- Prohibido usar OpenAI o cualquier proveedor distinto de Anthropic para la generación.
- No implementes autenticación ni multiusuario: es un proyecto académico en red local.

FORMATO DE SALIDA
Ejecuta todo tú mismo. Al final entrega: árbol de archivos, contenido de requirements.txt y
.env.example, el system prompt completo que usas para la generación, la salida real de curl
contra los tres endpoints con el índice vacío, y las instrucciones para que el teléfono alcance
el servidor en la red local.
```

## Cuando termines

1. Copia `model.tflite` y `labels.txt` a `app/src/main/assets/` y pon `useStubDetector` en `false`
   dentro de `model_config.json`.
2. Levanta el backend y dile a Dariem la IP local para que la configure en la app.
3. Anota en `docs/PROGRESO.md` qué quedó hecho y qué falta.
