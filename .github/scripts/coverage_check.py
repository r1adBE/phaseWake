#!/usr/bin/env python3
"""Prüft Mindestabdeckung (Zeilen) aus JaCoCo-XML oder llvm-cov-JSON (docs/ENTWICKLUNG.md, Abschnitt 4).

Aufruf: coverage_check.py NAME MINDEST_PROZENT [--package PAKET …] DATEI [DATEI …]
JaCoCo: android/*/build/reports/jacoco/**.xml; Swift: Ausgabe von `llvm-cov export -summary-only` (JSON).
Bei Swift zählen nur Dateien unter Sources/ (keine Tests, keine Abhängigkeiten).
--package (nur JaCoCo, mehrfach möglich): nur dieses Paket, z. B. com/phasewake/app/ring (ohne Unterpakete).
"""
import json
import sys
import xml.etree.ElementTree as ET


def line_counter(node):
    for c in node.findall("counter"):
        if c.get("type") == "LINE":
            return int(c.get("covered")), int(c.get("covered")) + int(c.get("missed"))
    return 0, 0


def jacoco(path, packages=()):
    root = ET.parse(path).getroot()
    if not packages:
        return line_counter(root)
    cov = tot = 0
    for pkg in root.findall("package"):
        if pkg.get("name") in packages:
            c, t = line_counter(pkg)
            cov += c
            tot += t
    return cov, tot


def jacoco_files(path, packages=()):
    """(Datei, abgedeckt, gesamt) je Quelldatei, für die Liste bei zu wenig Abdeckung."""
    root = ET.parse(path).getroot()
    out = []
    for pkg in root.findall("package"):
        if packages and pkg.get("name") not in packages:
            continue
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
    name, minimum, rest = sys.argv[1], float(sys.argv[2]), sys.argv[3:]
    packages, files = [], []
    while rest:
        arg = rest.pop(0)
        if arg == "--package":
            packages.append(rest.pop(0).strip("/"))
        else:
            files.append(arg)
    cov = tot = 0
    for f in files:
        c, t = llvm(f) if f.endswith(".json") else jacoco(f, packages)
        cov += c
        tot += t
    if packages and tot == 0:
        # Paket umbenannt oder Bericht leer: nicht still als bestanden werten.
        print(f"::error title=Abdeckung::{name}: keine Zeilen für {', '.join(packages)} gefunden")
        return 1
    pct = 100.0 * cov / tot if tot else 0.0
    msg = f"{name}: {pct:.1f} % Zeilen abgedeckt ({cov}/{tot}), Mindestwert {minimum:.0f} %"
    ok = pct >= minimum
    print(("::notice title=Abdeckung::" if ok else "::error title=Abdeckung::") + msg)
    if not ok:
        # Die Dateien mit den meisten nicht abgedeckten Zeilen, damit klar ist, wo Tests fehlen.
        rows = [r for f in files if not f.endswith(".json") for r in jacoco_files(f, packages)]
        for name_, c, t in sorted(rows, key=lambda r: r[1] - r[2])[:8]:
            print(f"  {name_}: {c}/{t} Zeilen")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
