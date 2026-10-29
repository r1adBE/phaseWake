#!/usr/bin/env python3
"""Prüft die Höchstlänge von Kotlin- und Swift-Dateien (docs/ENTWICKLUNG.md, Abschnitt 2).

Grenze: 400 Zeilen. Altlasten stehen in .github/quality/line-limit-baseline.txt («pfad zeilen») und dürfen nur
schrumpfen. Mit --update wird die Liste auf den aktuellen Stand verkürzt (nie verlängert).
"""
import sys
from pathlib import Path

LIMIT = 400
ROOT = Path(__file__).resolve().parents[2]
BASELINE = ROOT / ".github/quality/line-limit-baseline.txt"
SOURCES = ["android", "ios"]
SKIP = ("/build/", "/.build/", "/screenshotTest/")


def files():
    for base in SOURCES:
        for ext in ("*.kt", "*.swift"):
            for f in (ROOT / base).rglob(ext):
                rel = f.relative_to(ROOT).as_posix()
                if not any(s in "/" + rel for s in SKIP):
                    yield rel, sum(1 for _ in f.open(encoding="utf-8"))


def load_baseline():
    if not BASELINE.exists():
        return {}
    out = {}
    for line in BASELINE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            path, count = line.rsplit(" ", 1)
            out[path] = int(count)
    return out


def main():
    baseline = load_baseline()
    current = dict(files())
    errors, notes = [], []
    for path, count in sorted(current.items()):
        allowed = baseline.get(path, LIMIT)
        if count > max(allowed, LIMIT):
            errors.append(f"{path}: {count} Zeilen (erlaubt {max(allowed, LIMIT)})")
    for path, count in baseline.items():
        if current.get(path, 0) <= LIMIT:
            notes.append(f"{path}: hält die Grenze jetzt – aus der Altlasten-Liste streichen")
    if "--update" in sys.argv:
        keep = {p: min(c, current[p]) for p, c in baseline.items() if current.get(p, 0) > LIMIT}
        BASELINE.write_text("# Altlasten: Datei Zeilen (darf nur schrumpfen, siehe docs/ENTWICKLUNG.md)\n"
                            + "".join(f"{p} {c}\n" for p, c in sorted(keep.items())), encoding="utf-8")
    for n in notes:
        print("Hinweis:", n)
    for e in errors:
        print("::error::" + e if "--ci" in sys.argv else "Fehler: " + e)
    print(f"{len(current)} Dateien, {len(baseline)} Altlasten, {len(errors)} Verstösse")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
