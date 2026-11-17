#!/usr/bin/env python3
"""Mindestabdeckung (Zeilen) je Datei aus einem Xcode-Ergebnisbündel.

Aufruf: xccov_check.py NAME BERICHT.json [--exclude TYP …] DATEI=MINDEST_PROZENT [DATEI=MINDEST_PROZENT …]
BERICHT.json: Ausgabe von `xcrun xccov view --report --json <bündel>.xcresult`. DATEI ist der Pfad ab dem Repo
(z. B. ios/PhaseWake/BackupAlarm.swift) und muss im Bericht stehen; sonst Fehler (umbenannt, nicht gebaut oder
ohne -enableCodeCoverage), damit die Prüfung nie still durchlässt.
--exclude (mehrfach möglich): Funktionen, deren Name den Typ enthält, zählen nicht (z. B. eine SwiftUI-Ansicht in
derselben Datei, die Unit-Tests nicht zeichnen).
Je Datei eine eigene Schwelle statt einer Summe: Fallen die Tests einer kleinen Datei weg, fiele das in der Summe
der grossen Dateien nicht auf.
"""
import json
import sys


def entries(report):
    """Alle Dateien aller Ziele im Bericht."""
    for target in report.get("targets", []):
        yield from target.get("files", [])


def measure(entry, excluded):
    """(abgedeckt, ausführbar) einer Datei, ohne die Funktionen der ausgeschlossenen Typen."""
    covered, total = entry.get("coveredLines", 0), entry.get("executableLines", 0)
    for function in entry.get("functions", []):
        if any(name in function.get("name", "") for name in excluded):
            covered -= function.get("coveredLines", 0)
            total -= function.get("executableLines", 0)
    return max(covered, 0), max(total, 0)


def find(report, path):
    """Eintrag zur Datei; steht sie in mehreren Zielen, der mit den meisten ausführbaren Zeilen."""
    hits = [e for e in entries(report) if e.get("path", "").endswith("/" + path)]
    return max(hits, key=lambda e: e.get("executableLines", 0)) if hits else None


def main():
    name, report_path, rest = sys.argv[1], sys.argv[2], sys.argv[3:]
    excluded, wanted = [], []
    while rest:
        arg = rest.pop(0)
        if arg == "--exclude":
            excluded.append(rest.pop(0))
        else:
            path, _, minimum = arg.rpartition("=")
            wanted.append((path, float(minimum)))
    with open(report_path) as f:
        report = json.load(f)
    failed = 0
    for path, minimum in wanted:
        entry = find(report, path)
        covered, total = measure(entry, excluded) if entry else (0, 0)
        if total == 0:
            print(f"::error title=Abdeckung::{name}: keine Zeilen für {path} im Bericht "
                  "(Datei umbenannt oder ohne -enableCodeCoverage gebaut?)")
            failed += 1
            continue
        pct = 100.0 * covered / total
        ok = pct >= minimum
        failed += 0 if ok else 1
        print(("::notice" if ok else "::error") + f" title=Abdeckung::{name}: {path} {pct:.1f} % Zeilen abgedeckt "
              f"({covered}/{total}), Mindestwert {minimum:.0f} %")
        if not ok:
            # Die Funktionen mit den meisten nicht abgedeckten Zeilen, damit klar ist, wo Tests fehlen.
            rows = [(fn.get("name", "?"), fn.get("coveredLines", 0), fn.get("executableLines", 0))
                    for fn in entry.get("functions", []) if not any(x in fn.get("name", "") for x in excluded)]
            for fn, c, t in sorted(rows, key=lambda r: r[1] - r[2])[:6]:
                print(f"  {fn}: {c}/{t} Zeilen")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
