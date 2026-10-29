#!/usr/bin/env python3
"""Erzeugt die Testvektoren der verschlüsselten Sicherung (shared/testdata/backup-v1*.*).

Unabhängig von den Apps (Python «cryptography»): Beide Kerne (Kotlin und Swift) müssen diese Datei mit dem
Passwort entschlüsseln und denselben Inhalt lesen. Format siehe docs/KONZEPT.md, Abschnitt «Sichern».
Salz und Nonce sind hier fest, damit der Vektor reproduzierbar ist; die Apps nehmen echte Zufallswerte.
Der Schlüssel kommt aus dem Passwort in Unicode-NFC. Der zweite Vektor (backup-v1-nfc.*) hat ein Passwort mit «ä€»,
in der Passwort-Datei absichtlich als NFD geschrieben: Beide Kerne müssen vor der Ableitung normalisieren.

Aufruf: python3 .github/scripts/make-backup-vector.py [--check]
"""
import base64
import json
import pathlib
import sys
import unicodedata

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "shared/testdata"
MAGIC = b"PWBK1"
ITERATIONS = 210_000
PASSWORD = "Mondlicht-2026"
PASSWORD_NFC = "Mondlicht-\u00e4\u20ac-2026"
SALT = bytes(range(16))
NONCE = bytes(range(100, 112))

PAYLOAD = {
    "format": "phasewake-backup",
    "version": 1,
    "createdAt": 1793260800000,
    "alarms": "phasewake-alarms 1\n"
              "3f2a-77|6|30|MO,TU,WE,TH,FR|25|1|VIBRATE|1||raw%3Awake_birds|Arbeit+%7C+fr%C3%BCh|WINDOW|NONE|BOTH|1793338200000|1\n"
              "c|9|0||30|0|SOUND|0|450|||OFF|MATH|PHONE|0|0\n",
    "nights": [
        "phasewake-night 1\n3f2a-77|1793250000000|1793251800000|1793251200000|LIGHT_SLEEP|1|4\n0.012;58.0,;,0.3;61.5\n",
    ],
    "notes": "2026-10-28|CAFFEINE,custom:1b2c",
    "customNotes": "custom:1b2c|Spät gegessen «Pizza»",
    "routineItems": "PHONE|\ncustom:9a|Tee \"Kamille\"",
    "routineTicks": "2026-10-28|PHONE",
    "settings": {
        "sleepGoal": 480,
        "bedtimeReminder": True,
        "weeklyReview": False,
        "soundRecDefault": True,
        "theme": "DARK",
        "holidayCountry": "CH",
        "holidayRegion": "BE",
    },
}


def seal(plain: bytes, password: str) -> bytes:
    kdf = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=SALT, iterations=ITERATIONS)
    key = kdf.derive(unicodedata.normalize("NFC", password).encode("utf-8"))
    sealed = AESGCM(key).encrypt(NONCE, plain, None)  # Geheimtext | Tag (16), ohne zusätzliche Daten
    return MAGIC + SALT + NONCE + sealed


def build() -> dict[str, bytes]:
    plain = json.dumps(PAYLOAD, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    nfd = unicodedata.normalize("NFD", PASSWORD_NFC)
    return {
        "backup-v1.json": plain + b"\n",
        "backup-v1.password": PASSWORD.encode("utf-8") + b"\n",
        "backup-v1.pwbak.b64": base64.b64encode(seal(plain, PASSWORD)) + b"\n",
        "backup-v1-nfc.password": nfd.encode("utf-8") + b"\n",
        "backup-v1-nfc.pwbak.b64": base64.b64encode(seal(plain, nfd)) + b"\n",
    }


def main() -> int:
    files = build()
    if "--check" in sys.argv:
        bad = [name for name, content in files.items() if not (OUT / name).exists() or (OUT / name).read_bytes() != content]
        for name in bad:
            print(f"veraltet: shared/testdata/{name}")
        return 1 if bad else 0
    for name, content in files.items():
        (OUT / name).write_bytes(content)
        print(f"geschrieben: shared/testdata/{name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
