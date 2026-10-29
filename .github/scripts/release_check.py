#!/usr/bin/env python3
"""Prüft den Store-Build (Release) von Handy und Uhr auf Testreste (Audit PW-02).

Aufruf aus android/ nach `assembleRelease`: release_check.py
Gelesen wird das zusammengeführte Release-Manifest jeder App. Fehler, wenn
- die App als debuggable markiert ist,
- eine Vorschau- oder Test-Activity enthalten ist (z. B. PreviewActivity aus ui-tooling),
- für eine App kein Release-Manifest gefunden wird (Build fehlte).
"""
import glob
import sys
import xml.etree.ElementTree as ET

NS = "{http://schemas.android.com/apk/res/android}"
APPS = ("app", "wear")
FORBIDDEN = ("PreviewActivity", "ComponentActivity", "TestActivity")


def manifest(app):
    hits = [p for p in glob.glob(f"{app}/build/intermediates/merged_manifest*/**/AndroidManifest.xml", recursive=True)
            if "release" in p.lower()]
    return sorted(hits)[0] if hits else None


def check(app):
    path = manifest(app)
    if path is None:
        return [f"{app}: kein Release-Manifest gefunden (assembleRelease gelaufen?)"]
    errors = []
    application = ET.parse(path).getroot().find("application")
    if application is not None and application.get(NS + "debuggable") == "true":
        errors.append(f"{app}: Release-Build ist debuggable")
    for activity in application.findall("activity") if application is not None else []:
        name = activity.get(NS + "name", "")
        if name.rsplit(".", 1)[-1] in FORBIDDEN:
            errors.append(f"{app}: Release enthält {name}")
    return errors


def main():
    errors = [e for app in APPS for e in check(app)]
    for e in errors:
        print(f"::error title=Store-Build::{e}")
    if not errors:
        print("::notice title=Store-Build::Handy und Uhr: Release ohne debuggable und ohne Vorschau-Activity")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
