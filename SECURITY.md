# Sicherheit

Eine Sicherheitslücke bitte **nicht** als öffentliches Issue melden, sondern per E-Mail an
**riad.work@outlook.com** (Betreff «Phase Wake Sicherheit»). Hilfreich sind: betroffene App (Handy, Uhr, iPhone,
Apple Watch), Version aus «Über» bzw. der Diagnose, Schritte zum Nachstellen und was ein Angreifer damit erreicht.

Geprüft und behoben wird auf dem aktuellen Stand von `main`; ältere Testversionen bekommen keine eigenen Korrekturen.
Nach der Behebung steht die Meldung (auf Wunsch mit Namen) in `CHANGELOG.md`.

Phase Wake hat kein Konto, keinen Server und kein Internet-Recht. Besonders interessant sind darum: Daten anderer
Apps oder Geräte, die die App erreichen können (exportierte Komponenten, Data Layer, WatchConnectivity), die
verschlüsselte Sicherung (`.pwbak`) und alles, was einen Wecker still ausfallen lässt.

---

# Security

Please do **not** report a vulnerability as a public issue; send an e-mail to **riad.work@outlook.com** (subject
"Phase Wake security") instead. Helpful: affected app (phone, watch, iPhone, Apple Watch), version from "About" or the
diagnostics, steps to reproduce and what an attacker gains.

Fixes are made on the current state of `main`; older test versions do not get separate fixes. Once fixed, the report is
credited in `CHANGELOG.md` (with your name if you like).

Phase Wake has no account, no server and no internet permission. Of particular interest are therefore: data that other
apps or devices can reach (exported components, Data Layer, WatchConnectivity), the encrypted backup (`.pwbak`) and
anything that makes an alarm fail silently.
