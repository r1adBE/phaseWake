#!/usr/bin/env python3
"""Prüft alle Übersetzungen gegen Englisch: gleiche Texte, gleiche Platzhalter, gültiges XML.

Aufruf: check-translations.py <res-Ordner> [...]; Rückgabe 1 bei Fehlern.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sd]|%%")


# Pluralformen je Sprache (CLDR, ganze Zahlen); «other» ist immer Pflicht.
PLURAL_CATEGORIES = {
    "ar": {"zero", "one", "two", "few", "many", "other"},
    "cs": {"one", "few", "other"}, "pl": {"one", "few", "many", "other"},
    "ru": {"one", "few", "many", "other"}, "uk": {"one", "few", "many", "other"},
    "ro": {"one", "few", "other"}, "iw": {"one", "two", "other"},
    "fr": {"one", "many", "other"}, "es": {"one", "many", "other"}, "it": {"one", "many", "other"},
    "pt": {"one", "many", "other"},
    "ja": {"other"}, "ko": {"other"}, "zh": {"other"}, "th": {"other"}, "vi": {"other"},
    "in": {"other"}, "ms": {"other"},
}


def strings(path: Path) -> dict[str, str]:
    root = ET.parse(path).getroot()
    return {e.get("name"): "".join(e.itertext()) for e in root.iter("string") if e.get("translatable") != "false"}


def plurals(path: Path) -> dict[str, dict[str, str]]:
    root = ET.parse(path).getroot()
    return {p.get("name"): {i.get("quantity"): "".join(i.itertext()) for i in p.iter("item")} for p in root.iter("plurals")}


def lang_of(folder: str) -> str:
    q = folder[len("values-"):]
    return q.split("+")[1] if q.startswith("b+") else q.split("-")[0]


def check_plurals(lang: str, base: dict[str, dict[str, str]], other: dict[str, dict[str, str]], errors: list[str]) -> None:
    for name in base.keys() - other.keys():
        errors.append(f"{lang}: fehlt Plural {name}")
    for name in base.keys() & other.keys():
        items = other[name]
        if "other" not in items:
            errors.append(f"{lang}: Plural {name} ohne «other»")
            continue
        wanted = PLURAL_CATEGORIES.get(lang_of(lang), {"one", "other"})
        # «many» gilt in fr/es/it/pt nur für Millionen: freiwillig.
        if lang_of(lang) in ("fr", "es", "it", "pt"):
            wanted = wanted - {"many"}
        for missing in sorted(wanted - items.keys()):
            errors.append(f"{lang}: Plural {name} ohne «{missing}»")
        allowed = set(PLACEHOLDER.findall(base[name]["other"]))
        for quantity, text in items.items():
            if not set(PLACEHOLDER.findall(text)) <= allowed:
                errors.append(f"{lang}: Platzhalter in Plural {name}/{quantity}: {text!r}")
            # Die Zahl selbst muss in «other» stehen; in «one» darf sie fehlen («eine Nacht»).
            if quantity == "other" and sorted(PLACEHOLDER.findall(text)) != sorted(PLACEHOLDER.findall(base[name]["other"])):
                errors.append(f"{lang}: Platzhalter in Plural {name}/other: {text!r}")


def main() -> int:
    errors = []
    for res in map(Path, sys.argv[1:]):
        base = strings(res / "values" / "strings.xml")
        base_plurals = plurals(res / "values" / "strings.xml")
        for file in sorted(res.glob("values-*/strings.xml")):
            lang = file.parent.name
            try:
                other = strings(file)
                check_plurals(lang, base_plurals, plurals(file), errors)
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
