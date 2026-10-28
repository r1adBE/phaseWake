# phaseWake – Konzept

Stand: 28.10.2026.

## Ziel

Ein Wecker, der in einem Fenster vor der Zielzeit wartet, bis die Uhr eine leichte Schlafphase erkennt, und dann am Handgelenk vibriert. Spätestens zur Zielzeit weckt er immer.

Nicht Ziel: ein vollständiges Schlaf-Staging (Tief/REM/Leicht) wie Samsung Health. phaseWake braucht nur eine Antwort: «Ist jetzt ein guter Moment zum Wecken?»

## Plattformen

| | Android | Apple |
|---|---|---|
| Handy | Android-App (`android/app/`), Kotlin, Compose | iPhone-App (`ios/`), Swift, SwiftUI |
| Uhr | Wear-OS-App (`android/wear/`), Galaxy Watch | watchOS-App (`ios/`), Apple Watch |
| Messung im Fenster | Foreground-Service (Typ `health`), Beschleunigung + Health Services | `WKExtendedRuntimeSession` Typ «Smart Alarm», CoreMotion + HealthKit |
| Wecken | Vibrator-API | `notifyUser(hapticType:)` |
| Sync | Wearable Data Layer | WatchConnectivity |

Apple stellt mit der Sitzungsart «Smart Alarm» genau dafür eine Schnittstelle bereit: Sie läuft im Hintergrund, lässt sich bis 36 Std. im Voraus planen, überwacht Puls und Bewegung und dauert höchstens 30 Min. Daher das Weckfenster-Maximum von 30 Min auf beiden Plattformen.

Die Galaxy Watch lässt sich nicht mit dem iPhone koppeln, die Apple Watch nicht mit Android. Es gibt also genau zwei Paare.

Die Phasenerkennung ist auf beiden Plattformen dieselbe Regel, je einmal in Kotlin und Swift geschrieben. Beide Umsetzungen laufen gegen dieselben Testnächte in `shared/testdata/` und müssen Epoche für Epoche gleich entscheiden.

## Rollen

| | Handy-App | Uhr-App |
|---|---|---|
| Wecker anlegen/ändern | ja | nur Ein/Aus |
| Überwachung im Fenster | nein | ja (Sensorik liegt hier) |
| Wecken | Ton + Vibration als Zweitwecker, standardmässig an, abschaltbar | Vibration, Pflicht |
| Verlauf anzeigen | ja | letzte Nacht |
| Daten | lokal (Android: DataStore, iOS: UserDefaults und Datei) | lokal |

Die Uhr funktioniert ohne Handy. Fällt die Verbindung weg, weckt sie trotzdem; das Handy zeigt dann nur den Verlauf nach.

## Ablauf einer Nacht

1. Nutzer stellt am Handy Zielzeit und Weckfenster ein (Standard 30 Min, Bereich 10–30 Min).
2. Der Wecker wird per Data Layer an die Uhr übertragen. Die Uhr setzt einen exakten Alarm (`AlarmManager`, `setAlarmClock`) auf *Zielzeit − Fenster*.
3. Fensterbeginn: Ein Foreground-Service (Typ `health`) startet, hält einen Partial-Wakelock und liest Sensoren.
4. Alle 30 s wird eine Epoche bewertet. Erkennt die Bewertung Leichtschlaf oder Aufwachen, löst die Uhr den Weckvorgang aus.
5. Ist die Zielzeit erreicht, ohne dass die Bewertung angeschlagen hat, weckt die Uhr trotzdem (Rückfall).
6. Weckvorgang: Vibrationsmuster in drei Stufen (sanft → deutlich → anhaltend), je 60 s; Abstellen durch Halten (1,2 s), Schlummern 5 Min (maximal 3×). Parallel bekommt das Handy ein Signal und kann Ton abspielen.
7. Die Uhr schreibt eine Zusammenfassung der Nacht (Weckzeitpunkt, Grund, Bewegungsverlauf im Fenster) und sendet sie ans Handy.

## Phasenerkennung

Im Fenster wird nur das gemessen, was für die Entscheidung nötig ist. Zwei Signale:

**Bewegung (Aktigraphie)** – Beschleunigungssensor mit Batching, 25 Hz. Pro Epoche (30 s) wird die Bewegungsenergie berechnet (Summe der Betragsänderungen oberhalb eines Rauschschwellwerts). Das ist die Standardmethode der Aktigraphie und erkennt Leichtschlaf zuverlässig: Im Tiefschlaf liegt der Wert nahe null, im Leichtschlaf und beim Drehen deutlich darüber.

**Puls** – Health Services `MeasureClient`, Herzfrequenz. Dient als zweite Stimme: steigender Puls gegenüber dem Mittel der ersten Fensterminuten spricht für leichten Schlaf oder beginnendes Aufwachen.

**Entscheidung** (einfach und erklärbar, keine Blackbox):

- `wach`: Bewegungsenergie in 2 von 3 aufeinanderfolgenden Epochen über dem Wach-Schwellwert → sofort wecken (Nutzer ist ohnehin wach).
- `leicht`: Bewegungsenergie über dem Leicht-Schwellwert in mindestens 2 der letzten 4 Epochen **und** Puls mindestens 5 % über dem Fenster-Basiswert → wecken.
- sonst: weiter warten.

Die Schwellwerte starten mit festen Werten und werden ab der dritten Nacht pro Nutzer kalibriert (80. Perzentil der Bewegung der letzten 14 Nächte). Die genaue, verbindliche Regel steht in [PHASENERKENNUNG.md](PHASENERKENNUNG.md). Keine Modelle, kein Training, keine Daten verlassen die Uhr.

In der ersten Fensterminute wird nicht entschieden (Basiswerte sammeln). Fehlt der Puls (Uhr sitzt locker), entscheidet nur die Bewegung.

## Energie

Ziel: unter 5 % Akku pro Nacht bei 30-Min-Fenster.

- Ausserhalb des Fensters läuft nichts, ausser nach «Gute Nacht»: dann nur Bewegung, stromsparend gebündelt, bis zum Einschlafen. Kein Hintergrund-Tracking.
- Beschleunigung mit Batching (`maxReportLatency` 10 s), Display bleibt aus.
- Puls nur im Fenster, über Health Services statt rohem Sensor.
- Wakelock nur, solange der Dienst misst oder weckt; nach «Gute Nacht» nur auf Uhren ohne aufweckenden Beschleunigungssensor (höchstens 16 Std.).

## Daten und Datenschutz

- Alles bleibt auf Uhr und Handy. Kein Konto, kein Server, keine Analytik, kein Internet-Recht im Manifest.
- Gespeichert werden Weckereinstellungen und pro Nacht: Zielzeit, Fenster, tatsächliche Weckzeit, Weckgrund, Bewegungskurve des Fensters (30-s-Werte). Verlauf nach 90 Nächten automatisch gekürzt, jederzeit löschbar.
- Apple: HealthKit-Lesezugriff Herzfrequenz, Bewegung über CoreMotion.
- Android-Berechtigungen Uhr: `BODY_SENSORS` bzw. `health.READ_HEART_RATE` (Puls), `BODY_SENSORS_BACKGROUND` bzw. `health.READ_HEALTH_DATA_IN_BACKGROUND` (Puls bei dunklem Display), `ACTIVITY_RECOGNITION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `USE_EXACT_ALARM` (ab Android 13) bzw. `SCHEDULE_EXACT_ALARM` (Android 12), `USE_FULL_SCREEN_INTENT`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `VIBRATE`, `POST_NOTIFICATIONS`. Handy: `USE_EXACT_ALARM` bzw. `SCHEDULE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `VIBRATE`, `health.READ_SLEEP` (optional). Keine Internet-Berechtigung.
- Datenschutzerklärung: Verantwortlicher Riad Work, c/o BENETI GmbH, Einschlagweg 67, 3400 Burgdorf; Kontakt riad.work@outlook.com.

## Technik

- Android: Kotlin, Jetpack Compose (Handy) und Wear Compose (Uhr), Material 3. Mindest-SDK 30 (Wear OS 3+, Android 11+), Ziel-SDK 36. Entwickelt auf der Galaxy Watch 8 Classic; andere Wear-OS-Uhren mit Herzfrequenzsensor sollten laufen.
- Apple: Swift, SwiftUI, iOS 17+ und watchOS 10+.
- Puls im Smart-Alarm-Fenster auf der Apple Watch: Wie dicht HealthKit dort Werte liefert, wird am Gerät geprüft. Kommen zu wenige, entscheidet wie auf Android bei fehlendem Puls die Bewegung allein.
- Sync Handy ↔ Uhr: Android über Wearable Data Layer (`DataClient` für Wecker, `MessageClient` für Ereignisse; einzige Google-Play-Services-Abhängigkeit, auf Galaxy Watches ohnehin vorhanden), Apple über WatchConnectivity.
- Keine weiteren Fremdabhängigkeiten.
- Tests: Phasenerkennung als reine Kotlin-Logik ohne Android-Abhängigkeit, mit aufgezeichneten Nächten als Testdaten. Abdeckung: Logik ≥ 90 %, Oberfläche ≥ 50 %.
- Sprachen: 32, Du-Form (Deutsch in Schweizer Schreibung). Android-Texte sind die Quelle, die Apple-Kataloge werden daraus erzeugt.

## Umfang v1

Alles in v1, nichts auf später verschoben:

- mehrere Wecker, je wiederholbar nach Wochentagen
- Weckfenster, Vibrationsweckung mit Schlummern, Handy-Zweitwecker
- Verlauf der letzten 90 Nächte mit Bewegungskurve, Kalibrierung
- Weckfenster-Vorschlag aus dem Verlauf
- Tile und Komplikation auf der Uhr
- Nacht-Check auf der Startseite, sobald ein Wecker ansteht: Uhr erreichbar und eingerichtet (Sensoren, Mitteilungen, Weckbildschirm), Mitteilungen und Vollbild-Wecker erlaubt (iPhone: AlarmKit), «Nicht stören» lässt Wecker durch, Wecker-Lautstärke nicht auf 0, exakte Wecker erlaubt (Android 12), Handy-Akku über 20 % oder am Laden
- Morgenbericht: Weckgrund, Datenqualität (Bewegung und Puls / nur Bewegung / Lücken) und Befinden 1–5 mit einem Tipp; Befinden im Verlauf, im Export und in den Trends
- Hinweis nach 3 Nächten in Folge ohne leichten Schlaf trotz 30-Min-Fenster: wahrscheinliche Ursache aus der Datenqualität (Lücken → Uhr abgelegt/am Laden, Puls fehlte → enger tragen, vollständig → ruhiger Schlaf, Wecken zur Zielzeit ist richtig). Das Fenster bleibt höchstens 30 Min.
- Trends im Verlauf: Ø Schlaf der letzten 7 Tage gegenüber der Vorwoche und Schwankung der Einschlafzeit (aus Health Connect bzw. Apple Health, je mind. 3 bzw. 5 Nächte), Ø Befinden; ohne genug Daten keine Aussage
- Sonnenaufgang auf dem Handy (Option pro Wecker, standardmässig aus): aus, beim Wecken (in 2 Min voll hell) oder ab Weckfenster (zur Zielzeit voll hell; weckt die Uhr früher, in 1 Min). Bildschirm von schwachem Dunkelorange zu warmem Weiss, Helligkeit von 1 % bis 100 %. Android; auf dem iPhone im Nachttisch-Modus (App bleibt nachts offen, Bildschirm schwarz bis zum Licht), weil iOS bei gesperrtem Bildschirm keinen eigenen Vollbildschirm erlaubt
- anonymer Export des Verlaufs (CSV)
- Schlafnotizen (28.10.2026): 8 feste und bis zu 5 eigene Notizen pro Abend; im Verlauf «Was deinen Schlaf beeinflusst» (ab je 3 Nächten mit und ohne, Unterschied im Befinden ab 0,5)
- Schlafziel (Standard 7½ Std.) mit Wochenstand aus Health Connect bzw. Apple Health, Bettzeit-Erinnerung 30 Min vorher (standardmässig aus)
- Einschlafklänge, in der App erzeugt (weisses, rosa, braunes Rauschen, Regen, Meer), Timer 15/30/60 Min, enden spätestens zum Weckfenster
- Einschlaf-Erkennung: Wecker wahlweise relativ zur Einschlafzeit (z. B. «nach 7½ Std.») statt zur Uhrzeit, «Gute Nacht» ab 16 Std. vor der Weckzeit – nur Galaxy Watch; die Smart-Alarm-Sitzung der Apple Watch dauert höchstens 30 Min und kann nicht die ganze Nacht messen

## Entscheide (28.10.2026)

- Aus der Marktanalyse (28.10.2026): Nacht-Check, Morgenbericht mit Befinden, Trends und ein freiwilliger Spenden-Link (GitHub Sponsors). Kein Abo, keine Werbung, kein Mikrofon, kein «KI-Coach».

- Sonnenaufgang (28.10.2026): Option pro Wecker, standardmässig aus; wählbar «beim Wecken» oder «ab Weckfenster».

- Weckfenster Standard 30 Min, einstellbar 10–30 Min. Morgens überwiegt Leicht- und REM-Schlaf, Tiefschlaf ist selten; 30 Min reichen fast immer für einen guten Moment. Ein längeres Fenster würde nur unnötig früh wecken.
- Handy-Zweitwecker standardmässig an.
- Schlummern 5 Min, maximal 3×.
- Verlauf 90 Nächte, Grundlage für die Kalibrierung.
- Anzeigename «Phase Wake» (Repo und Code: phaseWake).
- Android und Apple mit gleichem Funktionsumfang, je nativ (Kotlin bzw. Swift), wie bei cryptoChecker.
- Daten bleiben lokal. Wer helfen will, exportiert seine Nächte freiwillig als anonyme Datei (ohne Namen, Gerätekennung oder Standort) und schickt sie per GitHub-Issue oder E-Mail. Kein Server, keine Internet-Berechtigung.
- Gesundheitsdaten (28.10.2026): Samsung Health und Apple Health liefern Schlafphasen erst nach dem Aufwachen, also nicht für den Weckmoment. v1 liest sie optional nachträglich (Android über Health Connect, iPhone über HealthKit), um die eigene Erkennung zu kalibrieren und die ganze Nacht im Verlauf zu zeigen. Nur Lesen, einzeln erlaubt, bleibt auf dem Gerät. Herzschlag-Abstände (HRV) live auf der Galaxy Watch über das Samsung Health Sensor SDK erst nach Aufnahme ins Samsung-Partnerprogramm.
