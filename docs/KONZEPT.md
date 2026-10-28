# phaseWake – Konzept

Stand: 28.10.2026, Entwurf zur Freigabe.

## Ziel

Ein Wecker, der in einem Fenster vor der Zielzeit wartet, bis die Uhr eine leichte Schlafphase erkennt, und dann am Handgelenk vibriert. Spätestens zur Zielzeit weckt er immer.

Nicht Ziel: ein vollständiges Schlaf-Staging (Tief/REM/Leicht) wie Samsung Health. phaseWake braucht nur eine Antwort: «Ist jetzt ein guter Moment zum Wecken?»

## Rollen

| | Phone-App (`app/`) | Watch-App (`wear/`) |
|---|---|---|
| Wecker anlegen/ändern | ja | nur Ein/Aus, Fenster kürzen |
| Überwachung im Fenster | nein | ja (Sensorik liegt hier) |
| Wecken | Ton + Vibration als Zweitwecker, abschaltbar | Vibration, Pflicht |
| Verlauf anzeigen | ja | letzte Nacht |
| Daten | lokal (DataStore) | lokal (DataStore) |

Die Uhr funktioniert ohne Handy. Fällt die Verbindung weg, weckt sie trotzdem; das Handy zeigt dann nur den Verlauf nach.

## Ablauf einer Nacht

1. Nutzer stellt am Handy Zielzeit und Weckfenster ein (Standard 30 Min, Bereich 10–60 Min).
2. Der Wecker wird per Data Layer an die Uhr übertragen. Die Uhr setzt einen exakten Alarm (`AlarmManager`, `setAlarmClock`) auf *Zielzeit − Fenster*.
3. Fensterbeginn: Ein Foreground-Service (Typ `health`) startet, hält einen Partial-Wakelock und liest Sensoren.
4. Alle 30 s wird eine Epoche bewertet. Erkennt die Bewertung Leichtschlaf oder Aufwachen, löst die Uhr den Weckvorgang aus.
5. Ist die Zielzeit erreicht, ohne dass die Bewertung angeschlagen hat, weckt die Uhr trotzdem (Rückfall).
6. Weckvorgang: Vibrationsmuster in drei Stufen (sanft → deutlich → anhaltend), je 60 s; Abstellen durch Tippen und Halten, Schlummern 5 Min (maximal 2×). Parallel bekommt das Handy ein Signal und kann Ton abspielen.
7. Die Uhr schreibt eine Zusammenfassung der Nacht (Weckzeitpunkt, Grund, Bewegungsverlauf im Fenster) und sendet sie ans Handy.

## Phasenerkennung

Im Fenster wird nur das gemessen, was für die Entscheidung nötig ist. Zwei Signale:

**Bewegung (Aktigraphie)** – Beschleunigungssensor mit Batching, 25 Hz. Pro Epoche (30 s) wird die Bewegungsenergie berechnet (Summe der Betragsänderungen oberhalb eines Rauschschwellwerts). Das ist die Standardmethode der Aktigraphie und erkennt Leichtschlaf zuverlässig: Im Tiefschlaf liegt der Wert nahe null, im Leichtschlaf und beim Drehen deutlich darüber.

**Puls** – Health Services `MeasureClient`, Herzfrequenz. Dient als zweite Stimme: steigender Puls gegenüber dem Mittel der ersten Fensterminuten spricht für leichten Schlaf oder beginnendes Aufwachen.

**Entscheidung** (einfach und erklärbar, keine Blackbox):

- `wach`: Bewegungsenergie in 2 von 3 aufeinanderfolgenden Epochen über dem Wach-Schwellwert → sofort wecken (Nutzer ist ohnehin wach).
- `leicht`: Bewegungsenergie über dem Leicht-Schwellwert in mindestens 2 der letzten 4 Epochen **und** Puls mindestens 5 % über dem Fenster-Basiswert → wecken.
- sonst: weiter warten.

Die Schwellwerte starten mit festen Werten und werden ab der dritten Nacht pro Nutzer kalibriert (Median der Bewegungsenergie der letzten Nächte als Basis). Keine Modelle, kein Training, keine Daten verlassen die Uhr.

In der ersten Fensterminute wird nicht entschieden (Basiswerte sammeln). Fehlt der Puls (Uhr sitzt locker), entscheidet nur die Bewegung.

## Energie

Ziel: unter 5 % Akku pro Nacht bei 30-Min-Fenster.

- Ausserhalb des Fensters läuft nichts; kein Hintergrund-Tracking.
- Beschleunigung mit Batching (`maxReportLatency` 10 s), Display bleibt aus.
- Puls nur im Fenster, über Health Services statt rohem Sensor.
- Wakelock nur solange der Service misst.

## Daten und Datenschutz

- Alles bleibt auf Uhr und Handy. Kein Konto, kein Server, keine Analytik, kein Internet-Recht im Manifest.
- Gespeichert werden Weckereinstellungen und pro Nacht: Zielzeit, Fenster, tatsächliche Weckzeit, Weckgrund, Bewegungskurve des Fensters (30-s-Werte). Verlauf nach 90 Nächten automatisch gekürzt, jederzeit löschbar.
- Berechtigungen Uhr: `BODY_SENSORS` (Puls), `ACTIVITY_RECOGNITION`, `FOREGROUND_SERVICE_HEALTH`, `SCHEDULE_EXACT_ALARM`, `WAKE_LOCK`, `VIBRATE`, `POST_NOTIFICATIONS`. Handy: `SCHEDULE_EXACT_ALARM`, `POST_NOTIFICATIONS`, `VIBRATE`.
- Datenschutzerklärung: Verantwortlicher Riad Work, c/o BENETI GmbH, Einschlagweg 67, 3400 Burgdorf; Kontakt riad.work@outlook.com.

## Technik

- Kotlin, Jetpack Compose (Handy) und Wear Compose (Uhr), Material 3.
- Mindest-SDK 30 (Wear OS 3+, Android 11+), Ziel-SDK 36. Entwickelt auf der Galaxy Watch 8 Classic; andere Wear-OS-Uhren mit Herzfrequenzsensor sollten laufen.
- Sync Handy ↔ Uhr über Wearable Data Layer (`DataClient` für Wecker, `MessageClient` für Ereignisse). Das ist die einzige Google-Play-Services-Abhängigkeit; sie ist auf Galaxy Watches ohnehin vorhanden.
- Keine weiteren Fremdabhängigkeiten.
- Tests: Phasenerkennung als reine Kotlin-Logik ohne Android-Abhängigkeit, mit aufgezeichneten Nächten als Testdaten. Abdeckung: Logik ≥ 90 %, Oberfläche ≥ 50 %.
- Sprachen v1: Deutsch (Schweizer Schreibung) und Englisch, Du-Form.

## Umfang v1

Drin: ein Wecker (wiederholbar nach Wochentagen), Weckfenster, Vibrationsweckung mit Schlummern, Handy-Zweitwecker, Verlauf der letzten Nächte mit Bewegungskurve, Kalibrierung.

Später: mehrere Wecker, Weckfenster-Vorschlag aus dem Verlauf, Tile und Komplikation auf der Uhr, Export des Verlaufs als CSV, Einschlaf-Erkennung (Fenster relativ zur Einschlafzeit statt zur Uhrzeit).

## Offene Entscheide

1. Weckfenster-Standard 30 Min – ok?
2. Handy-Zweitwecker standardmässig an oder aus?
3. Schlummern 5 Min, maximal 2× – ok?
4. Verlauf 90 Nächte behalten – ok?
5. Anzeigename «Phase Wake» in beide `strings.xml` eintragen?
