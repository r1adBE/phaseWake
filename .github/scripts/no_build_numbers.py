#!/usr/bin/env python3
"""Keine Build-Nummern und keine TODO-Marken im Produktivcode (docs/ENTWICKLUNG.md, Abschnitte 2 und 3).

Durchsucht android/*/src/main und die Swift-Quellen der Apps und des Kerns (ohne Tests). Dazu Kommentare (Audit N-06):
«Build 54», «build #54», «Build-Nummer 54» in Kommentaren aller Kotlin-, Gradle-, Swift- und YAML-Dateien (auch Tests
und Workflows). Obergrenze: 0.

Ausnahme (Entscheid r1AD 14.11.2026, Build-Nummer in «Über», Diagnose und Nacht-Check): Je eine Datei pro Plattform
(`BuildNumber`) darf die Build-Nummer lesen; Literale, Texte und TODO-Marken sind auch dort verboten.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RULES = [
    (re.compile(r"\b(versionCode|longVersionCode|VERSION_CODE\b(?!S))"), "Build-Nummer (versionCode) im Code"),
    (re.compile(r"CFBundleVersion\b"), "Build-Nummer (CFBundleVersion) im Code"),
    (re.compile(r"PHASEWAKE_BUILD"), "Build-Nummer aus dem Build im Code"),
    (re.compile(r"\"[^\"]*\b[Bb]uild\s*#?\d+[^\"]*\""), "Build-Nummer als Text"),
    (re.compile(r"\b(TODO|FIXME|XXX)\b"), "TODO/FIXME/XXX im Produktivcode"),
]
# Einzige Dateien, die die Build-Nummer lesen dürfen (nur die Regeln mit «lesen»).
READERS = {
    "android/shared/src/main/kotlin/com/phasewake/app/shared/BuildNumber.kt",
    "ios/Shared/BuildNumber.swift",
}
READ_RULES = {"Build-Nummer (versionCode) im Code", "Build-Nummer (CFBundleVersion) im Code"}
# «Build» mit Nummer (auch «Build-Nummer 54», «Build-Nr. 54», «build #54»); Zahl als eigenes Wort.
BUILD_NUMBER = re.compile(r"\b[Bb]uild(?:-Nummer|-Nr\.?)?\s*#?\s*\d+\b")
# Kommentar-Anfang je Sprache: Kotlin/Swift «//», «/*» oder Fortsetzung «*»; YAML «#». Grob, aber ohne Treffer in Texten.
SLASH_COMMENT = re.compile(r"//|/\*|^\s*\*")
HASH_COMMENT = re.compile(r"(^|\s)#")


def sources():
    for f in (ROOT / "android").glob("*/src/main/**/*.kt"):
        yield f
    for f in (ROOT / "ios").rglob("*.swift"):
        rel = f.relative_to(ROOT).as_posix()
        if "/Tests/" not in rel and "/.build/" not in rel:
            yield f


def commented():
    """Alle Dateien für die Kommentar-Prüfung mit dem passenden Kommentar-Muster (ohne Build-Ordner)."""
    skip = ("/build/", "/.build/", "/.gradle/")
    groups = [
        (SLASH_COMMENT, (ROOT / "android").rglob("*.kt")),
        (SLASH_COMMENT, (ROOT / "android").rglob("*.kts")),
        (SLASH_COMMENT, (ROOT / "ios").rglob("*.swift")),
        (HASH_COMMENT, (ROOT / ".github").rglob("*.yml")),
        (HASH_COMMENT, (ROOT / ".github").rglob("*.yaml")),
    ]
    for comment, files in groups:
        for f in files:
            if not any(s in "/" + f.relative_to(ROOT).as_posix() for s in skip):
                yield comment, f


def comment_hits():
    for comment, f in commented():
        for no, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            start = comment.search(line)
            if start and BUILD_NUMBER.search(line, start.start()):
                yield f"{f.relative_to(ROOT).as_posix()}:{no}: Build-Nummer im Kommentar"


def main():
    found = []
    for f in sources():
        reader = f.relative_to(ROOT).as_posix() in READERS
        for no, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            for rx, msg in RULES:
                if reader and msg in READ_RULES:
                    continue
                if rx.search(line):
                    found.append(f"{f.relative_to(ROOT).as_posix()}:{no}: {msg}")
    found += comment_hits()
    for item in found:
        print("::error::" + item if "--ci" in sys.argv else item)
    print(f"{len(found)} Funde (Obergrenze 0)")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
