"""Volca las fichas del backend RAG en `assets/catalog.json`, el respaldo sin conexion.

### Por que hace falta

`catalog.json` tenia **dos** fichas de las 54 clases. La app resuelve una clase sin ficha con
"Ficha no disponible", asi que no se rompia nada, pero el estudiante que detectaba una
autoclave y no tenia el backend a mano no veia ni el procedimiento ni el equipo de
proteccion.

Y ese caso no es raro, es el normal: el backend corre en el portatil de Mario, con una IP de
red local. Un telefono con datos moviles o en otra red **no puede alcanzarlo**. Con las 54
fichas dentro del APK, la deteccion y la ficha tecnica funcionan en cualquier sitio y sin
conexion; lo unico que sigue necesitando servidor es el chat.

### Que NO resuelve

El chat. Preguntarle a la app sigue exigiendo alcanzar el backend, porque la regla 5 de
CLAUDE.md prohibe que la app hable con el modelo directamente: iria la clave de la API dentro
del APK, y un APK se descompila en minutos.

### Uso

    python tools/generar_catalogo.py

Lee `../labscan-rag/storage/equipment_cards.json` y escribe `app/src/main/assets/catalog.json`
descartando las fichas que no correspondan a ninguna linea de `labels.txt`.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
ASSETS = RAIZ / "app/src/main/assets"

# Campos de EquipmentDto, en su orden. Se filtra a estos a proposito: `CatalogJsonTest`
# deserializa SIN ignoreUnknownKeys, asi que un campo de mas que el backend anada algun dia
# rompe la prueba, que es justo lo que se quiere que pase para enterarse.
CAMPOS = (
    "classId",
    "displayName",
    "shortDescription",
    "function",
    "components",
    "basicProcedure",
    "ppe",
    "risks",
    "relatedPractices",
    "sources",
)

# Los que la prueba exige no vacios. Una ficha sin procedimiento o sin riesgos no sirve de
# respaldo: es peor que no tenerla, porque parece completa.
OBLIGATORIOS = ("classId", "displayName", "shortDescription", "basicProcedure", "risks", "sources")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--fichas",
        default=str(RAIZ.parent.parent / "PythonProjects/labscan-rag/storage/equipment_cards.json"),
    )
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    origen = Path(args.fichas).expanduser()
    if not origen.is_file():
        print(f"No existe {origen}", file=sys.stderr)
        print("Genera las fichas antes: python -m app.cards --rebuild", file=sys.stderr)
        return 1

    crudo = json.loads(origen.read_text(encoding="utf-8"))
    fichas = list(crudo.values()) if isinstance(crudo, dict) else crudo

    clases = [c.strip() for c in (ASSETS / "labels.txt").read_text(encoding="utf-8").splitlines() if c.strip()]
    print(f"{len(fichas)} fichas en el backend, {len(clases)} clases en labels.txt")

    salida, huerfanas, incompletas = [], [], []
    for ficha in fichas:
        clase = ficha.get("classId", "")
        if clase not in clases:
            huerfanas.append(clase)
            continue
        faltan = [c for c in OBLIGATORIOS if not ficha.get(c)]
        if faltan:
            incompletas.append((clase, faltan))
            continue
        salida.append({c: ficha.get(c, [] if c in ("components", "basicProcedure", "ppe", "risks", "relatedPractices", "sources") else "") for c in CAMPOS})

    # En el orden de labels.txt, para que el archivo se lea igual que el modelo emite.
    salida.sort(key=lambda f: clases.index(f["classId"]))

    sin_ficha = [c for c in clases if c not in {f["classId"] for f in salida}]

    print(f"  se escriben          {len(salida)}")
    if huerfanas:
        print(f"  descartadas por no estar en labels.txt: {len(huerfanas)}")
        for h in huerfanas:
            print(f"     {h}")
    if incompletas:
        print(f"  descartadas por incompletas: {len(incompletas)}")
        for c, f in incompletas:
            print(f"     {c}  sin {', '.join(f)}")
    if sin_ficha:
        print(f"  clases que quedan SIN ficha: {len(sin_ficha)}")
        for c in sin_ficha:
            print(f"     {c}")

    if args.dry_run:
        print("\n(dry-run: no se escribio nada)")
        return 0

    destino = ASSETS / "catalog.json"
    destino.write_text(
        json.dumps(salida, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"\n{destino.relative_to(RAIZ)}  {destino.stat().st_size // 1024} KB")
    print("Siguiente: ./gradlew test")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
