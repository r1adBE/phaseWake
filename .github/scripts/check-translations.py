#!/usr/bin/env python3
"""Prüft alle Übersetzungen gegen Englisch: gleiche Texte, gleiche Platzhalter, gültiges XML.

Aufruf: check-translations.py <res-Ordner> [...]; Rückgabe 1 bei Fehlern.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sd]|%%")


def strings(path: Path) -> dict[str, str]:
    root = ET.parse(path).getroot()
    return {e.get("name"): "".join(e.itertext()) for e in root.iter("string") if e.get("translatable") != "false"}


def main() -> int:
    errors = []
    for res in map(Path, sys.argv[1:]):
        base = strings(res / "values" / "strings.xml")
        for file in sorted(res.glob("values-*/strings.xml")):
            lang = file.parent.name
            try:
                other = strings(file)
            except ET.ParseError as e:
                errors.append(f"{file}: kein gültiges XML ({e})")
                continue
            for name in base.keys() - other.keys():
                errors.append(f"{lang}: fehlt {name}")
            for name in other.keys() - base.keys():
                errors.append(f"{lang}: unbekannt {name}")
            for name in base.keys() & other.keys():
                if sorted(PLACEHOLDER.findall(base[name])) != sorted(PLACEHOLDER.findall(other[name])):
                    errors.append(f"{lang}: Platzhalter in {name}: {other[name]!r}")
                if re.search(r"(?<!\\)'", other[name]) and not other[name].startswith('"'):
                    errors.append(f"{lang}: unmaskiertes ' in {name}")
            print(f"{res.parent.name}/{lang}: {len(other)} Texte")
    for e in errors:
        print("FEHLER", e)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
