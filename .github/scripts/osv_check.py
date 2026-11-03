#!/usr/bin/env python3
"""Prüft die Release-Abhängigkeiten von Handy und Uhr auf bekannte Schwachstellen (OSV-Datenbank).

Aufruf aus android/ (in der CI nach dem Build): osv_check.py
Liest mit Gradle die aufgelösten Bibliotheken von `releaseRuntimeClasspath` beider Apps und fragt sie
gesammelt bei https://api.osv.dev ab (Ökosystem Maven). Fehler, wenn eine Bibliothek eine bekannte
Schwachstelle hat; ohne Netz eine Warnung, kein Abbruch (die Prüfung darf den Build nicht vom Netz abhängig machen).
"""
import json
import re
import subprocess
import sys
import urllib.request

MODULES = ("app", "wear")
# «+--- gruppe:name:1.0 -> 1.2 (*)»: aufgelöste Version ist die nach dem Pfeil.
LINE = re.compile(r"[+\\]--- ([\w.\-]+):([\w.\-]+):([\w.\-+]+)(?: -> ([\w.\-+]+))?")
API = "https://api.osv.dev/v1/querybatch"
BATCH = 500


def dependencies(module):
    out = subprocess.run(
        ["./gradlew", "-q", f":{module}:dependencies", "--configuration", "releaseRuntimeClasspath"],
        capture_output=True, text=True, check=True,
    ).stdout
    found = set()
    for match in LINE.finditer(out):
        group, name, version, resolved = match.groups()
        found.add((group, name, resolved or version))
    return found


def query(packages):
    vulnerable = {}
    items = sorted(packages)
    for start in range(0, len(items), BATCH):
        chunk = items[start:start + BATCH]
        body = {"queries": [{"package": {"ecosystem": "Maven", "name": f"{g}:{n}"}, "version": v} for g, n, v in chunk]}
        request = urllib.request.Request(API, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=60) as response:
            results = json.load(response)["results"]
        for (g, n, v), result in zip(chunk, results):
            ids = [vuln["id"] for vuln in result.get("vulns", [])]
            if ids:
                vulnerable[f"{g}:{n}:{v}"] = ids
    return vulnerable


def main():
    packages = set()
    for module in MODULES:
        packages |= dependencies(module)
    try:
        vulnerable = query(packages)
    except OSError as e:
        print(f"::warning title=Schwachstellen::OSV nicht erreichbar ({e}); Prüfung übersprungen")
        return 0
    for coordinate, ids in sorted(vulnerable.items()):
        print(f"::error title=Schwachstellen::{coordinate}: {', '.join(ids)}")
    if not vulnerable:
        print(f"::notice title=Schwachstellen::{len(packages)} Release-Bibliotheken (Handy, Uhr) ohne bekannte Schwachstelle (OSV)")
    return 1 if vulnerable else 0


if __name__ == "__main__":
    sys.exit(main())
