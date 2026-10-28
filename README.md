<p align="center"><img src="docs/logo.svg" width="112" alt="Phase Wake Logo"></p>

<h1 align="center">Phase Wake</h1>

<p align="center">
Schlafphasenwecker für <b>Galaxy Watch</b> und <b>Apple Watch</b> – weckt dich im leichten Schlaf statt mitten aus dem Tiefschlaf.<br>
Open Source · ohne Konto · ohne Server · ohne Abo<br>
<sub>Stand: in Entwicklung, noch nicht in den Stores.</sub>
</p>

<p align="center">
<a href="https://github.com/r1adBE/phaseWake/actions/workflows/ci.yml"><img src="https://github.com/r1adBE/phaseWake/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/Lizenz-MIT-0B1A4A" alt="Lizenz MIT"></a>
</p>

<p align="center">
<img src="https://raw.githubusercontent.com/r1adBE/phaseWake/screenshots/app/HomeDark.png" width="230" alt="Startseite dunkel">
<img src="https://raw.githubusercontent.com/r1adBE/phaseWake/screenshots/app/HomeLight.png" width="230" alt="Startseite hell">
<img src="https://raw.githubusercontent.com/r1adBE/phaseWake/screenshots/app/RingPhone.png" width="230" alt="Wecken">
</p>

<p align="center"><a href="https://github.com/r1adBE/phaseWake/tree/screenshots">Alle Bildschirme ansehen →</a></p>

## So funktioniert es

Du stellst eine Zielzeit und ein Weckfenster (10–30 Minuten). Im Fenster misst die Uhr Bewegung und Puls in 30-Sekunden-Abschnitten. Sobald sie leichten Schlaf erkennt, weckt sie dich – sanft per Vibration, auf Wunsch mit Ton. Spätestens zur Zielzeit weckt sie immer.

- **Weckfenster** vor der Zielzeit, frei einstellbar
- **Einschlaf-Modus** (Galaxy Watch): «7½ Stunden schlafen» ab dem Einschlafen, nie später als die gewählte Zeit
- **Weckart** pro Wecker: Uhr mit Vibration oder Ton, Handy als Zweitwecker (aus, Vibration, Ton)
- **Sonnenaufgang** auf dem Handy (Option): Der Bildschirm wird von schwachem Orange zu hellem, warmem Licht – ab Weckfenster oder beim Wecken; auf dem iPhone im Nachttisch-Modus
- **Schlummern** 5 Minuten, höchstens 3×
- **Nacht-Check** vor dem Schlafen: Uhr verbunden, Wecker erlaubt, «Nicht stören», Akku – mit Knopf zum Beheben
- **Morgenbericht**: wann und warum geweckt wurde, worauf die Entscheidung beruhte, und ein Tipp, wie du dich fühlst
- **Trends**: Schlafdauer und Regelmässigkeit (aus Health Connect bzw. Apple Health) und dein Befinden im Wochenvergleich
- **Verlauf** der letzten 90 Nächte mit Bewegungskurve; optional Abgleich mit den Schlafphasen aus Health Connect bzw. Apple Health
- **Lernt mit**: Die Schwellen passen sich ab der dritten Nacht an dich an
- **Kachel und Komplikation** auf der Uhr
- **Hell, Dunkel, System** – und barrierefrei mit Bildschirmleser
- **32 Sprachen**

Die Erkennungsregel ist offen dokumentiert: [docs/PHASENERKENNUNG.md](docs/PHASENERKENNUNG.md).

## Datenschutz

Alles bleibt auf Handy und Uhr. Kein Konto, kein Server, keine Werbung, keine Auswertung. Wer helfen will, die Erkennung zu verbessern, kann seine Nächte freiwillig als anonyme CSV-Datei exportieren.

## Aufbau

| Ordner | Inhalt |
|---|---|
| `android/core` | Phasenerkennung und Logik, reines Kotlin, vollständig getestet |
| `android/shared` | Speicher und Abgleich Handy ↔ Uhr |
| `android/app` | Android-App (Jetpack Compose) |
| `android/wear` | Wear-OS-App für die Galaxy Watch (Compose for Wear OS) |
| `ios/PhaseWakeCore` | Dieselbe Logik in Swift, gleiche Tests |
| `ios/PhaseWake` | iPhone-App (SwiftUI) |
| `ios/PhaseWakeWatch` | Apple-Watch-App (SwiftUI, Smart-Alarm-Sitzung) |
| `shared/testdata` | Testnächte, die beide Plattformen identisch bewerten müssen |
| `docs` | Konzept, Erkennungsregel, Logo |

## Testen

Fertige Testversion für Android-Handy und Galaxy Watch: [Releases → test](https://github.com/r1adBE/phaseWake/releases/tag/test). Anleitung Schritt für Schritt: [docs/TESTEN.md](docs/TESTEN.md).

## Bauen

Android (JDK 21):

```sh
cd android
./gradlew :core:test :app:assembleDebug :wear:assembleDebug
```

Apple (macOS mit Xcode 26):

```sh
cd ios/PhaseWakeCore && swift test
open ../PhaseWake.xcodeproj
```

Die Texte der Apple-Apps entstehen aus den Android-Texten: `python3 .github/scripts/gen-xcstrings.py`.

## Unterstützen

Phase Wake ist gratis, ohne Werbung und ohne Abo. Wer die Entwicklung unterstützen möchte: [GitHub Sponsors](https://github.com/sponsors/r1adBE).

## Lizenz

[MIT](LICENSE) © r1adBE · Schrift [Manrope](licenses/Manrope-OFL.txt) unter SIL Open Font License 1.1
