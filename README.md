<p align="center"><img src="docs/logo.svg" width="128" alt="phaseWake"></p>

# phaseWake

Schlafphasenwecker für Android mit Companion-App für die Samsung Galaxy Watch (Wear OS).

Die Watch misst in einem Weckfenster vor der Zielzeit Bewegung und Puls, erkennt eine Leichtschlafphase und weckt dann per Vibration am Handgelenk – statt mitten aus dem Tiefschlaf zur fixen Uhrzeit.

## Warum

Samsung Health trackt den Schlaf ausführlich, bietet aber keinen Smart-Wake-Alarm. Kostenlose Alternativen gibt es für die Galaxy Watch nicht. PhaseWake schliesst diese Lücke – offen, ohne Konto, ohne Server, ohne Abo.

## Aufbau

| Modul | Zweck |
|---|---|
| `app/` | Phone-App: Wecker einrichten, Weckfenster, Verlauf |
| `wear/` | Watch-Companion: Sensorik, Phasenerkennung, Vibrationsweckung |
| `docs/` | Logo, Konzept |

Entwickelt und getestet auf der Galaxy Watch 8 Classic.

## Grundsätze

- Alle Daten bleiben auf Handy und Uhr. Keine Cloud, kein Konto, keine Telemetrie.
- Keine Fremdabhängigkeiten über AndroidX / Wear Compose / Health Services hinaus.
- Die Uhr weckt auch dann, wenn das Handy nicht erreichbar ist.

## Lizenz

MIT – siehe [LICENSE](LICENSE).
