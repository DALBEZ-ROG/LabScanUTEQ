# Contrato API — Backend RAG

Este contrato es **fijo**. La app Android se construye contra él antes de que el backend exista,
usando un mock local. Cambiarlo requiere acuerdo de ambas partes y una entrada en `docs/DECISIONES.md`.

`BASE_URL` se configura en `build.gradle.kts` por variante:
- `debug`: `http://10.0.2.2:8000/` (emulador) o la IP LAN del PC del backend
- `release`: URL de despliegue

Todas las respuestas son `application/json; charset=utf-8`.

---

## GET `/api/health`

Verificación de disponibilidad. La app la llama al arrancar para decidir si muestra el chat
o el modo sin conexión.

```json
{ "status": "ok", "indexedDocuments": 34, "model": "claude-haiku-4-5" }
```

---

## GET `/api/equipment/{classId}`

`classId` es exactamente una línea de `labels.txt` (ej. `microscopio_binocular`).
Devuelve la ficha técnica que se muestra al tocar una detección.

```json
{
  "classId": "microscopio_binocular",
  "displayName": "Microscopio binocular",
  "shortDescription": "Instrumento óptico para observar muestras a 40x–1000x.",
  "function": "Ampliar muestras biológicas mediante un sistema de lentes...",
  "components": ["Oculares", "Revólver portaobjetivos", "Platina", "Condensador", "Tornillo macrométrico"],
  "basicProcedure": [
    "Conectar el equipo y encender la lámpara con el interruptor lateral.",
    "Colocar la preparación sobre la platina y sujetarla con las pinzas.",
    "Iniciar con el objetivo de 4x y enfocar con el tornillo macrométrico."
  ],
  "ppe": ["Bata de laboratorio", "Guantes de nitrilo"],
  "risks": ["Descarga eléctrica por manipular el equipo con las manos húmedas."],
  "relatedPractices": ["Práctica 3: Observación de células vegetales"],
  "sources": [
    { "title": "Guía de prácticas de Biotecnología UTEQ", "page": 12, "documentId": "guia_biotec_2024" }
  ]
}
```

- Si `classId` no existe: **404** con el cuerpo de error de más abajo. La app cae al `catalog.json` local.
- Los campos de texto pueden venir vacíos; la app oculta esas secciones.

---

## POST `/api/chat`

El endpoint principal. La app **nunca** envía imágenes ni documentos, solo texto y el `classId`
del equipo seleccionado. El backend recupera los fragmentos pertinentes y arma el contexto.

Petición:
```json
{
  "classId": "microscopio_binocular",
  "message": "¿Cómo lo apago correctamente?",
  "history": [
    { "role": "user", "content": "¿Para qué sirve?" },
    { "role": "assistant", "content": "Sirve para observar muestras..." }
  ]
}
```

- `history`: máximo los últimos 6 turnos. La app la trunca antes de enviar.
- `classId` puede ser `null` si el usuario abre el chat sin haber seleccionado un equipo.
- `voiceMode` (booleano, opcional, por defecto `false`): la pregunta viene del modo de
  conversación por voz manos libres. Ver abajo.

### `voiceMode: true` — registro hablado

Cuando la app envía `"voiceMode": true`, la respuesta **se va a leer en voz alta**, no a leer con
los ojos. El backend debe responder en registro hablado:

- **Máximo tres oraciones.** Una respuesta larga se convierte en medio minuto de audio que el
  estudiante no puede saltar ni releer.
- **Sin listas ni viñetas.** Un motor de síntesis lee los guiones como "menos".
- **Sin markdown.** Los asteriscos se pronuncian.
- **Sin URLs ni números de página dentro del texto.** "Manual CX23 página 8" dicho en voz alta
  interrumpe la frase y no se puede anotar.

`sources` **se sigue devolviendo igual**, con la misma forma y las mismas reglas: la app las
**muestra en pantalla** y **no las lee en voz alta**. La regla 6 de CLAUDE.md se cumple viéndolas,
no oyéndolas.

El campo es solo una bandera. La app **no** reescribe ni recorta la respuesta que llegue: recortar
a tres oraciones en el teléfono podría cortar una advertencia de seguridad por la mitad.

Petición en modo de voz:
```json
{
  "classId": "microscopio_binocular",
  "message": "¿cómo lo apago?",
  "history": [],
  "voiceMode": true
}
```

Respuesta:
```json
{
  "answer": "Baje la intensidad de la lámpara al mínimo antes de apagar el interruptor...",
  "hasSufficientContext": true,
  "sources": [
    { "title": "Manual Olympus CX23", "page": 8, "documentId": "manual_cx23", "snippet": "Apagado del equipo..." }
  ]
}
```

**Cuando no hay información suficiente** en los documentos, el backend responde con:
```json
{
  "answer": "No dispongo de información suficiente sobre eso en los documentos del laboratorio. Se recomienda consultar al docente o al responsable del laboratorio.",
  "hasSufficientContext": false,
  "sources": []
}
```

La app muestra ese mensaje con un estilo visual distinto (fondo ámbar) y **no** lee la sección
de fuentes en voz alta.

### `fromWeb`, respuestas que no salen de los manuales

Desde el 2026-09-10 el backend puede responder **buscando en internet** cuando los manuales del
laboratorio no cubren la pregunta. Esas respuestas llevan `fromWeb: true` y sus fuentes son
direcciones web:

```json
{
  "answer": "Esto no está en los manuales del laboratorio. Lo encontré en internet...",
  "hasSufficientContext": true,
  "fromWeb": true,
  "sources": [
    { "title": "Web: evidentscientific.com", "documentId": "https://evidentscientific.com/...", "page": null }
  ]
}
```

Reglas del campo:

- **Es opcional y su valor por defecto es `false`.** Un backend anterior no lo envía y la app lo
  interpreta como "vino de los manuales", que es lo correcto. No es un cambio de contrato que
  rompa nada.
- `hasSufficientContext` sigue significando "hay una respuesta que dar", no "salió del manual".
  Lo que distingue las dos cosas es `fromWeb`.
- Las fuentes web traen la dirección completa en `documentId` y el dominio en `title`, con el
  prefijo `Web:`. Nunca traen `page`.
- La app **tiene que avisarlo en pantalla antes del texto de la respuesta**, no solo en las
  fuentes. Lo hace con un encabezado y un icono de globo terráqueo en la propia burbuja.

Cuándo se activa la búsqueda: solo si la recuperación local no devolvió fragmentos, **o** si los
devolvió pero el modelo declaró que no responden la pregunta. Y nunca con el índice vacío, que
es señal de un backend a medio montar y no de una pregunta no cubierta. Ver D-041.

---

## Error estándar

Cualquier código 4xx o 5xx:
```json
{ "error": { "code": "EQUIPMENT_NOT_FOUND", "message": "Descripción legible en español." } }
```

Códigos previstos: `EQUIPMENT_NOT_FOUND`, `INDEX_NOT_READY`, `LLM_UNAVAILABLE`, `RATE_LIMITED`, `INTERNAL_ERROR`.

---

## Comportamiento de la app ante fallos

| Situación | Qué hace la app |
|---|---|
| Timeout (>15 s) o sin red | Muestra la ficha desde `assets/catalog.json` y desactiva el chat con aviso |
| 404 en `/api/equipment` | Cae al catálogo local |
| 5xx en `/api/chat` | Mensaje de error reintentables con botón "Reintentar" |
| `hasSufficientContext: false` | Muestra la respuesta con estilo de advertencia, sin fuentes |
| `fromWeb: true` | Encabezado con globo terráqueo en la burbuja, y las fuentes con icono de web |
| Sin red **en modo de voz** | Lo dice en voz alta una vez y sale del modo (única excepción a "los errores no se leen", ver D-025) |
| 5xx **en modo de voz** | Lo muestra en pantalla, no lo lee, y sigue escuchando para que el estudiante repita |

## Mock para desarrollo

La fase F5 incluye un `MockRagServer` (OkHttp `MockWebServer` o un interceptor que lee JSON de
`assets/mock/`) activado con `BuildConfig.USE_MOCK_API = true`. Permite terminar el chat, la voz
y la UI completa sin que el backend exista.
