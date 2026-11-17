#!/usr/bin/env python3
"""Prüft Zeilenbreite, Funktionslänge und Parameterzahl in Kotlin und Swift (docs/ENTWICKLUNG.md, Abschnitt 2).

Grenzen wie in den Standardregeln von detekt und SwiftLint:
- Zeile: höchstens 120 Zeichen (alle Quellen, auch Tests).
- Funktion: höchstens 60 Zeilen vom Kopf bis zum Ende des Körpers; Oberflächen (Kotlin `@Composable`, SwiftUI
  `@ViewBuilder` und alles, was `some View` liefert) höchstens 120. Gemessen werden Funktionen mit Block- und
  Ausdruckskörper (`= run { … }`), Kotlin `init`, `constructor`, Getter/Setter, Swift `init`, `deinit`,
  `subscript` und berechnete Eigenschaften (`var x: T { … }`).
- Parameter: höchstens 7 je Funktion (sonst Datenklasse). Konstruktoren zählen nicht: Kotlin-Datenklassen und
  Swift-`init` von Werttypen tragen ihre Felder (wie `ignoreDataClasses` bei detekt).
Die Dateilänge prüft `line_limit.py`. Ausgabe mit `--ci` als GitHub-Annotation. Selbsttest: `test_code_limits.py`.
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
# So weit sucht die Prüfung nach dem Körper bzw. dem Ende einer Parameterliste (Zeilen).
HEADER_LINES = 60

_ANNOT = r"(?:@[\w.]+(?::\w+)?(?:\([^)]*\))?\s+)*"
_MODS = r"(?:[a-z]+(?:\([a-z]+\))?\s+)*"
KOTLIN_FUN = re.compile(r"^\s*" + _ANNOT + _MODS + r"fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(`[^`]+`|\w+)\s*\(")
KOTLIN_CTOR = re.compile(r"^\s*" + _ANNOT + _MODS + r"(constructor)\s*\(")
KOTLIN_INIT = re.compile(r"^\s*(init)\s*\{")
# Getter/Setter am Zeilenanfang oder hinter der Eigenschaft (`val x: Int get() = …`).
KOTLIN_ACCESSOR = re.compile(r"^\s*" + _ANNOT + _MODS + r"(get|set)\s*\(\s*(?:\w+\s*(?::\s*[\w.<>?, ]+)?)?\)")
KOTLIN_INLINE_ACCESSOR = re.compile(r"^\s*" + _ANNOT + _MODS + r"(?:val|var)\s[^=]*?\s(get)\s*\(\s*\)")
SWIFT_FUNC = re.compile(r"^\s*" + _ANNOT + _MODS + r"(?:func\s+(\w+|[^\s(<]+)\s*(?:<[^>]*>)?\s*\("
                        r"|(init)[?!]?\s*(?:<[^>]*>)?\s*\(|(subscript)\s*(?:<[^>]*>)?\s*\()")
SWIFT_DEINIT = re.compile(r"^\s*(deinit)\s*\{")
# Berechnete Eigenschaft: Typ ohne «=», danach direkt der Körper (`var body: some View {`).
SWIFT_COMPUTED = re.compile(r"^\s*" + _ANNOT + _MODS + r"var\s+(\w+)\s*:\s*([^={]+?)\s*\{")
SWIFT_UI = re.compile(r"->\s*some\s+View\b")
_SWIFT_RAW = re.compile(r"(#+)\"")
# Zeilenende bzw. -anfang, nach denen ein Ausdruck weitergeht.
_EXPR_TAIL = ("=", "(", "[", "{", ",", "+", "-", "*", "/", "%", "&&", "||", "->", ".", "?:", ":", "?", "<", ">")
_EXPR_HEAD = (".", "?.", "?:", "!!", "&&", "||", "+", "-", "*", "/", "%", "else", "catch", "finally", "as ", "as?",
              ":", "->", "?", "==", "!=", "<", ">", "in ", "!in ", "is ", "!is ")
_HEADER_TAIL = (":", "->", ",", "&", "where", "=", "(", "<")
_HEADER_HEAD = ("{", "=", ":", "->", "where", "throws", "rethrows", "async", "&", ",", ")", ">")


def _interpolation_end(line, i):
    """Index nach der Klammer, die eine Interpolation ab `i` (hinter `${` bzw. `\\(`) schliesst."""
    depth = 1
    while i < len(line):
        c = line[i]
        if c == '"':
            i = _string_end(line, i, hashes=0, swift=False)
            continue
        if c in "({[":
            depth += 1
        elif c in ")}]":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return i


def _string_end(line, i, hashes, swift):
    """Index nach dem Ende der einzeiligen Zeichenkette, deren «"» an `i` steht (mit `hashes` × «#» bei Swift)."""
    j, close = i + 1, '"' + "#" * hashes
    while j < len(line):
        c = line[j]
        if c == "\\":
            if swift and line.startswith("#" * hashes + "(", j + 1):
                j = _interpolation_end(line, j + 2 + hashes)
            else:
                j += 2 if hashes == 0 else 1
            continue
        if not swift and c == "$" and line.startswith("{", j + 1):
            j = _interpolation_end(line, j + 2)
            continue
        if line.startswith(close, j):
            return j + len(close)
        j += 1
    return j


def strip_code(lines, swift):
    """Zeilen ohne Kommentare und Zeichenketten (Klammern darin zählen nicht)."""
    out, in_block, raw_end = [], 0, None
    for line in lines:
        res, i = [], 0
        while i < len(line):
            if raw_end:
                end = line.find(raw_end, i)
                if end < 0:
                    i = len(line)
                else:
                    i, raw_end = end + len(raw_end), None
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
            hashes = 0
            if swift and c == "#":
                raw = _SWIFT_RAW.match(line, i)
                if raw:
                    hashes = len(raw.group(1))
                    i += hashes
                    c = '"'
            if line.startswith('"""', i):
                raw_end, i = '"""' + "#" * hashes, i + 3
                res.append('""')
                continue
            if c == '"':
                res.append('""')
                i = _string_end(line, i, hashes, swift)
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


def _chars(code, line, col):
    """Alle Zeichen ab Zeile `line`, Spalte `col` als (Zeile, Spalte, Zeichen)."""
    for n in range(line, len(code)):
        text = code[n]
        for c in range(col if n == line else 0, len(text)):
            yield n, c, text[c]


def _matching(code, line, col):
    """(Zeile, Spalte) der Klammer, die die Klammer an (line, col) schliesst; None ohne Gegenstück."""
    depth = 0
    for n, c, ch in _chars(code, line, col):
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                return n, c
    return None


def count_params(code, start, col):
    """Parameter der Liste, die in Zeile `start` an Spalte `col` mit «(» beginnt (Komma am Ende zählt nicht).

    Kommas zählen nur auf oberster Ebene: nicht in Klammern, Lambdas oder Typargumenten (`Map<A, B>`); das «>» von
    `->` schliesst kein Typargument."""
    depth, angle, commas, content, trailing, prev = 0, 0, 0, False, False, ""
    for n, _, ch in _chars(code, start, col):
        if n > start + HEADER_LINES:
            break
        if ch in "([{":
            depth += 1
            if depth > 1:
                content, trailing = True, False
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                return (commas - (1 if trailing else 0) + 1) if content else 0
        elif ch == "<" and depth == 1 and prev and (prev.isalnum() or prev in "_>?."):
            angle += 1
            content, trailing = True, False
        elif ch == ">" and depth == 1 and angle > 0 and prev != "-":
            angle -= 1
        elif depth == 1 and angle == 0 and ch == ",":
            commas, trailing = commas + 1, True
        elif depth >= 1 and not ch.isspace():
            content, trailing = True, False
        if not ch.isspace():
            prev = ch
        elif depth == 1:
            prev = " "
    return 0


def _next_code_line(code, n):
    """Text der nächsten nicht leeren Zeile nach `n` (leer am Dateiende)."""
    for m in range(n + 1, min(n + 4, len(code))):
        if code[m].strip():
            return code[m].strip()
    return ""


def _body_start(code, line, col, swift):
    """Sucht hinter dem Kopf ab (line, col) den Körper: ("block"|"expr", Zeile, Spalte) oder None (kein Körper).

    Rückgabetyp, `where`-Klausel, `throws` usw. dürfen über mehrere Zeilen gehen. «=» leitet nur in Kotlin einen
    Ausdruckskörper ein (in Swift steht es nur in `where T == U`)."""
    paren, angle, prev = 0, 0, ""
    n = line
    while n < len(code) and n <= line + HEADER_LINES:
        text = code[n]
        for c in range(col if n == line else 0, len(text)):
            ch = text[c]
            nxt = text[c + 1] if c + 1 < len(text) else ""
            if ch in "([":
                paren += 1
            elif ch in ")]":
                paren -= 1
            elif ch == "<":
                angle += 1
            elif ch == ">" and prev != "-" and angle > 0:
                angle -= 1
            elif ch == "{" and paren == 0:
                return "block", n, c
            elif ch == "=" and paren == 0 and not swift and text[c - 1:c] not in ("=", "!", "<", ">") and nxt != "=":
                return "expr", n, c
            if not ch.isspace():
                prev = ch
        tail = text.rstrip()
        if paren > 0 or angle > 0 or tail.endswith(_HEADER_TAIL) or _next_code_line(code, n).startswith(_HEADER_HEAD):
            n += 1
            continue
        return None
    return None


def _expression_end(code, line, col):
    """Letzte Zeile des Kotlin-Ausdruckskörpers, der an (line, col) mit «=» beginnt."""
    depth = 0
    n = line
    while n < len(code):
        text = code[n]
        for c in range(col + 1 if n == line else 0, len(text)):
            if text[c] in "([{":
                depth += 1
            elif text[c] in ")]}":
                depth -= 1
        if depth > 0:
            n += 1
            continue
        tail = text.rstrip()
        following = code[n + 1].strip() if n + 1 < len(code) else ""
        ends_open = tail.endswith(_EXPR_TAIL) and not tail.endswith(("++", "--"))
        if ends_open or (following and following.startswith(_EXPR_HEAD)):
            n += 1
            continue
        return n
    return len(code) - 1


def _body_end(code, kind, line, col):
    """Letzte Zeile eines Körpers, der an (line, col) mit «{» bzw. «=» beginnt."""
    if kind == "expr":
        return _expression_end(code, line, col)
    close = _matching(code, line, col)
    return close[0] if close else None


def _annotations_before(code, n):
    """Annotationszeilen direkt über Zeile `n` (auch mehrzeilige Argumente) als Text."""
    parts, j = [], n - 1
    while j >= 0:
        text = code[j].strip()
        if not text or not (text.startswith(("@", ")")) or text.endswith((",", "("))):
            break
        parts.append(text)
        j -= 1
    return " ".join(reversed(parts))


def _declaration(text, swift):
    """(Name, Spalte nach dem Namen bzw. der «(», Art, Annotationen) für einen Funktionskopf in `text`, sonst None.

    Art: "params" (Parameterliste mit Zählung), "ctor" (Parameterliste ohne Zählung), "block" (Körper folgt)."""
    if swift:
        m = SWIFT_FUNC.match(text)
        if m:
            name = m.group(1) or m.group(2) or m.group(3)
            return name, m.end() - 1, "ctor" if name == "init" else "params", text[:m.start(m.lastindex)]
        m = SWIFT_DEINIT.match(text) or SWIFT_COMPUTED.match(text)
        if m:
            # Der Typ einer berechneten Eigenschaft zählt wie der Rückgabetyp (`var header: some View`).
            ui = " -> " + m.group(2) if m.re is SWIFT_COMPUTED else ""
            return m.group(1), m.end() - 1, "block", text[:m.start(1)] + ui
        return None
    for regex, kind in ((KOTLIN_FUN, "params"), (KOTLIN_CTOR, "ctor"), (KOTLIN_ACCESSOR, "ctor"),
                        (KOTLIN_INLINE_ACCESSOR, "ctor"), (KOTLIN_INIT, "block")):
        m = regex.match(text)
        if m:
            col = m.end() - 1
            if regex in (KOTLIN_ACCESSOR, KOTLIN_INLINE_ACCESSOR):
                col = text.index("(", m.start(1))
            return m.group(1), col, kind, text[:m.start(1)]
    return None


def measure(code, swift):
    """Alle Funktionen in `code` (ohne Kommentare/Zeichenketten) als Dicts: name, line (0-basiert), params, length, ui.

    `params` ist None für Konstruktoren, Getter/Setter und Blöcke; `length` None ohne Körper."""
    found = []
    for n, text in enumerate(code):
        decl = _declaration(text, swift)
        if not decl:
            continue
        name, col, kind, prefix = decl
        params = count_params(code, n, col) if kind == "params" else None
        head = ""
        if kind == "block":
            start = ("block", n, col)
        else:
            close = _matching(code, n, col)
            start = _body_start(code, close[0], close[1] + 1, swift) if close else None
            if start:
                # Kopf hinter der Parameterliste: Rückgabetyp, `where`, `throws`.
                head = " ".join([code[close[0]][close[1] + 1:]] + code[close[0] + 1:start[1] + 1])
        end = _body_end(code, *start) if start else None
        annotations = _annotations_before(code, n) + " " + prefix
        if swift:
            ui = bool(SWIFT_UI.search(head + " " + prefix)) or "@ViewBuilder" in annotations
        else:
            ui = re.search(r"@Composable\b", annotations) is not None
        found.append({"name": name, "line": n, "params": params,
                      "length": None if end is None else end - n + 1, "ui": ui})
    return found


def function_end(code, start, swift=False):
    """Zeile, in der die Funktion aus Zeile `start` endet (None ohne Körper oder ohne Funktionskopf)."""
    for item in measure(code, swift):
        if item["line"] == start:
            return None if item["length"] is None else start + item["length"] - 1
    return None


def check(path, rel):
    swift = path.suffix == ".swift"
    lines = path.read_text(encoding="utf-8").split("\n")
    errors = []
    for n, line in enumerate(lines, 1):
        if len(line) > MAX_LINE:
            errors.append(f"{rel}:{n}: Zeile {len(line)} Zeichen (höchstens {MAX_LINE})")
    for item in measure(strip_code(lines, swift), swift):
        where, name = f"{rel}:{item['line'] + 1}", item["name"]
        if item["params"] is not None and item["params"] > MAX_PARAMS:
            errors.append(f"{where}: {name} hat {item['params']} Parameter (höchstens {MAX_PARAMS})")
        limit = MAX_UI_FUNCTION if item["ui"] else MAX_FUNCTION
        if item["length"] is not None and item["length"] > limit:
            errors.append(f"{where}: {name} hat {item['length']} Zeilen (höchstens {limit})")
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
