# Testnächte

Nächte im Format `.night`, die Android (`android/core`) und Apple (`ios/PhaseWakeCore`) mit den Startwerten gleich bewerten müssen: dieselbe erste Weck-Epoche, derselbe Grund. Format und Regel: [docs/PHASENERKENNUNG.md](../../docs/PHASENERKENNUNG.md).

Die aktuellen Nächte sind synthetisch und decken je einen Regelfall ab. Echte, anonym exportierte Nächte kommen dazu, sobald es sie gibt.

## Sicherung

`backup-v1.pwbak.b64` ist eine verschlüsselte Sicherung (Base64), `backup-v1.password` ihr Passwort und `backup-v1.json`
der Klartext. Erzeugt mit `python3 .github/scripts/make-backup-vector.py` (Python «cryptography», feste Werte für Salz und
Nonce); `--check` prüft, ob die Dateien aktuell sind. Beide Kerne müssen die Datei entschlüsseln und denselben Inhalt lesen.
