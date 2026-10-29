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
| Wecker anlegen/ändern | ja | Uhrzeit, Weckfenster, Wochentage, Ein/Aus, Nächstes Mal überspringen, Löschen |
| Überwachung im Fenster | nein | ja (Sensorik liegt hier) |
| Wecken | Ton + Vibration als Zweitwecker, standardmässig an, abschaltbar | Vibration, Pflicht |
| Verlauf anzeigen | ja | letzte Nacht |
| Daten | lokal (Android: DataStore, iOS: UserDefaults und Datei) | lokal |

Die Uhr funktioniert ohne Handy. Fällt die Verbindung weg, weckt sie trotzdem; das Handy zeigt dann nur den Verlauf nach.

Uhr-App eigenständig (29.10.2026): Auf der Uhr lassen sich Wecker anlegen («+ Neuer Wecker», höchstens 8), ändern (Zeile antippen; der Schalter schaltet weiterhin nur ein/aus) und löschen (mit Rückfrage). Der Editor der Uhr zeigt Uhrzeit, Weckfenster («Genau · 10 · 20 · 30», seit Audit PW-03), Wochentage (ohne Tag: einmal), Ein/Aus und «Nächstes Mal überspringen» (wiederholte Wecker); Krone bzw. Drehrahmen blättern und verstellen. Alles Übrige (Sonnenaufgang, Weckton, Handy-Wecker, Weckaufgabe, Schlafdauer, Name) bleibt beim Ändern unverändert; neue Wecker bekommen dieselben Vorgaben wie am Handy (Android: 7:00, Werktage, Morgenvögel; iPhone: jetzige Uhrzeit, Werktage). Gespeichert wird lokal, neu geplant und ans Handy geschickt; es gilt wie überall die zuletzt gespeicherte Liste. Die Wear-OS-App ist im Play Store als eigenständig markiert (`com.google.android.wearable.standalone`): Messen und Wecken gehen ohne Handy, das Handy bleibt optionaler Begleiter. Sonnenaufgang, Weckton, Notizen und Auswertungen gibt es nur am Handy.

## Ablauf einer Nacht

1. Nutzer stellt am Handy oder an der Uhr Zielzeit und Weckfenster ein (Standard 30 Min, Bereich 10–30 Min, oder «Genau»: kein frühes Wecken, die Uhr misst trotzdem 30 Min für den Verlauf).
2. Der Wecker wird per Data Layer an die Gegenseite übertragen. Die Uhr setzt einen exakten Alarm (`AlarmManager`, `setAlarmClock`) auf *Zielzeit − Fenster*.
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
- Android-Berechtigungen Uhr: `BODY_SENSORS` bzw. `health.READ_HEART_RATE` (Puls), `BODY_SENSORS_BACKGROUND` bzw. `health.READ_HEALTH_DATA_IN_BACKGROUND` (Puls bei dunklem Display), `ACTIVITY_RECOGNITION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `USE_EXACT_ALARM` (ab Android 13) bzw. `SCHEDULE_EXACT_ALARM` (Android 12), `USE_FULL_SCREEN_INTENT`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `VIBRATE`, `POST_NOTIFICATIONS`. Handy: `USE_EXACT_ALARM` bzw. `SCHEDULE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `VIBRATE`, `health.READ_SLEEP` (optional), `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (Einschlafklänge), `RECORD_AUDIO` und `FOREGROUND_SERVICE_MICROPHONE` (nur für «Geräusche aufnehmen», erst beim Einschalten erfragt), `BLUETOOTH_CONNECT` bzw. bis Android 11 `BLUETOOTH` (nur für «Als Begleit-App verbinden», erst dann erfragt). Keine Internet-Berechtigung.
- Geräusche in der Nacht (optional, standardmässig aus): nur kurze Ausschnitte (höchstens 10 s, höchstens 40 pro Nacht) im App-Ordner des Handys, nach 7 Tagen gelöscht; nicht in Sicherungen, nicht im Export, nie übertragen.
- Datenschutzerklärung: Verantwortlicher Riad Work, c/o BENETI GmbH, Einschlagweg 67, 3400 Burgdorf; Kontakt riad.work@outlook.com.

## Technik

- Android: Kotlin, Jetpack Compose (Handy) und Wear Compose (Uhr), Material 3. Mindest-SDK 30 (Wear OS 3+, Android 11+), Ziel-SDK 36. Entwickelt auf der Galaxy Watch 8 Classic; andere Wear-OS-Uhren mit Herzfrequenzsensor sollten laufen.
- Apple: Swift, SwiftUI, iOS 17+ und watchOS 10+.
- Puls im Smart-Alarm-Fenster auf der Apple Watch: Wie dicht HealthKit dort Werte liefert, wird am Gerät geprüft. Kommen zu wenige, entscheidet wie auf Android bei fehlendem Puls die Bewegung allein.
- Sync Handy ↔ Uhr: Android über Wearable Data Layer (`DataClient` für Wecker, `MessageClient` für Ereignisse; einzige Google-Play-Services-Abhängigkeit, auf Galaxy Watches ohnehin vorhanden), Apple über WatchConnectivity.
- Weitere Fremdabhängigkeit nur für «Geräusche aufnehmen» auf Android: MediaPipe Tasks Audio (`com.google.mediapipe:tasks-audio`) mit dem Modell YAMNet (`yamnet.tflite` in den Assets), beide Apache-2.0, laufen ganz auf dem Gerät. Fehlt das Modell, gelten alle Ausschnitte als «anderes Geräusch». Auf dem iPhone die eingebaute SoundAnalysis.
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
- Sonnenaufgang (Option pro Wecker, standardmässig aus): aus, beim Wecken (in 2 Min voll hell) oder ab Weckfenster (zur Zielzeit voll hell; weckt die Uhr früher, in 1 Min). Bildschirm von schwachem Dunkelorange zu warmem Weiss, Helligkeit von 1 % bis 100 %. Android: wählbar auf Handy (Standard), Galaxy Watch oder beiden; auf der Uhr ab Fenster als Vollbild (Tippen schliesst, die Messung läuft weiter), beim Wecken als Hintergrund des Weckbildschirms. Apple: nur iPhone, im Nachttisch-Modus (App bleibt nachts offen, Bildschirm schwarz bis zum Licht), weil iOS bei gesperrtem Bildschirm keinen eigenen Vollbildschirm erlaubt und die Apple Watch ihr Display in der Weck-Sitzung nicht zuverlässig anhält
- anonymer Export des Verlaufs (CSV)
- Schlafnotizen (28.10.2026): 8 feste und bis zu 5 eigene Notizen pro Abend; im Verlauf «Was deinen Schlaf beeinflusst» (ab je 3 Nächten mit und ohne, Unterschied im Befinden ab 0,5)
- Schlafziel (Standard 7½ Std.) mit Wochenstand aus Health Connect bzw. Apple Health, Bettzeit-Erinnerung 30 Min vorher (standardmässig aus)
- Einschlafklänge, Timer 15/30/60 Min, enden spätestens zum Weckfenster (3 s Einblenden, 30 s Ausblenden). In der App erzeugt: weisses, rosa, braunes Rauschen, Ventilator (braunes Rauschen durch einen Tiefpass, dazu leises Brummen 50/100 Hz) und Einschlaf-Wellen (Stereo: links 200 Hz, rechts 202 Hz, je etwa −30 dBFS, mit sehr leisem rosa Rauschen; Hinweis «Mit Kopfhörern hören.», keine Gesundheitsversprechen). Aufnahmen (CC0, nahtlose Schleifen, 44,1 kHz Stereo, etwa −26 dBFS RMS), einmal dekodiert und ohne Lücke wiederholt (Android MediaCodec → AudioTrack, iPhone AVAudioFile → derselbe Quellknoten):
  - Regen: «Rain (loopable)» von Ylmir, https://opengameart.org/content/rain-loopable
  - Meer: «Sea and river wave sounds» (Vistula) von RandomMind, https://opengameart.org/content/sea-and-river-wave-sounds
  - Wald bei Nacht (Grillen): «Crickets ambient noise» von Wolfgang_, https://opengameart.org/content/crickets-ambient-noise-loopable
  - Kaminfeuer: «Fireplace sound loop» von PagDev, https://opengameart.org/content/fireplace-sound-loop
  - Bach: «30 CC0 SFX loops» (water_flowing) von rubberduck, https://opengameart.org/content/30-cc0-sfx-loops
- Wecktöne am Handy (je 28 s, 8 s Einblenden, in Schleife, Wecker-Kanal mit dem sanften Lauterwerden): Morgenvögel (aus «Ambient bird sounds» von isaiah658, CC0, https://opengameart.org/content/ambient-bird-sounds), Glockenspiel, Sanfter Gong, Harfe (synthetisch) oder ein Klingelton des Systems. Neue Wecker: Morgenvögel; bestehende behalten ihren Ton. Gespeichert im Feld `sound` als «raw:wake_birds» usw., sonst die URI des Systemtons (leer = Standard). Antippen spielt kurz an. iPhone: dieselben vier Töne über AlarmKit (`AlertConfiguration.AlertSound.named("wake_birds.caf")`, Datei im App-Bundle, unter 30 s) bzw. als Ton der Mitteilung; ohne Wahl der Standardton
- Geräusche in der Nacht (28.10.2026, Handy, Option pro Nacht, standardmässig aus): Schnarchen und Sprechen erkennen (Pegel über Grundrauschen + 12 dB, mind. 0,5 s), kurze Ausschnitte (2 s Vorlauf, höchstens 10 s, höchstens einer je 30 s und 40 pro Nacht, bei mehr die lautesten), Einordnung auf dem Gerät (Android YAMNet, iPhone SoundAnalysis). Läuft bis 30 Min nach der Zielzeit oder bis zum Stoppen; im Morgenbericht und im Verlauf «Möglicherweise Schnarchen: 12 Min (8 Ausschnitte) · Sprechen: 2 Ausschnitte» mit Abspielen und Löschen. Nach 7 Tagen gelöscht
- Gewohnheiten (28.10.2026): «Regelmässig ins Bett: N von 7 Abenden» (± 30 Min um den Median von 14 Nächten, nur mit Gesundheitsdaten), Serien «N Nächte in Folge Schlafziel erreicht» und längste Serie in 90 Tagen (keine Punkte, keine Abzeichen), Wochenrückblick am Sonntag um 18:00 (Mitteilung, standardmässig aus; als Karte im Verlauf am Sonntag und Montag), Abendroutine als eigene Checkliste (höchstens 8 Punkte, Vorschläge «Handy weg», «Licht dimmen», «Wasser bereit») mit «Abendroutine: Ø 2,5 von 3» in den Trends ab 3 Abenden
- Weckaufgabe (28.10.2026, Option pro Wecker, Standard Halten): «Rechenaufgabe» – 3 Additionen zweistelliger Zahlen am Handy, falsche Antwort gibt eine neue Aufgabe; Schlummern geht ohne Aufgabe, der Ton wird beim Tippen auf 50 % gesenkt (nach 10 s ohne Eingabe wieder voll). Die Galaxy Watch zeigt «Aufgabe am Handy lösen oder 5 s halten» erst, wenn das Handy bestätigt, dass es die Aufgabe zeigt; sonst, beim Schlummern und sobald die Bestätigung ausbleibt, gilt auf der Uhr Halten (nie ein Wecker, der sich nicht beenden lässt; Einzelheiten in ENTSCHEIDE.md). iPhone: nicht wählbar (der Stopp-Knopf von AlarmKit lässt sich nicht ersetzen), Apple Watch wie Halten
- Einschlaf-Erkennung: Wecker wahlweise relativ zur Einschlafzeit (z. B. «nach 7½ Std.») statt zur Uhrzeit, «Gute Nacht» ab 16 Std. vor der Weckzeit – nur Galaxy Watch; die Smart-Alarm-Sitzung der Apple Watch dauert höchstens 30 Min und kann nicht die ganze Nacht messen
- Runde 3 (29.10.2026): Probewecker «In 2 Minuten testen» (Einstellungen und Nacht-Check; Uhr führt den echten Ablauf ohne Schlummern, Ergebnis mit ✓/✗ und passender Lösung; nie im Verlauf); Ersteinrichtung in 5 Schritten (überspringbar, «Einrichtung wiederholen»); «Nächstes Mal überspringen» pro Wecker (Handy-Menü, Uhr-Editor); «An Feiertagen nicht wecken» mit Land und Region (Daten «holidays», MIT; Gemeinde-Feiertage fehlen); Powernap 10–90 Min (letzte 5 bzw. 3 Min als Fenster, nicht gespeichert); Widget «Nächster Wecker» (Android und iPhone); verschlüsselte Sicherung `.pwbak` mit Passwort; Gerätesperre vor «Verlauf löschen» und «Wiederherstellen»; versteckte Diagnose (7× Versionszeile) mit Protokoll von Handy und Uhr

## Entscheide (28.10.2026)

- Aus der Marktanalyse (28.10.2026): Nacht-Check, Morgenbericht mit Befinden, Trends und ein freiwilliger Spenden-Link (GitHub Sponsors). Kein Abo, keine Werbung, kein «KI-Coach». Mikrofon nur optional für «Geräusche aufnehmen» (28.10.2026): standardmässig aus, alles bleibt auf dem Handy.

- Sonnenaufgang (28.10.2026): Option pro Wecker, standardmässig aus; wählbar «beim Wecken» oder «ab Weckfenster».

- Ultraschall-Schlafmessung (28.10.2026): nicht umgesetzt.

- Weckfenster Standard 30 Min, einstellbar 10–30 Min oder «Genau» (zur Zielzeit, nie früher; Audit PW-03). Morgens überwiegt Leicht- und REM-Schlaf, Tiefschlaf ist selten; 30 Min reichen fast immer für einen guten Moment. Ein längeres Fenster würde nur unnötig früh wecken.
- Kurze Nacht (Audit PW-04, nur Galaxy Watch): unter Schlafziel − 60 Min Schlaf kein frühes Wecken; die Einschlafzeit misst die Uhr automatisch ab 60 Min vor der Bettzeit (Regel: PHASENERKENNUNG.md, Abschnitt 8).
- Handy-Zweitwecker standardmässig an.
- Schlummern 5 Min, maximal 3×.
- Verlauf 90 Nächte, Grundlage für die Kalibrierung.
- Anzeigename «Phase Wake» (Repo und Code: phaseWake).
- Android und Apple mit gleichem Funktionsumfang, je nativ (Kotlin bzw. Swift), wie bei cryptoChecker.
- Daten bleiben lokal. Wer helfen will, exportiert seine Nächte freiwillig als anonyme Datei (ohne Namen, Gerätekennung oder Standort) und schickt sie per GitHub-Issue oder E-Mail. Kein Server, keine Internet-Berechtigung.
- Gesundheitsdaten (28.10.2026): Samsung Health und Apple Health liefern Schlafphasen erst nach dem Aufwachen, also nicht für den Weckmoment. v1 liest sie optional nachträglich (Android über Health Connect, iPhone über HealthKit), um den Weckmoment damit abzugleichen und die ganze Nacht im Verlauf zu zeigen (die Schwellen der Erkennung stammen nur aus den eigenen Bewegungswerten, siehe PHASENERKENNUNG.md, Abschnitt 4). Nur Lesen, einzeln erlaubt, bleibt auf dem Gerät. Verlauf mit Nächten aus den Gesundheitsdaten (29.10.2026): jede Schlafsitzung der letzten 90 Tage erscheint (Nickerchen nur, wenn kürzer als 3 Std. und ganz zwischen 09:00 und 19:00; kurzer Schlaf in der Nacht bleibt eine Nacht) als eigene Nacht mit Herz-Symbol, «Ohne Phase-Wake-Wecker · aus Samsung Health», Zeiten, Dauern, Phasen, Befinden 1–5 (nur bei Phase Wake gespeichert, je Nachtdatum, zählt in Diagrammen und Trends) und Notizen; je Nachtdatum eine, eine eigene Nacht am selben Nachtdatum oder überschneidend hat Vorrang (Kern `HealthHistory`). Live-Spiegel der Quelle, nie gespeichert oder gesichert; «Verlauf löschen» blendet Fremd-Nächte bis zu diesem Zeitpunkt aus. Herzschlag-Abstände (HRV) live auf der Galaxy Watch über das Samsung Health Sensor SDK erst nach Aufnahme ins Samsung-Partnerprogramm.
