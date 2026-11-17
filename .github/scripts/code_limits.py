#!/usr/bin/env python3
"""Prüft Zeilenbreite, Funktionslänge und Parameterzahl in Kotlin und Swift (docs/ENTWICKLUNG.md, Abschnitt 2).

Grenzen wie in den Standardregeln von detekt und SwiftLint:
- Zeile: höchstens 120 Zeichen (alle Quellen, auch Tests).
- Funktion: höchstens 60 Zeilen vom Kopf bis zur schliessenden Klammer; Oberflächen (Kotlin `@Composable`, SwiftUI
  `body` und Funktionen, die `some View` liefern) höchstens 120.
- Parameter: höchstens 7 je Funktion (sonst Datenklasse). Konstruktoren zählen nicht: Kotlin-Datenklassen und
  Swift-`init` von Werttypen tragen ihre Felder (wie `ignoreDataClasses` bei detekt).
Die Dateilänge prüft `line_limit.py`. Ausgabe mit `--ci` als GitHub-Annotation.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ["android", "ios"]
SKIP = ("/build/", "/.build/")
MAX_LINE = 120
MAX_FUNCTION = 60
MAX_UI_FUNCTION = 120
MAX_PARAMS = 7

KOTLIN_FUN = re.compile(r"^\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:[a-z]+\s+)*fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(`[^`]+`|\w+)\s*\(")
SWIFT_FUNC = re.compile(r"^\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:[a-z]+(?:\([a-z]+\))?\s+)*(?:func\s+(\w+|[^\s(<]+)\s*(?:<[^>]*>)?\s*\(|(init)\??\s*(?:<[^>]*>)?\s*\()")
SWIFT_BODY = re.compile(r"^\s*(?:@\w+\s+)*(?:[a-z]+\s+)*var\s+body\s*:\s*some\s+View\s*\{")


def strip_code(lines, swift):
    """Zeilen ohne Kommentare und Zeichenketten (Klammern darin zählen nicht)."""
    out, in_block, in_raw = [], 0, False
    for line in lines:
        res, i = [], 0
        while i < len(line):
            if in_raw:
                end = line.find('"""', i)
                if end < 0:
                    i = len(line)
                else:
                    in_raw, i = False, end + 3
                continue
            if in_block:
                if line.startswith("*/", i):
                    in_block -= 1
                    i += 2
                elif line.startswith("/*", i):
                    in_block += 1
                    i += 2
                else:
                    i += 1
                continue
            c = line[i]
            if line.startswith("//", i):
                break
            if line.startswith("/*", i):
                in_block, i = 1, i + 2
                continue
            if line.startswith('"""', i):
                in_raw, i = True, i + 3
                res.append('""')
                continue
            if c == '"':
                j = i + 1
                while j < len(line) and line[j] != '"':
                    j += 2 if line[j] == "\\" else 1
                res.append('""')
                i = j + 1
                continue
            if c == "'" and not swift:
                j = i + 1
                while j < len(line) and line[j] != "'":
                    j += 2 if line[j] == "\\" else 1
                res.append("''")
                i = j + 1
                continue
            res.append(c)
            i += 1
        out.append("".join(res))
    return out


def count_params(code, start, col):
    """Parameter der Liste, die in Zeile `start` an Spalte `col` mit «(» beginnt."""
    depth, commas, content = 0, 0, False
    for n in range(start, min(start + 60, len(code))):
        text = code[n][col:] if n == start else code[n]
        for ch in text:
            if ch in "([{<":
                depth += 1
                if depth > 1:
                    content = True
                continue
            if ch in ")]}>":
                depth -= 1
                if depth == 0:
                    return commas + 1 if content else 0
                continue
            if depth == 1 and ch == ",":
                commas += 1
            elif depth >= 1 and not ch.isspace():
                content = True
        if depth == 1 and code[n].rstrip().endswith(","):
            pass
    return 0


def function_end(code, start):
    """Zeile der schliessenden Klammer des Funktionskörpers (oder None ohne Körper / Ausdruck)."""
    depth, opened, paren = 0, False, 0
    for n in range(start, len(code)):
        for ch in code[n]:
            if ch == "(":
                paren += 1
            elif ch == ")":
                paren -= 1
            elif ch == "=" and paren == 0 and not opened and depth == 0:
                return None
            elif ch == "{":
                depth += 1
                opened = True
            elif ch == "}":
                depth -= 1
                if opened and depth == 0:
                    return n
        if not opened and n > start + 40:
            return None
    return None


def check(path, rel):
    swift = path.suffix == ".swift"
    lines = path.read_text(encoding="utf-8").split("\n")
    errors = []
    for n, line in enumerate(lines, 1):
        if len(line) > MAX_LINE:
            errors.append(f"{rel}:{n}: Zeile {len(line)} Zeichen (höchstens {MAX_LINE})")
    code = strip_code(lines, swift)
    for n, text in enumerate(code):
        match = (SWIFT_FUNC if swift else KOTLIN_FUN).match(text)
        body = swift and SWIFT_BODY.match(text)
        if not match and not body:
            continue
        name = "body" if body else (match.group(1) or match.group(2))
        if match and name != "init":
            params = count_params(code, n, match.end() - 1)
            if params > MAX_PARAMS:
                errors.append(f"{rel}:{n + 1}: {name} hat {params} Parameter (höchstens {MAX_PARAMS})")
        end = function_end(code, n)
        if end is None:
            continue
        length = end - n + 1
        before = " ".join(code[max(0, n - 3):n + 1])
        head = " ".join(code[n:min(end, n + 8)])
        ui = body or "@Composable" in before or (swift and re.search(r"->\s*some\s+View", head))
        limit = MAX_UI_FUNCTION if ui else MAX_FUNCTION
        if length > limit:
            errors.append(f"{rel}:{n + 1}: {name} hat {length} Zeilen (höchstens {limit})")
    return errors


def main():
    errors, count = [], 0
    for base in SOURCES:
        for ext in ("*.kt", "*.kts", "*.swift"):
            for f in sorted((ROOT / base).rglob(ext)):
                rel = f.relative_to(ROOT).as_posix()
                if any(s in "/" + rel for s in SKIP):
                    continue
                count += 1
                errors += check(f, rel)
    only = [a for a in sys.argv[1:] if not a.startswith("--")]
    if only:
        errors = [e for e in errors if any(e.startswith(o) for o in only)]
    for e in errors:
        print("::error::" + e if "--ci" in sys.argv else "Fehler: " + e)
    print(f"{count} Dateien, {len(errors)} Verstösse (Zeile ≤ {MAX_LINE}, Funktion ≤ {MAX_FUNCTION}/{MAX_UI_FUNCTION},"
          f" Parameter ≤ {MAX_PARAMS})")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
