#!/usr/bin/env python3
"""Jede öffentliche Deklaration im Kern hat einen Doku-Kommentar (docs/ENTWICKLUNG.md, Abschnitt 5).

Kotlin (android/core/src/main): Klassen, Objekte, Schnittstellen, Aufzählungen, Funktionen und Eigenschaften ohne
`private`/`internal`, auf Datei- oder Klassenebene (Einrückung höchstens 4), brauchen davor einen `/** … */`-Block.
Swift (ios/PhaseWakeCore/Sources): Deklarationen mit `public` brauchen davor `///`.
Ausnahmen: `override`, Aufzählungswerte, `data class`-Parameter, Getter/Setter. Obergrenze: 0 Funde.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KT_DECL = re.compile(r"^( {0,4})(?:public |open |abstract |sealed |data |enum |value |inline |const |lateinit |suspend |operator |infix |annotation |fun interface )*"
                     r"(class|object|interface|fun|val|var)\b")
KT_SKIP = re.compile(r"\b(private|internal|override|protected)\b")
SW_DECL = re.compile(r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*public\s+(?:static |final |mutating |nonisolated |convenience |init|let |var |func |struct |enum |class |protocol |typealias |extension )")


def preceded_by_doc(lines, i, swift):
    j = i - 1
    while j >= 0 and (lines[j].strip().startswith("@") or lines[j].strip() == ""):
        if lines[j].strip() == "":
            return False
        j -= 1
    if j < 0:
        return False
    prev = lines[j].strip()
    return prev.startswith("///") if swift else (prev.endswith("*/") or prev.startswith("/**"))


def check_kotlin(f, out):
    lines = f.read_text(encoding="utf-8").splitlines()
    depth = 0
    for i, line in enumerate(lines):
        m = KT_DECL.match(line)
        if m and not KT_SKIP.search(line.split("=")[0]) and depth <= 1 and not line.lstrip().startswith(("fun interface",)):
            if not preceded_by_doc(lines, i, swift=False):
                out.append(f"{f.relative_to(ROOT).as_posix()}:{i + 1}: {line.strip()[:80]}")
        depth += line.count("{") - line.count("}")
        depth = max(depth, 0)


def check_swift(f, out):
    lines = f.read_text(encoding="utf-8").splitlines()
    for i, line in enumerate(lines):
        if SW_DECL.match(line) and "public extension" not in line and not line.strip().startswith("public extension"):
            if not preceded_by_doc(lines, i, swift=True):
                out.append(f"{f.relative_to(ROOT).as_posix()}:{i + 1}: {line.strip()[:80]}")


def main():
    out = []
    for f in sorted((ROOT / "android/core/src/main").rglob("*.kt")):
        check_kotlin(f, out)
    for f in sorted((ROOT / "ios/PhaseWakeCore/Sources").rglob("*.swift")):
        check_swift(f, out)
    # Altlast beim Einführen der Regel: darf nur sinken (Zahl in .github/quality/doc-baseline.txt).
    base_file = ROOT / ".github/quality/doc-baseline.txt"
    allowed = int(base_file.read_text().split()[0]) if base_file.exists() else 0
    if "--update" in sys.argv:
        base_file.write_text(f"{min(len(out), allowed) if base_file.exists() else len(out)}\n")
    if len(out) > allowed:
        for o in out:
            print("::error::Doku fehlt: " + o if "--ci" in sys.argv else o)
    print(f"{len(out)} öffentliche Deklarationen ohne Doku-Kommentar (erlaubt {allowed}, Ziel 0)")
    return 1 if len(out) > allowed else 0


if __name__ == "__main__":
    sys.exit(main())
