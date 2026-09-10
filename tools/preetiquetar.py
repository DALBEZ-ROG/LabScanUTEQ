"""Pre-dibuja una caja en cada foto usando el modelo v1, para no anotar 748 a mano.

### La idea

Las fotos estan en `roboflow_upload/<nombre_de_clase>/`, asi que **la clase ya se sabe por
la carpeta**. Lo unico que falta es la caja. Eso convierte un problema de 54 clases en uno
de una sola, y para eso el detector v1 sirve perfectamente aunque sus nombres de clase sean
los antiguos: aqui se le ignora la clase que predice y solo se le toma la caja.

El resultado es una carpeta en formato YOLOv8 lista para subir a Roboflow, con las cajas ya
dibujadas y la clase correcta. El trabajo pasa de dibujar 748 cajas a revisarlas.

### Lo que NO hace

- **Solo marca un objeto por foto**, el de mayor puntuacion. Si en la foto salen dos o tres
  aparatos, los demas hay que anotarlos a mano en Roboflow. Se hace asi a proposito: la
  clase viene de la carpeta y es una sola, asi que una segunda caja automatica se etiquetaria
  con la clase equivocada, y una etiqueta mal puesta cuesta mas de arreglar que una que falta.
- **No inventa una caja cuando el modelo no ve nada.** Esas fotos salen en la lista de
  pendientes y hay que anotarlas a mano. Es preferible a poner una caja del tamano de la foto
  entera, que le ensenaria al modelo que el equipo siempre ocupa toda la pantalla.

### Uso

    python tools/preetiquetar.py \
        --fotos "C:/Users/Mario/OneDrive/Documentos/ProyectoMobil/roboflow_upload" \
        --salida "C:/Users/Mario/Downloads/preetiquetado"

Despues: subir la carpeta de salida a Roboflow, que reconoce el formato YOLOv8 y trae las
cajas puestas.

Al subir, en el dialogo de reparto **cambiar "Use Existing Values" por un reparto 70/15/15**.
Aqui todo esta en `train/`, asi que respetar lo existente deja 0 % de validacion y 0 % de
test, y entonces no hay nada con que medir el modelo.
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

import numpy as np
from PIL import Image

TAM = 640
GRIS = 114


def letterbox(imagen: Image.Image, tam: int = TAM):
    """
    Redimensiona sin deformar y rellena con gris hasta un cuadrado.

    Devuelve `(lienzo, escala, pad_x, pad_y)`. Es el MISMO tratamiento que hace la app en
    `Letterbox.kt`; si aqui se estirara la imagen, las cajas saldrian corridas.
    """
    w, h = imagen.size
    escala = min(tam / w, tam / h)
    nw, nh = int(round(w * escala)), int(round(h * escala))
    redimensionada = imagen.resize((nw, nh), Image.BILINEAR)

    lienzo = Image.new("RGB", (tam, tam), (GRIS, GRIS, GRIS))
    pad_x, pad_y = (tam - nw) // 2, (tam - nh) // 2
    lienzo.paste(redimensionada, (pad_x, pad_y))
    return lienzo, escala, pad_x, pad_y


def _xyxy(cx: float, cy: float, w: float, h: float):
    return cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2


def _se_tocan(a, b, holgura: float = 12.0) -> bool:
    """Si dos rectangulos se solapan o casi, con unos pixeles de margen."""
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    return not (
        ax2 + holgura < bx1
        or bx2 + holgura < ax1
        or ay2 + holgura < by1
        or by2 + holgura < ay1
    )


def caja_del_aparato(
    salida: np.ndarray,
    umbral: float,
    fusionar: bool = True,
) -> tuple[float, float, float, float, float] | None:
    """
    La caja del aparato completo, uniendo las partes que el modelo detecta por separado.

    El modelo v1 tiende a encuadrar **una parte** del equipo y no el conjunto: en el agitador
    orbital marca la base azul de los mandos y deja fuera la bandeja verde de arriba; en la
    balanza analitica marca la urna de vidrio y deja fuera el cuerpo con el visor. Tomando
    solo la caja de mayor puntuacion, la mitad de las etiquetas salian cortadas.

    Como en cada carpeta hay un solo aparato, se puede unir sin riesgo: se parte de la mejor
    caja y se le suman las que la tocan y tienen al menos la mitad de su puntuacion. El
    resultado es el rectangulo que envuelve al conjunto.

    `fusionar=False` deja el comportamiento antiguo, por si en alguna clase la union se pasa.
    """
    datos = salida[0]
    cajas = datos[:4]
    puntuaciones = datos[4:].max(axis=0)

    mejor = int(puntuaciones.argmax())
    punta = float(puntuaciones[mejor])
    if punta < umbral:
        return None

    x1, y1, x2, y2 = _xyxy(*(float(v) for v in cajas[:, mejor]))

    if fusionar:
        # La mitad de la mejor puntuacion: por debajo de eso ya no es una parte del aparato,
        # es ruido de fondo, y unirlo estiraria la caja hasta la pared.
        corte = max(umbral, punta * 0.5)
        candidatas = np.where(puntuaciones >= corte)[0]

        # Varias pasadas: una parte puede tocar a otra que a su vez toca a la principal,
        # como la bandeja que toca el brazo que toca la base.
        for _ in range(3):
            crecio = False
            for i in candidatas:
                caja = _xyxy(*(float(v) for v in cajas[:, int(i)]))
                if _se_tocan((x1, y1, x2, y2), caja):
                    nx1, ny1 = min(x1, caja[0]), min(y1, caja[1])
                    nx2, ny2 = max(x2, caja[2]), max(y2, caja[3])
                    if (nx1, ny1, nx2, ny2) != (x1, y1, x2, y2):
                        x1, y1, x2, y2 = nx1, ny1, nx2, ny2
                        crecio = True
            if not crecio:
                break

    return (x1 + x2) / 2, (y1 + y2) / 2, x2 - x1, y2 - y1, punta


def mejor_caja(salida: np.ndarray, umbral: float) -> tuple[float, float, float, float, float] | None:
    """
    La caja con mayor puntuacion de todo el tensor.

    La salida es `[1, 4 + n_clases, 8400]`, transpuesta: las 8400 propuestas van en la
    ultima dimension. Las cuatro primeras filas son cx, cy, ancho y alto **en pixeles** del
    lienzo de 640, no normalizados: asi lo exporto onnx2tf y asi lo lee la app
    (`coordsNormalized: false` en model_config.json).

    De que clase cree el modelo que es da igual: la clase la pone la carpeta. Aqui solo se
    usa la puntuacion maxima como medida de "aqui hay un aparato".
    """
    datos = salida[0]                      # [4 + n, 8400]
    cajas = datos[:4]                      # [4, 8400]
    puntuaciones = datos[4:].max(axis=0)   # [8400]

    i = int(puntuaciones.argmax())
    if float(puntuaciones[i]) < umbral:
        return None

    cx, cy, w, h = (float(v) for v in cajas[:, i])
    return cx, cy, w, h, float(puntuaciones[i])


def a_yolo(caja, escala, pad_x, pad_y, ancho, alto):
    """
    Del lienzo de 640 a coordenadas YOLO normalizadas de la imagen original.

    Se quita el relleno, se deshace la escala y se divide por el tamano real. Al final se
    recorta a 0..1, porque una caja que asome por el borde de la foto hace que Roboflow
    rechace el archivo entero.
    """
    cx, cy, w, h, _ = caja

    cx = (cx - pad_x) / escala
    cy = (cy - pad_y) / escala
    w = w / escala
    h = h / escala

    x1 = max(0.0, cx - w / 2)
    y1 = max(0.0, cy - h / 2)
    x2 = min(float(ancho), cx + w / 2)
    y2 = min(float(alto), cy + h / 2)

    if x2 <= x1 or y2 <= y1:
        return None

    # Se normaliza, se REDONDEA a los mismos 6 decimales con los que se va a escribir, y se
    # vuelve a recortar. Redondear despues de calcular el centro y el ancho hacia arriba deja
    # esquinas en 1.000001, y Roboflow las marca como "Trimmed Annotations" al subirlas.
    # Pasaba en 38 de las 727 etiquetas. No dana nada, pero el aviso hace dudar de si el
    # dataset esta bien.
    e1 = min(max(round(x1 / ancho, 6), 0.0), 1.0)
    f1 = min(max(round(y1 / alto, 6), 0.0), 1.0)
    e2 = min(max(round(x2 / ancho, 6), 0.0), 1.0)
    f2 = min(max(round(y2 / alto, 6), 0.0), 1.0)

    if e2 <= e1 or f2 <= f1:
        return None

    return (e1 + e2) / 2, (f1 + f2) / 2, e2 - e1, f2 - f1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fotos", required=True, help="Carpeta con una subcarpeta por clase")
    parser.add_argument("--salida", required=True, help="Donde escribir el dataset YOLOv8")
    parser.add_argument(
        "--modelo",
        default=str(Path(__file__).resolve().parent.parent / "app/src/main/assets/model.tflite"),
    )
    parser.add_argument(
        "--sin-fusion",
        action="store_true",
        help="No unir las partes que el modelo detecta por separado. Deja la caja de mayor "
             "puntuacion tal cual, que suele encuadrar solo una parte del aparato.",
    )
    parser.add_argument(
        "--umbral",
        type=float,
        default=0.10,
        help="Puntuacion minima. Bajo a proposito: solo se busca DONDE esta el aparato, "
             "no de que clase es, y una caja floja se corrige mas rapido que una que falta.",
    )
    args = parser.parse_args()

    fotos = Path(args.fotos)
    salida = Path(args.salida)
    if not fotos.is_dir():
        print(f"No existe {fotos}", file=sys.stderr)
        return 1

    clases = sorted(p.name for p in fotos.iterdir() if p.is_dir())
    if not clases:
        print(f"No hay subcarpetas de clase en {fotos}", file=sys.stderr)
        return 1
    print(f"{len(clases)} clases\n")

    from ai_edge_litert.interpreter import Interpreter

    interprete = Interpreter(model_path=args.modelo)
    interprete.allocate_tensors()
    entrada = interprete.get_input_details()[0]
    tensor_salida = interprete.get_output_details()[0]

    if list(entrada["shape"]) != [1, TAM, TAM, 3]:
        print(f"El modelo espera {entrada['shape']}, no [1,{TAM},{TAM},3]", file=sys.stderr)
        return 1

    dir_img = salida / "train" / "images"
    dir_lbl = salida / "train" / "labels"
    dir_img.mkdir(parents=True, exist_ok=True)
    dir_lbl.mkdir(parents=True, exist_ok=True)

    resumen: list[tuple[str, int, int]] = []
    sin_caja: list[str] = []

    for indice, clase in enumerate(clases):
        imagenes = sorted(
            p for p in (fotos / clase).iterdir()
            if p.suffix.lower() in {".jpg", ".jpeg", ".png"}
        )
        con_caja = 0

        for ruta in imagenes:
            try:
                imagen = Image.open(ruta).convert("RGB")
            except Exception as exc:
                print(f"  no se pudo abrir {ruta.name}: {exc}")
                continue

            lienzo, escala, pad_x, pad_y = letterbox(imagen)
            tensor = np.asarray(lienzo, dtype=np.float32)[None] / 255.0

            interprete.set_tensor(entrada["index"], tensor)
            interprete.invoke()
            bruto = interprete.get_tensor(tensor_salida["index"])

            caja = caja_del_aparato(bruto, args.umbral, fusionar=not args.sin_fusion)
            # Se conserva el nombre original, que ya empieza por el nombre de la clase
            # (`autoclave_gemmy_sturdy_sa_252f_001.jpg`), asi que en Roboflow las fotos del
            # mismo aparato quedan juntas al ordenar por nombre. Repetir la clase delante
            # ademas pasaba de los 260 caracteres de ruta que admite Windows.
            destino = dir_img / ruta.name
            shutil.copy2(ruta, destino)

            if caja is None:
                sin_caja.append(destino.name)
                # Una etiqueta VACIA es valida en YOLO y significa "sin objetos". Se escribe
                # igualmente para que Roboflow no tome la foto por no anotada y la descarte.
                (dir_lbl / f"{destino.stem}.txt").write_text("", encoding="utf-8")
                continue

            yolo = a_yolo(caja, escala, pad_x, pad_y, *imagen.size)
            if yolo is None:
                sin_caja.append(destino.name)
                (dir_lbl / f"{destino.stem}.txt").write_text("", encoding="utf-8")
                continue

            cx, cy, w, h = yolo
            (dir_lbl / f"{destino.stem}.txt").write_text(
                f"{indice} {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}\n", encoding="utf-8"
            )
            con_caja += 1

        resumen.append((clase, len(imagenes), con_caja))
        print(f"  {clase:<52} {con_caja:>3}/{len(imagenes):<3}")

    # Todas las fotos van a `train/` porque el reparto lo hace Roboflow al crear la version,
    # que es donde ademas se aplican sus aumentos y su preprocesado.
    #
    # OJO al subir: Roboflow propone "Use Existing Values" y, como aqui todo esta en train,
    # eso deja 100 % entrenamiento y 0 % validacion. Hay que cambiarlo a 70 / 15 / 15.
    (salida / "data.yaml").write_text(
        "train: train/images\n"
        "val: train/images\n"
        f"nc: {len(clases)}\n"
        f"names: {clases}\n",
        encoding="utf-8",
    )

    if sin_caja:
        (salida / "_sin_caja.txt").write_text("\n".join(sin_caja) + "\n", encoding="utf-8")

    total = sum(n for _, n, _ in resumen)
    hechas = sum(c for _, _, c in resumen)
    print("\n" + "=" * 64)
    print(f"fotos                {total}")
    print(f"con caja puesta      {hechas}  ({hechas * 100 // max(total, 1)} %)")
    print(f"sin caja, a mano     {total - hechas}")
    print("=" * 64)

    flojas = [(c, n, k) for c, n, k in resumen if n and k * 100 // n < 70]
    if flojas:
        print("\nClases donde el modelo v1 fallo mas, revisalas primero:")
        for c, n, k in sorted(flojas, key=lambda x: x[2] * 100 // max(x[1], 1)):
            print(f"  {k * 100 // n:>3} %   {c}  ({k}/{n})")

    print(f"\nSalida: {salida}")
    print("Comprime esa carpeta y subela a Roboflow: reconoce el formato YOLOv8.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
