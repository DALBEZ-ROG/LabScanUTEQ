# Guion del video de demostración — LabScan UTEQ

**Duración objetivo: 4 minutos.** Grabar en vertical con la pantalla del teléfono y voz en off.

## Antes de empezar

Cinco minutos de preparación que evitan tener que regrabar:

- [ ] **Modelo cargado**: `model.tflite` en `assets/` y `useStubDetector` en `false`. Si se graba
      en modo demostración, la banda ámbar "Detector de prueba activo" sale en todos los planos.
- [ ] **Backend accesible**, o `USE_MOCK_API` en `true`. Comprobar antes que la banda "El asistente
      no está disponible" **no** aparece.
- [ ] **No molestar activado**, brillo al máximo, batería sobre el 50 %.
- [ ] **Rotación automática desactivada** en vertical, salvo para el plano de rotación.
- [ ] Dos o tres equipos sobre la mesa, separados, con luz pareja y sin contraluz.
- [ ] Ensayar una vez la pregunta por voz: es lo único que puede fallar por ruido de fondo.
- [ ] Grabar con `adb shell screenrecord --size 1080x2340 /sdcard/demo.mp4` o con la grabadora del
      sistema. Después: `adb pull /sdcard/demo.mp4`.

---

## Escaleta

### 0:00 – 0:20 · Presentación

**Se ve:** icono de la app y apertura, con el splash.

> "LabScan UTEQ es un asistente móvil para el Laboratorio de Biotecnología de la Universidad
> Técnica Estatal de Quevedo. Está pensado para un estudiante de primer semestre que entra al
> laboratorio y no sabe qué es cada equipo ni cómo se usa."

**Momento exacto a capturar:** el arranque completo, desde el lanzador hasta la cámara en vivo. No
cortarlo: demuestra que la app abre rápido.

---

### 0:20 – 1:00 · Detección múltiple

**Se ve:** la cámara apuntando a **dos o tres equipos a la vez**, moviendo el teléfono despacio.

> "La app reconoce los equipos en tiempo real, en el propio teléfono. No hay ninguna imagen
> saliendo a internet: la inferencia corre en el dispositivo."

**Momentos exactos a capturar:**
1. Los dos o tres cuadros dibujados **al mismo tiempo**, cada uno con su nombre y su porcentaje.
2. Un movimiento lento del teléfono para que se vea que **los cuadros siguen a los equipos sin
   temblar** (es lo que aporta el suavizado entre frames).
3. Acercarse a un equipo y alejarse, para que el cuadro cambie de tamaño.

**Cuidado:** no mover rápido. Con 8 o 9 detecciones por segundo, un barrido brusco deja los cuadros
atrás y en video se ve peor de lo que es.

---

### 1:00 – 1:35 · Selección y ficha técnica

**Se ve:** el dedo toca uno de los cuadros. El cuadro se pone ámbar, los demás se atenúan y sube la
ficha.

> "Al tocar un equipo se abre su ficha técnica: para qué sirve, sus componentes, el procedimiento
> básico de uso, la protección que hace falta y los riesgos."

**Momentos exactos a capturar:**
1. **El toque y el resaltado**: cuadro ámbar más grueso, los demás atenuados. Es la señal de que la
   app entendió cuál eligió el estudiante.
2. **El desplazamiento por la ficha**, sin prisa, hasta llegar a **"Riesgos asociados"** — que es la
   sección que justifica todo el proyecto en un laboratorio.
3. **"Fuentes consultadas"** al final, con el nombre del documento y la página.

---

### 1:35 – 2:30 · Pregunta por voz y respuesta con fuente

**Se ve:** botón "Preguntar al asistente" → pantalla de chat con el encabezado "Consultando
sobre: …" → mantener pulsado el micrófono → hablar → soltar.

> "Desde la ficha se puede preguntar. Con las manos ocupadas, lo natural es hablar."

**Momentos exactos a capturar:**
1. El **encabezado verde** con el nombre del equipo: prueba que el asistente sabe de qué se habla.
2. **El dedo manteniendo pulsado el micrófono** y el texto apareciendo en vivo en el campo mientras
   se habla. Ese es el plano más convincente del video.
3. **La pregunta transcrita** apareciendo como burbuja del usuario.
4. **La respuesta llegando y leyéndose sola** en voz alta. Dejar que se oiga; no cortar el audio.
5. **Los chips de fuente** debajo de la respuesta, en primer plano.

> "La respuesta no la inventa el modelo de lenguaje: sale de los manuales del laboratorio, y debajo
> se ve exactamente de qué documento y de qué página."

**Pregunta sugerida:** "¿cómo lo enciendo?" — es corta, clara y difícil de transcribir mal.

---

### 2:30 – 3:00 · Información insuficiente

**Se ve:** una segunda pregunta cuya respuesta no está en los documentos.

> "Y cuando la información no está en los manuales, la app no se la inventa."

**Momentos exactos a capturar:**
1. La **burbuja ámbar** con el título "Información insuficiente".
2. Que **no hay ningún chip de fuente** debajo, al lado de la respuesta anterior que sí los tiene.
   Si se puede, dejar las dos burbujas en el mismo plano: la comparación se explica sola.

**Con el backend simulado**, esto sale garantizado en la **segunda** pregunta de cada sesión: el
mock alterna entre respuesta con contexto y sin contexto. Con el backend real, preguntar algo
deliberadamente fuera del temario, por ejemplo "¿cuánto cuesta este equipo?".

**Pregunta sugerida:** "¿cuánto cuesta este equipo?"

---

### 3:00 – 3:30 · Diagnóstico y ajustes

**Se ve:** engranaje → Ajustes → Diagnóstico del modelo.

> "Para el docente y para quien continúe el proyecto, la app enseña por dentro qué modelo está
> corriendo."

**Momentos exactos a capturar:**
1. **Ajustes**: el umbral de confianza, la lectura automática de respuestas y el campo del servidor.
2. **Diagnóstico**: la forma del tensor de entrada y de salida, el número de clases y si cuadran con
   `labels.txt`, y el **rendimiento medido** (FPS, latencia total y latencia de inferencia).
3. El botón **"Copiar diagnóstico"**: sirve para pegar el informe en un reporte.

---

### 3:30 – 4:00 · Modo sin conexión y cierre

**Se ve:** activar el modo avión y volver al escáner.

> "Sin conexión, la app sigue sirviendo: la detección corre en el teléfono y las fichas están
> guardadas dentro de la aplicación."

**Momentos exactos a capturar:**
1. La **banda de "Sin conexión"** apareciendo arriba.
2. Tocar un equipo y ver **la ficha completa igual**, con la etiqueta pequeña
   "Sin conexión: ficha guardada en el teléfono".

> "LabScan UTEQ. Universidad Técnica Estatal de Quevedo, Laboratorio de Biotecnología."

---

## Resumen de tomas obligatorias

| # | Toma | Por qué es obligatoria |
|---|---|---|
| 1 | Dos o tres cuadros simultáneos | Es el requisito de detección múltiple |
| 2 | Cuadros siguiendo el movimiento sin temblar | Demuestra el trabajo de rendimiento |
| 3 | Toque → cuadro ámbar y los demás atenuados | Demuestra la selección |
| 4 | Ficha desplazada hasta "Riesgos asociados" | Es el contenido que justifica el proyecto |
| 5 | Dedo en el micrófono + texto apareciendo en vivo | Demuestra el dictado |
| 6 | Respuesta leyéndose sola, con audio | Demuestra el TTS |
| 7 | Chips de fuente en primer plano | Requisito: toda respuesta cita su fuente |
| 8 | Burbuja ámbar sin fuentes | Requisito: no inventar cuando no sabe |
| 9 | Pantalla de Diagnóstico con el tensor y los FPS | Evidencia técnica para el docente |
| 10 | Modo sin conexión con la ficha funcionando | Demuestra la degradación limpia |

## Errores que arruinan la grabación

- Grabar en **modo demostración** sin darse cuenta: la banda ámbar sale en todos los planos.
- Mover el teléfono rápido: los cuadros se quedan atrás y parece que la app va mal.
- Hablarle al micrófono **mientras el asistente está leyendo** la respuesta anterior: el reconocedor
  se oye a sí mismo. Esperar a que termine, o tocar "detener".
- Notificaciones entrando a mitad del video.
- Olvidar que el micrófono pide permiso **la primera vez**: hacer una prueba antes para que el
  diálogo no salga durante la grabación.
