<p align="center"><img src="docs/logo.svg" width="128" alt="phaseWake"></p>

# phaseWake

Schlafphasenwecker für Android mit Galaxy Watch (Wear OS) und für iPhone mit Apple Watch.

Die Watch misst in einem Weckfenster vor der Zielzeit Bewegung und Puls, erkennt eine Leichtschlafphase und weckt dann per Vibration am Handgelenk – statt mitten aus dem Tiefschlaf zur fixen Uhrzeit.

## Warum

Samsung Health trackt den Schlaf ausführlich, bietet aber keinen Smart-Wake-Alarm. Kostenlose Alternativen gibt es für die Galaxy Watch nicht. phaseWake schliesst diese Lücke – offen, ohne Konto, ohne Server, ohne Abo.

## Aufbau

| Ordner | Inhalt |
|---|---|
| `android/app/` | Android-App: Wecker einrichten, Weckfenster, Verlauf |
| `android/wear/` | Wear-OS-App: Sensorik, Phasenerkennung, Vibrationsweckung |
| `ios/` | iPhone-App mit Apple-Watch-App (SwiftUI) |
| `shared/testdata/` | Aufgezeichnete Nächte, die beide Plattformen gleich bewerten müssen |
| `docs/` | Logo, Konzept |

Entwickelt und getestet auf der Galaxy Watch 8 Classic.

## Grundsätze

- Alle Daten bleiben auf Handy und Uhr. Keine Cloud, kein Konto, keine Telemetrie.
- Keine Fremdabhängigkeiten über die Plattform-Bibliotheken hinaus (AndroidX, Wear Compose, Health Services; SwiftUI, HealthKit, CoreMotion).
- Die Uhr weckt auch dann, wenn das Handy nicht erreichbar ist.

## Lizenz

MIT – siehe [LICENSE](LICENSE).
