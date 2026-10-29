#!/usr/bin/env python3
"""Prüft, dass alle Geräte dieselbe Version tragen (docs/ENTWICKLUNG.md, Abschnitt 3).

Einzige Quelle ist `phasewake.version` in android/gradle.properties; jede `MARKETING_VERSION` im Xcode-Projekt
muss gleich lauten. Die Build-Nummer kommt aus der CI und wird hier nicht geprüft.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]


def main() -> int:
    props = (ROOT / "android/gradle.properties").read_text(encoding="utf-8")
    match = re.search(r"^phasewake\.version=(\S+)$", props, re.M)
    if not match:
        print("::error title=Version::phasewake.version fehlt in android/gradle.properties")
        return 1
    version = match.group(1)
    pbx = (ROOT / "ios/PhaseWake.xcodeproj/project.pbxproj").read_text(encoding="utf-8")
    others = sorted(set(re.findall(r"MARKETING_VERSION = ([^;]+);", pbx)) - {version})
    if others:
        print(f"::error title=Version::Xcode MARKETING_VERSION {', '.join(others)} statt {version}")
        return 1
    print(f"Version {version} auf allen Geräten")
    return 0


if __name__ == "__main__":
    sys.exit(main())
