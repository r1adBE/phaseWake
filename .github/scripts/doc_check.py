#!/usr/bin/env python3
"""Jede öffentliche Deklaration im Kern hat einen Doku-Kommentar (docs/ENTWICKLUNG.md, Abschnitt 5).

Kotlin (android/core/src/main): Klassen, Objekte, Schnittstellen, Aufzählungen, Funktionen und Eigenschaften ohne
`private`/`internal`/`protected`, auf Dateiebene und in öffentlichen Klassen, Objekten und `companion object` (jede
Tiefe), brauchen davor einen KDoc-Block `/** … */`; ein gewöhnlicher `/* … */`-Kommentar zählt nicht. Annotationen
dürfen davor oder auf derselben Zeile stehen (`@JvmStatic fun`).
Swift (ios/PhaseWakeCore/Sources): Deklarationen mit `public` oder `open` (auch `public private(set)`) und die
Mitglieder einer `public extension` ohne eigene Sichtbarkeit brauchen davor `///` (oder `/** … */`).
Ausnahmen: `override`, Aufzählungswerte, `data class`-Parameter, Getter/Setter, `companion object` und
`public extension` selbst (ihre Mitglieder zählen), `fun interface`. Obergrenze: 0 Funde.
Selbsttest: `test_doc_check.py`.
"""
import re
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from code_limits import strip_code  # noqa: E402  (Zeichenketten und Kommentare entfernen wie dort)

ROOT = Path(__file__).resolve().parents[2]
_KT_ANNOT = r"(?:@[\w.:]+(?:\([^)]*\))?\s+)*"
_KT_MODS = (r"(?:(?:public|private|internal|protected|open|final|abstract|sealed|data|enum|value|inline|const|"
            r"lateinit|suspend|operator|infix|annotation|tailrec|external|override|inner|actual|expect|"
            r"fun(?= interface))\s+)*")
KT_DECL = re.compile(r"^\s*" + _KT_ANNOT + _KT_MODS + r"(class|object|interface|fun|val|var)\b")
# Kopf eines Typs (auch `companion object`), dessen Körper Mitglieder enthält.
KT_TYPE = re.compile(r"^\s*" + _KT_ANNOT + _KT_MODS + r"(?:companion\s+)?(class|object|interface)\b")
KT_SKIP = re.compile(r"\b(private|internal|override|protected)\b")
_SW_ANNOT = r"(?:@\w+(?:\([^)]*\))?\s+)*"
_SW_SET = r"(?:(?:public|internal|fileprivate|private)\(set\)\s+)?"
_SW_MODS = (r"(?:(?:static|class|final|mutating|nonmutating|nonisolated|convenience|required|lazy|weak|unowned|"
            r"indirect|dynamic|optional)\s+)*")
_SW_KIND = r"(?:init|deinit|let|var|func|struct|enum|class|protocol|typealias|extension|actor|subscript)\b"
SW_DECL = re.compile(r"^\s*" + _SW_ANNOT + r"(?:public|open)\s+" + _SW_SET + _SW_MODS + _SW_KIND)
SW_MEMBER = re.compile(r"^\s*" + _SW_ANNOT + _SW_SET + _SW_MODS + _SW_KIND)
SW_PUBLIC_EXT = re.compile(r"^\s*" + _SW_ANNOT + r"public\s+extension\b")
SW_HIDDEN = re.compile(r"\b(private|fileprivate|internal|override)\b(?!\(set\))")


def preceded_by_doc(lines, i, swift):
    """Steht über Zeile `i` (Annotationen übersprungen, ohne Leerzeile) ein Doku-Kommentar?"""
    j = i - 1
    while j >= 0 and (lines[j].strip().startswith("@") or lines[j].strip() == ""):
        if lines[j].strip() == "":
            return False
        j -= 1
    if j < 0:
        return False
    prev = lines[j].strip()
    if swift and prev.startswith("///"):
        return True
    if not prev.endswith("*/"):
        return False
    # Anfang des Blocks suchen: nur `/**` ist Doku.
    while j >= 0 and "/*" not in lines[j]:
        j -= 1
    return j >= 0 and lines[j].strip().startswith("/**")


def _report(f, i, line, out):
    out.append(f"{f.relative_to(ROOT).as_posix()}:{i + 1}: {line.strip()[:80]}")


def check_kotlin(f, out):
    lines = f.read_text(encoding="utf-8").splitlines()
    code = strip_code(lines, swift=False)
    # Je offene «{» ein Eintrag: True für den Körper eines öffentlichen Typs, sonst False (Funktion, Lambda, privat).
    stack, paren, pending = [], 0, None
    for i, text in enumerate(code):
        line = lines[i]
        m = KT_DECL.match(text)
        public_scope = all(stack)
        head = text.split("=")[0]
        # Innerhalb einer Parameterliste (Felder einer Datenklasse je Zeile) keine eigene Deklaration: Die Felder
        # beschreibt der Doku-Kommentar der Klasse (`@property`).
        if m and paren == 0 and public_scope and not KT_SKIP.search(head) \
                and not text.lstrip().startswith("fun interface"):
            if not preceded_by_doc(lines, i, swift=False):
                _report(f, i, line, out)
        if paren == 0 and (m or KT_TYPE.match(text)):
            pending = bool(KT_TYPE.match(text)) and not KT_SKIP.search(head) and public_scope
        for ch in text:
            if ch == "(":
                paren += 1
            elif ch == ")":
                paren = max(paren - 1, 0)
            elif ch == "{":
                stack.append(bool(pending) and paren == 0)
                pending = None
            elif ch == "}" and stack:
                stack.pop()
        tail = text.rstrip()
        follows = next((t.strip() for t in code[i + 1:i + 3] if t.strip()), "")
        if paren == 0 and not tail.endswith((",", ":", "(")) and not follows.startswith((":", "{", "where", ")")):
            pending = None


def check_swift(f, out):
    lines = f.read_text(encoding="utf-8").splitlines()
    code = strip_code(lines, swift=True)
    # Tiefe vor jeder offenen `public extension`; ihre direkten Mitglieder sind ohne eigene Sichtbarkeit öffentlich.
    depth, ext_depths, ext_base = 0, [], None
    for i, text in enumerate(code):
        line = lines[i]
        in_ext = bool(ext_depths) and depth == ext_depths[-1] + 1
        visible = not SW_HIDDEN.search(text.split("=")[0])
        if SW_PUBLIC_EXT.match(text):
            ext_base = depth
        elif SW_DECL.match(text) and visible:
            if not preceded_by_doc(lines, i, swift=True):
                _report(f, i, line, out)
        elif in_ext and SW_MEMBER.match(text) and visible:
            if not preceded_by_doc(lines, i, swift=True):
                _report(f, i, line, out)
        depth += text.count("{") - text.count("}")
        if ext_base is not None and "{" in text:
            if depth > ext_base:
                ext_depths.append(ext_base)
            ext_base = None
        while ext_depths and depth <= ext_depths[-1]:
            ext_depths.pop()


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
