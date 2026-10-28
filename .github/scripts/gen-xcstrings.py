#!/usr/bin/env python3
"""Erzeugt die String-Kataloge der Apple-Apps aus den Android-Texten.

Android ist die Quelle (android/app und android/wear, values-*/strings.xml).
iPhone: App-Texte vor Uhr-Texten. Apple Watch: Uhr-Texte vor App-Texten.
Aufruf: python3 .github/scripts/gen-xcstrings.py [--check]
"""
import html, json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
RES = {"app": ROOT / "android/app/src/main/res", "wear": ROOT / "android/wear/src/main/res"}
TARGETS = {"PhaseWake": ("app", "wear"), "PhaseWakeWatch": ("wear", "app")}


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
    out: dict[str, dict[str, str]] = {}
    for folder in sorted(p for p in RES[module].iterdir() if p.name.startswith("values")):
        lang = apple_locale(folder.name)
        f = folder / "strings.xml"
        if lang and f.exists():
            for key, value in re.findall(r'<string name="([^"]+)"(?![^>]*translatable="false")[^>]*>(.*?)</string>', f.read_text(), re.S):
                out.setdefault(lang, {})[key] = unescape(value)
    return out


def catalog(first: str, second: str) -> str:
    a, b = load(first), load(second)
    keys = sorted(set(a["en"]) | set(b["en"]))
    strings = {}
    for key in keys:
        loc = {}
        for lang in sorted(set(a) | set(b)):
            value = a.get(lang, {}).get(key) if key in a["en"] else b.get(lang, {}).get(key)
            if value is not None:
                loc[lang] = {"stringUnit": {"state": "translated", "value": value}}
        strings[key] = {"localizations": loc}
    return json.dumps({"sourceLanguage": "en", "strings": strings, "version": "1.0"}, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def main() -> int:
    stale = []
    for target, (first, second) in TARGETS.items():
        path = ROOT / "ios" / target / "Localizable.xcstrings"
        text = catalog(first, second)
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
