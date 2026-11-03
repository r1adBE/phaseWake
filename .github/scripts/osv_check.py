#!/usr/bin/env python3
"""Prüft die Release-Abhängigkeiten von Handy und Uhr auf bekannte Schwachstellen (OSV-Datenbank).

Aufruf aus android/ (in der CI nach dem Build): osv_check.py
Liest mit Gradle die aufgelösten Bibliotheken von `releaseRuntimeClasspath` beider Apps und fragt sie
gesammelt bei https://api.osv.dev ab (Ökosystem Maven). Fehler, wenn eine Bibliothek eine bekannte
Schwachstelle hat, wenn Gradle Bibliotheken nicht auflösen kann («FAILED») oder wenn die Abfrage nach drei
Versuchen scheitert: Eine Prüfung, die still durchlässt, wäre keine Prüfung.
"""
import json
import re
import subprocess
import sys
import time
import urllib.request

MODULES = ("app", "wear")
# «+--- gruppe:name:1.0 -> 1.2 (*)»: aufgelöste Version ist die nach dem Pfeil.
LINE = re.compile(r"[+\\]--- ([\w.\-]+):([\w.\-]+):([\w.\-+]+)(?: -> ([\w.\-+]+))?")
API = "https://api.osv.dev/v1/querybatch"
BATCH = 500
ATTEMPTS = 3


class CheckError(Exception):
    """Die Prüfung konnte nicht vollständig laufen."""


def dependencies(module):
    out = subprocess.run(
        ["./gradlew", "-q", f":{module}:dependencies", "--configuration", "releaseRuntimeClasspath"],
        capture_output=True, text=True, check=True,
    ).stdout
    # Nicht aufgelöste Bibliotheken fehlen sonst still in der Liste.
    failed = [line.strip() for line in out.splitlines() if "FAILED" in line]
    if failed:
        raise CheckError(f"{module}: nicht aufgelöst: {'; '.join(failed[:5])}")
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
        results = post(body)
        if len(results) != len(chunk):
            raise CheckError(f"OSV lieferte {len(results)} statt {len(chunk)} Antworten")
        for (g, n, v), result in zip(chunk, results):
            ids = [vuln["id"] for vuln in result.get("vulns", [])]
            if ids:
                vulnerable[f"{g}:{n}:{v}"] = ids
    return vulnerable


def post(body):
    """Fragt OSV ab; bei Netz- oder Serverfehlern (z. B. 429, 5xx) bis zu [ATTEMPTS]-mal mit Pause."""
    request = urllib.request.Request(API, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    for attempt in range(1, ATTEMPTS + 1):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)["results"]
        except (OSError, ValueError, KeyError) as e:
            if attempt == ATTEMPTS:
                raise CheckError(f"OSV-Abfrage nach {ATTEMPTS} Versuchen gescheitert: {e}") from e
            time.sleep(10 * attempt)


def main():
    try:
        packages = set()
        for module in MODULES:
            packages |= dependencies(module)
        if not packages:
            raise CheckError("keine Release-Bibliotheken gefunden (Gradle-Ausgabe geändert?)")
        vulnerable = query(packages)
    except (CheckError, subprocess.CalledProcessError) as e:
        print(f"::error title=Schwachstellen::Prüfung nicht vollständig: {e}")
        return 1
    for coordinate, ids in sorted(vulnerable.items()):
        print(f"::error title=Schwachstellen::{coordinate}: {', '.join(ids)}")
    if not vulnerable:
        print(f"::notice title=Schwachstellen::{len(packages)} Release-Bibliotheken (Handy, Uhr) ohne bekannte Schwachstelle (OSV)")
    return 1 if vulnerable else 0


if __name__ == "__main__":
    sys.exit(main())
