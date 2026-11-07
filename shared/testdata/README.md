# Testnächte

Nächte im Format `.night`, die Android (`android/core`) und Apple (`ios/PhaseWakeCore`) mit den Startwerten gleich bewerten müssen: dieselbe erste Weck-Epoche, derselbe Grund. Format und Regel: [docs/PHASENERKENNUNG.md](../../docs/PHASENERKENNUNG.md).

Die aktuellen Nächte sind synthetisch und decken je einen Regelfall ab; `noisy_watch_*` nutzen die Werte einer Uhr mit Grundrauschen rund 0.02 m/s² (Befund 07.11.2026, Regel 3a). Echte, ohne Namen exportierte Nächte kommen dazu, sobald es sie gibt.

## Sicherung

`backup-v1.pwbak.b64` ist eine verschlüsselte Sicherung (Base64) im alten Format «PWBK1» (210 000 Runden, wird nur noch
gelesen), `backup-v2.pwbak.b64` derselbe Inhalt im aktuellen Format «PWBK2» (600 000 Runden, wird geschrieben);
`backup-v1.password` ist das Passwort beider Dateien und `backup-v1.json` der Klartext. `backup-v1-nfc.*` und
`backup-v2-nfc.pwbak.b64` prüfen dasselbe mit einem Passwort in NFD. Erzeugt mit
`python3 .github/scripts/make-backup-vector.py` (Python «cryptography», feste Werte für Salz und Nonce); `--check` prüft,
ob die Dateien aktuell sind. Beide Kerne müssen alle Dateien entschlüsseln und denselben Inhalt lesen.
