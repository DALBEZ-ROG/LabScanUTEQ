# Sesión de depuración en dispositivo — 2026-09-09

Teléfono: **Samsung SM-A566E ("S26 de Dariem")**, Android 16, por depuración inalámbrica.
Backend corriendo en el PC de Mario. Todo lo de abajo está **medido**, no supuesto.

---

## Lo que quedó ARREGLADO en esta sesión

### 1. "El asistente no está disponible" — era la IP, y era peor de lo que parecía

Mario cambió de red y el router le dio otra dirección: `192.168.100.25` → `172.20.135.199`.
El APK llevaba la vieja compilada dentro.

**Lo grave no era eso, sino que cambiar la URL en Ajustes no lo arreglaba.** El
`network_security_config` tenía una lista blanca de IP literales dentro del APK, así que Android
bloqueaba el tráfico en claro a cualquier otra dirección. Cada cambio de red obligaba a editar dos
archivos y recompilar.

Arreglado en **D-034**: la variante de depuración permite tráfico en claro a cualquier host. La de
release sigue prohibiéndolo (no declara `networkSecurityConfig`), así que el permiso no puede
colarse en la app publicada.

**Verificado:** el servidor registró `172.20.135.194 - "GET /api/health" 200 OK` y la banda de
aviso desapareció de la pantalla.

### 2. El modelo carga y detecta

```
Modelo cargado: entrada 1x640x640x3 FLOAT32, salida 1x54x8400 FLOAT32,
                50 clases, disposicion TRANSPOSED
Detector real activo con model.tflite
Camara enganchada, analisis=true
```

### 3. Sin crashes

`logcat -b crash` vacío. Los únicos avisos del proceso son ruido del sistema (`ashmem`,
`vendor.perf.ems.egg`, propiedades de MediaTek); ninguno sale de nuestro código.

---

## Lo que hay que ARREGLAR, por orden de importancia

### A. Rendimiento: 2,7 FPS. Es el problema más urgente.

Leído del HUD en pantalla y confirmado en el log:

```
2.7 FPS · 263 ms (254 inf) · 640x360 · rot 90
```

El objetivo de F7 son **≥10 FPS**. Estamos a menos de un tercio, y es peor que los 3,2 FPS
medidos el 5 de septiembre. De los 263 ms, **254 son inferencia pura**: la preparación del frame
ya está optimizada (D-019) y no queda nada que rascar ahí.

**Qué hacer, en este orden:**

1. **Export int8.** Es la palanca grande y está sin usar. Un modelo cuantizado suele ir 2–3 veces
   más rápido en CPU. Con eso se pasaría de ~254 ms a ~85–125 ms, es decir 8–11 FPS.
2. **Bajar `inputSize` a 416.** La app ya lo admite sin tocar código, lo lee del tensor. Son
   2,37 veces menos píxeles. Combinado con int8 daría margen de sobra.
3. El delegado GPU ya se probó en F7 y **perdía** contra la CPU (D-018). No volver a intentarlo
   sin medir.

### B. Falsos positivos con mucha confianza

Con la cámara apuntando a una escena cualquiera:

```
Detecciones (242 ms): refrigeradora 58%
Detecciones (239 ms): refrigeradora 91%
```

`refrigeradora` es **una de las tres clases genéricas intrusas** que había que borrar de Roboflow
(las otras son `agitador` y `camara_electroforesis`). Está apareciendo al 91 % sobre algo que no
es una refrigeradora.

Y el caso que reportó Mario: una microcentrífuga **Labnet Prism R** etiquetada como
`espectrofotometro_visible_digital_unico_1205` al 89 %, cuando la clase `microcentrifuga` existe
y **es exactamente ese aparato**.

Causa medida y documentada en **D-033**: el modelo acierta 29 de 30 sobre las fotos con las que se
entrenó y falla sobre el mismo aparato desde otro ángulo. Es memorización, no reconocimiento.

**Qué hacer:** borrar las tres clases intrusas, anotar las cinco clases que tienen fotos pero no
cajas, y sobre todo **más fotos por equipo desde ángulos, alturas y distancias distintos**. Las
que Mario está tomando ahora con el teléfono en el laboratorio son justo el dato que falta:
misma cámara, misma luz y mismo encuadre que tendrá la app en uso.

### C. Frames descartados por la cámara

```
CameraDeviceClient: notifyError: errorCode=3, errorStreamId=0, frameNumber=42
Camera3-OutputStream: A frame is dropped for stream 1 frame 42, due to buffer error
```

Aparecen en ráfagas mientras corre el análisis. Lo más probable es que sean **consecuencia** de la
lentitud: con 263 ms por frame, CameraX retiene los buffers más de lo que el proveedor espera y
descarta. No tumba nada —`FrameAnalyzer` los atrapa y sigue— pero conviene volver a mirarlo
**después** de arreglar el rendimiento, no antes: si desaparecen solos, no había nada que arreglar.

---

## Lo que NO se pudo probar, y por qué

**El chat de punta a punta desde la app.** Para llegar a la pantalla del asistente hay que tocar
una caja de detección, y eso exige apuntar la cámara a un equipo real; desde aquí no se puede
mover el teléfono. Lo que sí está verificado:

- La app alcanza el backend: `GET /api/health` → 200 OK desde el teléfono.
- El chat responde bien probado con `curl` contra ese mismo servidor, citando documento y página.

Queda por ver en pantalla la burbuja con la respuesta y los chips de fuente. Es lo primero que hay
que hacer con un equipo delante.

**El modo de voz y el dictado.** Mismo motivo, más que `adb` no puede inyectar audio.

---

## Cómo dejar esto corriendo otra vez

```bash
# 1. backend (deja la terminal abierta)
cd C:\Users\Mario\PythonProjects\labscan-rag
.venv\Scripts\python.exe -m uvicorn app.main:api --host 0.0.0.0 --port 8000

# 2. comprobar la IP actual del PC; si cambió, ya NO hay que recompilar:
#    basta escribirla en la app, en Ajustes -> URL del servidor
ipconfig

# 3. el teléfono, en el mismo wifi, con depuración inalámbrica activada
adb connect <IP_DEL_TELEFONO>:<PUERTO>
adb logcat -s LabScan
```

El puerto de depuración inalámbrica **cambia cada vez** que se reactiva o se cambia de red; el
emparejamiento sí se conserva.
