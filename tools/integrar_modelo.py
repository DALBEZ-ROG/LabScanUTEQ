"""Cambia el modelo, las etiquetas y el catalogo de una sola vez, verificando antes.

### Por que existe

Los tres archivos de `app/src/main/assets/` cambian **a la vez o ninguno**:

    model.tflite    labels.txt    catalog.json

`YoloTfliteDetector` compara las lineas de `labels.txt` con el ancho del tensor de salida y
lanza `ModelMismatchException` si no cuadran. Entonces `DetectorFactory` cae al
`StubDetector` y la app se queda dibujando cuadros falsos con un aviso. Cambiar uno solo de
los tres archivos rompe la app sin que el fallo se parezca a lo que lo causo.

Ademas comprueba lo que costo la primera integracion: que el tensor de entrada sea
**NHWC** `[1,640,640,3]` y no NCHW `[1,3,640,640]`. Con NCHW el modelo carga, no detecta
nada, y no hay ningun error que lo diga.

### Uso

    python tools/integrar_modelo.py \\
        --tflite ~/Downloads/best_float32.tflite \\
        --labels ~/Downloads/labels.txt

`--labels` es opcional: sin el se usa `docs/labels_v2.txt`. Con `--dry-run` solo comprueba.

Despues:  ./gradlew test  &&  ./gradlew installDebug
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
ASSETS = RAIZ / "app/src/main/assets"

# Equivalencias entre las clases del modelo viejo de 50 y las del dataset v2 de 54.
#
# Solo hacen falta las de `catalog.json`, que es el respaldo sin conexion y tiene dos fichas.
# Sin esto, `CatalogJsonTest` falla con "fichas que no corresponden a ninguna clase de
# labels.txt", que es la prueba haciendo bien su trabajo: una ficha huerfana no se mostraria
# nunca y nadie se enteraria.
EQUIVALENCIAS = {
    "microscopio_binocular": "microscopio_compuesto_binocular_amscope_b120",
    "camara_electroforesis": "camara_de_electroforesis_owl_easycast_b2",
}


def leer_tensores(ruta_tflite: Path):
    """Forma y tipo de la entrada y la salida, sin depender de tensorflow."""
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:
        print(
            "Falta ai_edge_litert, que es lo que permite abrir el .tflite para comprobarlo.\n"
            "  pip install ai_edge_litert",
            file=sys.stderr,
        )
        return None

    interprete = Interpreter(model_path=str(ruta_tflite))
    interprete.allocate_tensors()
    return interprete.get_input_details()[0], interprete.get_output_details()[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tflite", required=True, help="El modelo exportado desde Colab")
    parser.add_argument(
        "--labels",
        default=str(RAIZ / "docs/labels_v2.txt"),
        help="Una clase por linea, en el MISMO orden que data.yaml de Roboflow",
    )
    parser.add_argument("--dry-run", action="store_true", help="Solo comprueba, no escribe")
    args = parser.parse_args()

    tflite = Path(args.tflite).expanduser()
    labels_origen = Path(args.labels).expanduser()

    if not tflite.is_file():
        print(f"No existe {tflite}", file=sys.stderr)
        return 1
    if not labels_origen.is_file():
        print(f"No existe {labels_origen}", file=sys.stderr)
        return 1

    clases = [c.strip() for c in labels_origen.read_text(encoding="utf-8-sig").splitlines() if c.strip()]
    print(f"{len(clases)} clases en {labels_origen.name}")

    repetidas = {c for c in clases if clases.count(c) > 1}
    if repetidas:
        print(f"HAY CLASES REPETIDAS: {sorted(repetidas)}", file=sys.stderr)
        return 1

    tensores = leer_tensores(tflite)
    if tensores is None:
        return 1
    entrada, salida = tensores

    # int() por elemento: numpy los imprime como np.int32(640) y el aviso se vuelve ilegible
    # justo cuando mas falta hace leerlo con calma.
    forma_entrada = [int(v) for v in entrada["shape"]]
    forma_salida = [int(v) for v in salida["shape"]]
    print(f"entrada  {forma_entrada}  {entrada['dtype'].__name__}")
    print(f"salida   {forma_salida}  {salida['dtype'].__name__}")

    problemas: list[str] = []

    # --- NHWC, la comprobacion que costo la primera integracion --------------------------
    if len(forma_entrada) != 4 or forma_entrada[3] != 3:
        if len(forma_entrada) == 4 and forma_entrada[1] == 3:
            problemas.append(
                f"La entrada es NCHW {forma_entrada} y la app necesita NHWC [1,N,N,3].\n"
                "        Se exporto con model.export(format='tflite'), que produce NCHW.\n"
                "        Hay que rehacerlo por ONNX y onnx2tf: ver docs/ENTRENAMIENTO_COLAB.md."
            )
        else:
            problemas.append(f"Forma de entrada inesperada: {forma_entrada}")

    tam_entrada = forma_entrada[1] if len(forma_entrada) == 4 else 0
    if len(forma_entrada) == 4 and forma_entrada[1] != forma_entrada[2]:
        problemas.append(f"La entrada no es cuadrada: {forma_entrada}")

    # --- El numero de clases del tensor tiene que cuadrar con labels.txt -----------------
    #
    # La salida es [1, 4 + n_clases, n_propuestas] en disposicion TRANSPOSED. Se toma la
    # dimension menor de las dos ultimas, porque las propuestas son siempre miles y las
    # clases decenas.
    if len(forma_salida) == 3:
        canal = min(forma_salida[1], forma_salida[2])
        clases_tensor = canal - 4
        if clases_tensor != len(clases):
            problemas.append(
                f"El tensor declara {clases_tensor} clases y {labels_origen.name} tiene "
                f"{len(clases)}.\n"
                "        El detector lanza ModelMismatchException y la app cae al StubDetector."
            )
    else:
        problemas.append(f"Forma de salida inesperada: {forma_salida}")

    if problemas:
        print("\nNO se integra:\n")
        for p in problemas:
            print(f"  - {p}")
        return 1

    cuantizado = entrada["dtype"].__name__ in {"int8", "uint8"}
    print(f"cuantizado {cuantizado}")

    if args.dry_run:
        print("\nTodo cuadra. (dry-run: no se escribio nada)")
        return 0

    # --- Escritura -----------------------------------------------------------------------
    shutil.copy2(tflite, ASSETS / "model.tflite")
    (ASSETS / "labels.txt").write_text("\n".join(clases) + "\n", encoding="utf-8")

    config_path = ASSETS / "model_config.json"
    config = json.loads(config_path.read_text(encoding="utf-8"))
    config["inputSize"] = tam_entrada
    # Informativo: el detector elige el camino segun el tipo REAL del tensor. Se pone bien
    # igualmente para que la configuracion no diga una cosa y el modelo sea otra.
    config["quantized"] = cuantizado
    config_path.write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    catalogo_path = ASSETS / "catalog.json"
    catalogo = json.loads(catalogo_path.read_text(encoding="utf-8"))
    renombradas, huerfanas = [], []
    for ficha in catalogo:
        viejo = ficha["classId"]
        nuevo = EQUIVALENCIAS.get(viejo, viejo)
        if nuevo != viejo:
            ficha["classId"] = nuevo
            renombradas.append(f"{viejo} -> {nuevo}")
        if ficha["classId"] not in clases:
            huerfanas.append(ficha["classId"])
    catalogo_path.write_text(
        json.dumps(catalogo, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    print(f"\nmodel.tflite      {tflite.stat().st_size // 1024} KB")
    print(f"labels.txt        {len(clases)} clases")
    print(f"model_config.json inputSize={tam_entrada} quantized={cuantizado}")
    for r in renombradas:
        print(f"catalog.json      {r}")
    if huerfanas:
        print(f"\nAVISO: fichas que no corresponden a ninguna clase: {huerfanas}")
        print("       CatalogJsonTest va a fallar. Agregalas a EQUIVALENCIAS de este script.")

    print("\nSiguiente: ./gradlew test && ./gradlew installDebug")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
