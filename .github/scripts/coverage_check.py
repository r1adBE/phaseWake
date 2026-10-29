#!/usr/bin/env python3
"""Prüft Mindestabdeckung (Zeilen) aus JaCoCo-XML oder llvm-cov-JSON (docs/ENTWICKLUNG.md, Abschnitt 4).

Aufruf: coverage_check.py NAME MINDEST_PROZENT DATEI [DATEI …]
JaCoCo: android/*/build/reports/jacoco/**.xml; Swift: Ausgabe von `llvm-cov export -summary-only` (JSON).
Bei Swift zählen nur Dateien unter Sources/ (keine Tests, keine Abhängigkeiten).
"""
import json
import sys
import xml.etree.ElementTree as ET


def jacoco(path):
    root = ET.parse(path).getroot()
    for c in root.findall("counter"):
        if c.get("type") == "LINE":
            return int(c.get("covered")), int(c.get("covered")) + int(c.get("missed"))
    return 0, 0


def jacoco_files(path):
    """(Datei, abgedeckt, gesamt) je Quelldatei, für die Liste bei zu wenig Abdeckung."""
    root = ET.parse(path).getroot()
    out = []
    for pkg in root.findall("package"):
        for sf in pkg.findall("sourcefile"):
            for c in sf.findall("counter"):
                if c.get("type") == "LINE":
                    cov, miss = int(c.get("covered")), int(c.get("missed"))
                    out.append((pkg.get("name") + "/" + sf.get("name"), cov, cov + miss))
    return out


def llvm(path):
    data = json.load(open(path))
    cov = tot = 0
    for f in data["data"][0]["files"]:
        if "/Sources/" in f["filename"]:
            s = f["summary"]["lines"]
            cov += s["covered"]
            tot += s["count"]
    return cov, tot


def main():
    name, minimum, files = sys.argv[1], float(sys.argv[2]), sys.argv[3:]
    cov = tot = 0
    for f in files:
        c, t = llvm(f) if f.endswith(".json") else jacoco(f)
        cov += c
        tot += t
    pct = 100.0 * cov / tot if tot else 0.0
    msg = f"{name}: {pct:.1f} % Zeilen abgedeckt ({cov}/{tot}), Mindestwert {minimum:.0f} %"
    ok = pct >= minimum
    print(("::notice title=Abdeckung::" if ok else "::error title=Abdeckung::") + msg)
    if not ok:
        # Die Dateien mit den meisten nicht abgedeckten Zeilen, damit klar ist, wo Tests fehlen.
        rows = [r for f in files if not f.endswith(".json") for r in jacoco_files(f)]
        for name_, c, t in sorted(rows, key=lambda r: r[1] - r[2])[:8]:
            print(f"  {name_}: {c}/{t} Zeilen")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
