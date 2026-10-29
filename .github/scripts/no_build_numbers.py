#!/usr/bin/env python3
"""Keine Build-Nummern und keine TODO-Marken im Produktivcode (docs/ENTWICKLUNG.md, Abschnitte 2 und 3).

Durchsucht android/*/src/main und die Swift-Quellen der Apps und des Kerns (ohne Tests). Obergrenze: 0.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RULES = [
    (re.compile(r"\b(versionCode|longVersionCode|VERSION_CODE\b(?!S))"), "Build-Nummer (versionCode) im Code"),
    (re.compile(r"CFBundleVersion\b"), "Build-Nummer (CFBundleVersion) im Code"),
    (re.compile(r"PHASEWAKE_BUILD"), "Build-Nummer aus dem Build im Code"),
    (re.compile(r"\"[^\"]*\b[Bb]uild\s*#?\d+[^\"]*\""), "Build-Nummer als Text"),
    (re.compile(r"\b(TODO|FIXME|XXX)\b"), "TODO/FIXME/XXX im Produktivcode"),
]


def sources():
    for f in (ROOT / "android").glob("*/src/main/**/*.kt"):
        yield f
    for f in (ROOT / "ios").rglob("*.swift"):
        rel = f.relative_to(ROOT).as_posix()
        if "/Tests/" not in rel and "/.build/" not in rel:
            yield f


def main():
    found = []
    for f in sources():
        for no, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            for rx, msg in RULES:
                if rx.search(line):
                    found.append(f"{f.relative_to(ROOT).as_posix()}:{no}: {msg}")
    for item in found:
        print("::error::" + item if "--ci" in sys.argv else item)
    print(f"{len(found)} Funde (Obergrenze 0)")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
