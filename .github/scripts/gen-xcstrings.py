#!/usr/bin/env python3
"""Erzeugt die String-Kataloge der Apple-Apps aus den Android-Texten.

Android ist die Quelle (android/app und android/wear, values-*/strings.xml), dazu ios/Strings
für Texte, die es nur auf Apple gibt. iPhone: App- vor Uhr-Texten. Apple Watch: Uhr- vor App-Texten.

Widgets (PhaseWakeWidget, PhaseWakePhoneWidget) bekommen nur die Schlüssel, die ihr Code braucht. Ermittelt wird
das aus den Quellen des Ziels, so wie Xcode sie kompiliert (ios/PhaseWake.xcodeproj: Ordner des Ziels, dazu das
Paket PhaseWakeCore):
- Jedes String-Literal im Code des Ziels, das ein Schlüssel ist (bei Pluralformen der Name vor «|»), kommt
  hinein – auch ohne `L(…)`, z. B. als Argument für `todayKey:` oder als `Text("…")`.
- Texte lädt der Code nur über `L("schlüssel", …)` und `LP("name", n, …)` (Shared/Theme.swift,
  Shared/Plural.swift).
  Jeder Aufruf braucht ein Literal ohne Interpolation, das im Katalog des Ziels steht; sonst bricht das Skript ab.
  Ausnahme: Parameter in `DURCHREICHEN` (z. B. `L(todayKey)`), wenn jeder Aufrufer dort ein Literal übergibt.
- Andere Wege zum Katalog (NSLocalizedString, String(localized:), LocalizedStringKey, Bundle.localizedString
  ausserhalb der beiden Helfer, `L` als Funktionswert) brechen ab, ebenso im Paket. So zeigt ein Widget nie einen
  Schlüssel statt des Textes. Ziele mit Dateien, die das Skript nicht sicher zuordnen kann, brechen ebenfalls ab.
Aufruf: python3 .github/scripts/gen-xcstrings.py [--check]. Selbsttest: test_gen_xcstrings.py.
"""
import html, json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
RES = {
    "app": ROOT / "android/app/src/main/res",
    "wear": ROOT / "android/wear/src/main/res",
    "apple": ROOT / "ios/Strings",  # Texte nur für die Apple-Apps
}
TARGETS = {
    "PhaseWake": ("app", "wear", "apple"),
    "PhaseWakeWatch": ("wear", "app", "apple"),
    "PhaseWakeWidget": ("wear", "app", "apple"),
    "PhaseWakePhoneWidget": ("app", "apple"),
}
# Ziele, deren Katalog auf die im Code verwendeten Schlüssel gekürzt wird.
WIDGETS = ("PhaseWakeWidget", "PhaseWakePhoneWidget")
PBXPROJ = ROOT / "ios/PhaseWake.xcodeproj/project.pbxproj"
# Lokale Swift-Pakete, die ein Widget einbinden darf (Name → Quellordner); sie dürfen selbst keine Texte laden.
PACKAGES = {"PhaseWakeCore": "ios/PhaseWakeCore/Sources"}
# Schlüssel-Parameter, die an `L(…)` durchgereicht werden; jeder Aufrufer muss ein Literal übergeben.
DURCHREICHEN = ("todayKey", "tomorrowKey")
# Dateien mit den Text-Helfern und der erlaubten Zahl direkter `localizedString(`-Aufrufe darin.
HELFER = {"ios/Shared/Theme.swift": 1, "ios/Shared/Plural.swift": 2}
# Wege zum Katalog, die die Erkennung nicht verfolgt (im Code ohne Kommentare und Literale gesucht).
VERBOTEN = re.compile(r"\bNSLocalizedString\b|\bLocalizedString(?:Key|Resource)\b|\blocalized\s*:"
                      r"|\bimport\s+SwiftUI\b")


def apple_locale(folder: str) -> str | None:
    if folder == "values":
        return "en"
    q = folder[len("values-"):]
    if q.startswith("b+"):
        return "-".join(q.split("+")[1:])
    if q in ("night", "v31") or q.startswith("v") and q[1:].isdigit():
        return None
    lang, _, region = q.partition("-r")
    lang = {"iw": "he", "in": "id"}.get(lang, lang)
    return f"{lang}-{region}" if region else lang


def unescape(text: str) -> str:
    text = html.unescape(text.strip())
    text = re.sub(r"\\(['\"@?])", r"\1", text).replace("\\n", "\n").replace("\\t", "\t")
    text = re.sub(r"%(\d+)\$s", r"%\1$@", text)
    return re.sub(r"%(\d+)\$d", r"%\1$lld", text)


def load(module: str) -> dict[str, dict[str, str]]:
    """Texte je Sprache. Pluralformen werden zu «name|kategorie» (z. B. «hint_title|one»);
    die Apps wählen die Kategorie selbst nach den CLDR-Regeln (Shared/Plural.swift)."""
    out: dict[str, dict[str, str]] = {}
    for folder in sorted(p for p in RES[module].iterdir() if p.name.startswith("values")):
        lang = apple_locale(folder.name)
        f = folder / "strings.xml"
        if lang and f.exists():
            text = f.read_text()
            for key, value in re.findall(r'<string name="([^"]+)"(?![^>]*translatable="false")[^>]*>(.*?)</string>', text, re.S):
                # Überschriebene Material-Texte (Android-Zeitwähler) gehören nicht in die Apple-Kataloge.
                if key.startswith("m3c_"):
                    continue
                out.setdefault(lang, {})[key] = unescape(value)
            for name, body in re.findall(r'<plurals name="([^"]+)"[^>]*>(.*?)</plurals>', text, re.S):
                for quantity, value in re.findall(r'<item quantity="([^"]+)">(.*?)</item>', body, re.S):
                    out.setdefault(lang, {})[f"{name}|{quantity}"] = unescape(value)
    return out


def base(key: str) -> str:
    """Schlüssel, nach dem die Quelle gewählt wird: bei «name|kategorie» die Form «name|other»."""
    return key.split("|")[0] + "|other" if "|" in key else key


def catalog(*modules: str, keep: set[str] | None = None) -> str:
    """Katalog aus den Modulen; mit `keep` nur diese Schlüssel (Pluralformen über ihren Namen vor «|»)."""
    sources = [load(m) for m in modules]
    keys = set().union(*(s["en"] for s in sources))
    # Pluralformen über alle Sprachen vereinigen: Englisch kennt nur «one» und «other»,
    # ru, pl, cs, ar, he, ro brauchen auch «zero», «two», «few» oder «many».
    keys |= {k for s in sources for texts in s.values() for k in texts if "|" in k and base(k) in keys}
    if keep is not None:
        keys = {k for k in keys if k.split("|")[0] in keep}
    keys = sorted(keys)
    langs = sorted(set().union(*sources))
    strings = {}
    for key in keys:
        # Die erste Quelle, die den Text auf Englisch hat, liefert ihn in allen Sprachen
        # (bei Pluralformen gilt die Englisch-Form «other» desselben Namens).
        source = next(s for s in sources if base(key) in s["en"])
        loc = {}
        for lang in langs:
            value = source.get(lang, {}).get(key)
            if value is not None:
                loc[lang] = {"stringUnit": {"state": "translated", "value": value}}
        strings[key] = {"localizations": loc}
    return json.dumps({"sourceLanguage": "en", "strings": strings, "version": "1.0"}, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


class ScanError(Exception):
    """Die Schlüssel eines Widgets lassen sich nicht sicher bestimmen; `args[0]` ist die Liste der Gründe."""


_OPEN = re.compile(r'(#*)("""|")')


def lex(src: str) -> tuple[str, list[tuple[str, bool]]]:
    """Swift-Quelltext ohne Kommentare; jedes String-Literal wird zu «§n§», direkt dahinter der Code seiner
    Interpolationen in Klammern («"a\\(L("x"))"» → «§1§(L(§0§))»). Zeilenumbrüche bleiben (Zeilennummern).
    Liefert den Code und je Literal (Text, hat Interpolation)."""
    lits: list[tuple[str, bool]] = []

    def code(i: int, nested: bool) -> tuple[str, int]:
        out, depth = [], 0
        while i < len(src):
            if src.startswith("//", i):
                j = src.find("\n", i)
                i = len(src) if j < 0 else j
                continue
            if src.startswith("/*", i):
                j, level = i + 2, 1
                while level:
                    if j >= len(src):
                        raise ScanError(["Block-Kommentar ohne Ende"])
                    level += src.startswith("/*", j) - src.startswith("*/", j)
                    j += 2 if src.startswith(("/*", "*/"), j) else 1
                out.append("\n" * src.count("\n", i, j))
                i = j
                continue
            m = _OPEN.match(src, i)
            if m:
                text, i = string(m)
                out.append(text)
                continue
            c = src[i]
            if nested and c == "(":
                depth += 1
            elif nested and c == ")":
                if depth == 0:
                    return "".join(out), i + 1
                depth -= 1
            out.append(c)
            i += 1
        if nested:
            raise ScanError(["Interpolation ohne Ende"])
        return "".join(out), i

    def string(m: re.Match) -> tuple[str, int]:
        hashes, quote = m.group(1), m.group(2)
        close, esc = quote + hashes, "\\" + hashes
        i, text, inner, start = m.end(), [], [], m.start()
        while not src.startswith(close, i):
            if i >= len(src) or quote == '"' and src[i] == "\n":
                raise ScanError([f"String-Literal ohne Ende (Zeile {src.count(chr(10), 0, start) + 1})"])
            if src.startswith(esc + "(", i):
                sub, i = code(i + len(esc) + 1, True)
                inner.append(f"({sub})")
            elif src.startswith(esc, i):
                text.append(src[i + len(esc):i + len(esc) + 1])
                i += len(esc) + 1
            else:
                text.append(src[i])
                i += 1
        lits.append(("".join(text), bool(inner)))
        return f"§{len(lits) - 1}§" + "".join(inner) + "\n" * src.count("\n", start, i), i + len(close)

    return code(0, False)[0], lits


def scan(files: dict[str, str], known: set[str], packages: dict[str, str] | None = None) -> set[str]:
    """Benötigte Schlüssel (Pluralformen als Name vor «|») für Swift-Dateien eines Ziels (Pfad → Text).
    `known`: Schlüssel des Katalogs. `packages`: eingebundene Paketdateien, die keine Texte laden dürfen.
    Wirft ScanError mit allen Gründen, wenn die Menge nicht sicher bestimmbar ist."""
    names = {k.split("|")[0] for k in known}
    plurals = {k.split("|")[0] for k in known if "|" in k}
    singles = {k for k in known if "|" not in k}
    needed, errors = set(), []
    for path, src in sorted((packages or {}).items()):
        code, _ = lex(src)
        for m in list(VERBOTEN.finditer(code)) + list(re.finditer(r"\blocalizedString\s*\(", code)):
            errors.append(f"{path}:{code.count(chr(10), 0, m.start()) + 1}: Paket lädt Texte ({m.group(0)!r}), "
                          "das Widget-Bundle wäre gekürzt")
    for path, src in sorted(files.items()):
        code, lits = lex(src)

        def where(pos: int) -> str:
            return f"{path}:{code.count(chr(10), 0, pos) + 1}"

        def literal(token: str) -> str | None:
            m = re.fullmatch(r"§(\d+)§", token)
            return lits[int(m.group(1))][0] if m and not lits[int(m.group(1))][1] else None

        needed |= {text for text, interp in lits if not interp and text in names}
        for m in VERBOTEN.finditer(code):
            if m.group(0).startswith("import"):
                continue  # SwiftUI ist im Widget erlaubt; `Text("…")` deckt die Literal-Regel ab.
            errors.append(f"{where(m.start())}: {m.group(0)!r} wird nicht erkannt, bitte L(\"…\")/LP(\"…\") nutzen")
        raw = len(re.findall(r"\blocalizedString\s*\(", code))
        if raw != HELFER.get(path, 0):
            errors.append(f"{path}: {raw} direkte localizedString-Aufrufe, erwartet {HELFER.get(path, 0)} "
                          "(nur in den Helfern L/LP; sonst HELFER anpassen)")
        for m in re.finditer(r"(?<![\w.])(LP|L)(?!\w)(\s*\()?", code):
            if re.search(r"\bfunc\s+$", code[:m.start()]):
                continue  # Definition des Helfers
            fn = m.group(1)
            # Erstes Argument: ein einzelnes Token bis «,» oder «)»; alles andere gilt als nicht erkennbar.
            first = re.compile(r"\s*([^,()\s]*)\s*[,)]").match(code, m.end()) if m.group(2) else None
            arg = first.group(1) if first else code[m.end():m.end() + 30].split("\n")[0]
            if m.group(2) is None:
                errors.append(f"{where(m.start())}: {fn} ohne direktes Schlüssel-Argument (z. B. als Funktionswert)")
            elif first is None:
                errors.append(f"{where(m.start())}: {fn}({arg}…) – Schlüssel nicht als Literal erkennbar")
            elif arg in DURCHREICHEN and fn == "L":
                pass  # Aufrufer prüft die Schleife unten
            elif (key := literal(arg)) is None:
                errors.append(f"{where(m.start())}: {fn}({arg}) – Schlüssel nicht als Literal erkennbar")
            elif key not in (plurals if fn == "LP" else singles):
                errors.append(f"{where(m.start())}: {fn}(\"{key}\") – Schlüssel fehlt im Katalog")
        for name in DURCHREICHEN:
            for m in re.finditer(rf"(?<![\w.]){name}\s*:\s*(§\d+§|String\b|[^\s,)]*)", code):
                key = literal(m.group(1))
                if m.group(1) != "String" and key not in singles:
                    errors.append(f"{where(m.start())}: {name}: {m.group(1)} – "
                                  "Literal mit Schlüssel des Katalogs nötig")
    if errors:
        raise ScanError(errors)
    return needed


def target_files(target: str, pbx: str) -> tuple[list[str], list[str]]:
    """Quellordner (relativ zu ios/) und Paketnamen eines Ziels aus der project.pbxproj. Nur synchronisierte
    Ordner werden unterstützt; einzeln eingetragene Dateien oder Ausnahmen, die Dateien hinzufügen, brechen ab."""
    m = re.search(rf"\w+ /\* {re.escape(target)} \*/ = \{{\s*isa = PBXNativeTarget;(.*?)\n\t\t}};", pbx, re.S)
    if not m:
        raise ScanError([f"Ziel {target} nicht in der project.pbxproj"])
    body, errors = m.group(1), []
    if "PBXFileSystemSynchronizedGroupBuildPhaseMembershipExceptionSet" in pbx:
        errors.append("Ausnahmen je Build-Phase in synchronisierten Ordnern werden nicht unterstützt")

    def ids(field: str) -> list[str]:
        block = re.search(rf"{field} = \((.*?)\);", body, re.S)
        return re.findall(r"(\w+) /\*", block.group(1)) if block else []

    folders = []
    for gid in ids("fileSystemSynchronizedGroups"):
        g = re.search(rf"{gid} /\* [^*]* \*/ = \{{isa = PBXFileSystemSynchronizedRootGroup;.*?path = ([^;]+);", pbx)
        if not g:
            errors.append(f"{target}: Ordner {gid} nicht lesbar")
        else:
            folders.append(g.group(1).strip('"'))
    for phase in ids("buildPhases"):
        p = re.search(rf"{phase} /\* Sources \*/ = \{{.*?files = \((.*?)\);", pbx, re.S)
        if p and p.group(1).strip():
            errors.append(f"{target}: einzeln eingetragene Quelldateien werden nicht unterstützt")
    tid = re.search(rf"(\w+) /\* {re.escape(target)} \*/ = \{{\s*isa = PBXNativeTarget;", pbx).group(1)
    own = set(ids("fileSystemSynchronizedGroups"))
    for ex in re.finditer(r"(\w+) /\* Exceptions for [^*]* \*/ = \{\s*isa = PBXFileSystemSynchronizedBuildFile"
                          r"ExceptionSet;.*?target = (\w+)", pbx, re.S):
        group = re.search(rf"(\w+) /\* [^*]* \*/ = \{{isa = PBXFileSystemSynchronizedRootGroup; exceptions = "
                          rf"\([^)]*{ex.group(1)}", pbx)
        if ex.group(2) == tid and (not group or group.group(1) not in own):
            errors.append(f"{target}: Ausnahme {ex.group(1)} fügt Dateien eines fremden Ordners hinzu")
    packages = []
    for pid in ids("packageProductDependencies"):
        name = re.search(rf"{pid} /\* (\w+) \*/ = \{{", pbx).group(1)
        if name not in PACKAGES:
            errors.append(f"{target}: Paket {name} unbekannt (PACKAGES ergänzen)")
        packages.append(name)
    if errors:
        raise ScanError(errors)
    return folders, packages


def swift(folder: str) -> dict[str, str]:
    """Swift-Dateien unter `folder` (relativ zu ROOT), Pfad relativ zu ROOT → Text."""
    return {p.relative_to(ROOT).as_posix(): p.read_text() for p in sorted((ROOT / folder).rglob("*.swift"))}


def widget_keys(target: str, known: set[str]) -> set[str]:
    folders, packages = target_files(target, PBXPROJ.read_text())
    files = {k: v for f in folders for k, v in swift("ios/" + f).items()}
    linked = {k: v for name in packages for k, v in swift(PACKAGES[name]).items()}
    try:
        return scan(files, known, linked)
    except ScanError as e:
        raise ScanError([f"{target}: {msg}" for msg in e.args[0]]) from None


def main() -> int:
    stale = []
    try:
        for target, modules in TARGETS.items():
            path = ROOT / "ios" / target / "Localizable.xcstrings"
            keep = None
            if target in WIDGETS:
                known = set(json.loads(catalog(*modules))["strings"])
                keep = widget_keys(target, known)
            text = catalog(*modules, keep=keep)
            if "--check" in sys.argv:
                if not path.exists() or path.read_text() != text:
                    stale.append(str(path.relative_to(ROOT)))
            else:
                path.write_text(text)
    except ScanError as e:
        for msg in e.args[0]:
            print("::error::Widget-Schlüssel: " + msg)
        return 1
    if stale:
        print("::error::String-Kataloge veraltet, bitte gen-xcstrings.py ausführen: " + ", ".join(stale))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
