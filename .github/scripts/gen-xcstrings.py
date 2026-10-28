#!/usr/bin/env python3
"""Erzeugt die String-Kataloge der Apple-Apps aus den Android-Texten.

Android ist die Quelle (android/app und android/wear, values-*/strings.xml), dazu ios/Strings
für Texte, die es nur auf Apple gibt. iPhone: App- vor Uhr-Texten. Apple Watch: Uhr- vor App-Texten.
Aufruf: python3 .github/scripts/gen-xcstrings.py [--check]
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
}


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
                out.setdefault(lang, {})[key] = unescape(value)
            for name, body in re.findall(r'<plurals name="([^"]+)"[^>]*>(.*?)</plurals>', text, re.S):
                for quantity, value in re.findall(r'<item quantity="([^"]+)">(.*?)</item>', body, re.S):
                    out.setdefault(lang, {})[f"{name}|{quantity}"] = unescape(value)
    return out


def catalog(*modules: str) -> str:
    sources = [load(m) for m in modules]
    keys = sorted(set().union(*(s["en"] for s in sources)))
    langs = sorted(set().union(*sources))
    strings = {}
    for key in keys:
        # Die erste Quelle, die den Text auf Englisch hat, liefert ihn in allen Sprachen.
        source = next(s for s in sources if key in s["en"])
        loc = {}
        for lang in langs:
            value = source.get(lang, {}).get(key)
            if value is not None:
                loc[lang] = {"stringUnit": {"state": "translated", "value": value}}
        strings[key] = {"localizations": loc}
    return json.dumps({"sourceLanguage": "en", "strings": strings, "version": "1.0"}, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def main() -> int:
    stale = []
    for target, modules in TARGETS.items():
        path = ROOT / "ios" / target / "Localizable.xcstrings"
        text = catalog(*modules)
        if "--check" in sys.argv:
            if not path.exists() or path.read_text() != text:
                stale.append(str(path.relative_to(ROOT)))
        else:
            path.write_text(text)
    if stale:
        print("::error::String-Kataloge veraltet, bitte gen-xcstrings.py ausführen: " + ", ".join(stale))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
