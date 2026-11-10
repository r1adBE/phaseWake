# Entwicklungsregeln

Verbindlich für allen Code in diesem Repository (Kotlin, Swift, Skripte) – für Menschen und für KI-Agenten.
Was die CI prüft, steht jeweils dabei. Eine Regel, die die CI nicht prüfen kann, prüft das Review.

## 1. Grundsätze

1. **Ein Wecker muss wecken.** Jede Änderung am Weckablauf (Planen, Messen, Klingeln, Schlummern, Stoppen,
   Zweitwecker, Synchronisierung) braucht einen Test für den Normalfall und für den Ausfall (Uhr weg, Prozess
   beendet, Neustart, Zeitzone, Recht entzogen). Im Zweifel klingelt es lieber einmal zu viel als gar nicht.
2. **Daten bleiben auf dem Gerät.** Keine Internet-Berechtigung, keine Analyse-, Werbe- oder Absturz-Dienste,
   keine Konten. Neue Datenarten stehen vor dem Merge in `docs/DATENSCHUTZ.md`.
3. **Gleiches Verhalten auf Android und Apple.** Regeln (Erkennung, Planung, Formate, Auswertungen) liegen im
   Kern: `android/core` (Kotlin) und `ios/PhaseWakeCore` (Swift), mit gleichen Namen, gleicher Bedeutung und
   gleichen Testfällen. Plattform-Code zeigt an und ruft auf, entscheidet aber nichts, was der Kern entscheiden kann.
   Neues kommt auf beiden Plattformen im selben Zug, nie «erst Android, Apple folgt» (r1AD 10.11.2026); was eine
   Plattform nicht kann, steht als Grenze in den Dokumenten.
4. **Einfach vor schlau.** Lieber eine klare Funktion mehr als eine Abstraktion, die nur einmal gebraucht wird.

## 2. Struktur und Grösse

| Regel | Grenze | Prüfung |
|---|---|---|
| Zeilen je Datei (Kotlin, Swift) | höchstens **400** | `.github/scripts/line_limit.py` |
| Zeilen je Funktion | höchstens **60** (Compose-Bildschirme: 120) | Review |
| Parameter je Funktion | höchstens **7** (sonst Datenklasse) | Review |
| Verschachtelung | höchstens **4** Ebenen | Review |
| Build-Nummern im Produktivcode | **0** | `.github/scripts/no_build_numbers.py` |
| `TODO`, `FIXME`, `XXX` im Produktivcode | **0** – offene Punkte gehören ins Issue bzw. in `docs/ENTSCHEIDE.md` | `.github/scripts/no_build_numbers.py` |
| Auskommentierter Code | 0 | Review |
| Warnungen (Kotlin, Swift, Lint, Xcode) | **0** | CI-Hinweise «Warnungen» |

- **Altlasten:** Dateien, die beim Einführen der Grenze grösser waren, stehen in `.github/quality/line-limit-baseline.txt`
  mit ihrer damaligen Länge. Sie dürfen nur schrumpfen; die Liste wird nur kürzer. Neue Dateien halten die Grenze immer.
- **Pakete nach Aufgabe**, nicht nach Technik: `ring/`, `schedule/`, `sync/`, `sounds/`, `ui/home/` … Eine Datei
  enthält eine Hauptsache (einen Bildschirm, einen Dienst, eine Regel).
- **Abhängigkeiten nur nach innen:** UI → Dienste/Repositories → Kern. Der Kern kennt weder Android noch iOS.
- **Keine neuen Fremdbibliotheken** ohne Eintrag in `docs/ENTSCHEIDE.md` (Grund, Lizenz, Grösse) und Lizenzhinweis
  in README und App.

## 3. Build-Nummern und Versionen

- Version und Build-Nummer kommen **nur** aus dem Build. Die Version (aktuell 16.2.2) steht einmal in
  `android/gradle.properties` (`phasewake.version`) und gleich in Xcode (`MARKETING_VERSION`); `version_check.py`
  prüft das. Die Build-Nummer ist die Laufnummer der CI (`PHASEWAKE_BUILD`) und steigt mit jedem Build; die Uhr-App
  bekommt 1 000 000 dazu, weil Google Play für Handy und Uhr verschiedene Nummern verlangt. Store-Uploads nur aus dem
  Workflow «Store-Build» (eigene Laufnummer, signiert mit dem Upload-Schlüssel; docs/PLAY.md).
- Produktivcode liest keine Build-Nummer (`versionCode`, `longVersionCode`, `CFBundleVersion`) und enthält keine
  Versions- oder Build-Literale. Die Versionsbezeichnung (`versionName`, `CFBundleShortVersionString`) darf nur die
  Einstellungsseite anzeigen.
- Kennung: Store-Version `com.phasewake.app`, Testversionen (Debug) von Handy und Uhr `com.phasewake.app.test`
  (gleich auf beiden, Pflicht für den Data Layer). Die Testversion installiert sich neben der Store-Version; eine
  ältere Testversion mit der Store-Kennung einmal deinstallieren (Daten vorher als `.pwbak` sichern). Im Code nie die
  Kennung ausschreiben, sondern `${applicationId}` bzw. `context.packageName`; `release_check.py` prüft die Kennung
  des Release-Builds. Namespace bleibt `com.phasewake.app`.
- Verhalten hängt nie von der eigenen Version ab. Formate tragen ihre eigene Formatversion (z. B. `phasewake-alarms 1`)
  und lesen ältere Stände tolerant.

## 4. Tests und Abdeckung

| Bereich | Mindestabdeckung (Zeilen) | Prüfung |
|---|---|---|
| Kotlin-Kern `android/core` | **90 %** | JaCoCo + `.github/scripts/coverage_check.py`, CI |
| Swift-Kern `PhaseWakeCore` | **90 %** | `swift test --enable-code-coverage` + `.github/scripts/coverage_check.py`, CI |
| `android/shared` (Formate, Nachrichten) | **70 %** | JaCoCo + `.github/scripts/coverage_check.py`, CI |
| Zweitwecker am Handy (`android/app`, Paket `ring`), Wecken und Planen der Uhr (`android/wear`, Pakete `wake` und `schedule`) | **70 %** je Paket | JaCoCo + `.github/scripts/coverage_check.py --package`, CI |
| übrige Handy-App `android/app`, Uhr-App `android/wear` | keine (nur Information) | JaCoCo + `.github/scripts/coverage_check.py`, CI |
| iPhone- und Watch-App (`ios/PhaseWakeTests`, `ios/PhaseWakeWatchTests`, App als Testwirt) | keine (nur Tests) | `xcodebuild test` im Simulator, Job «Apple-Apps», CI |
| Bildschirme | jeder Bildschirm mit Vorschau im Screenshot-Test (hell/dunkel, Uhr rund) | Workflow «Bildschirmfotos» |

- **Gleiche Fälle in beiden Kernen:** Ein Kern-Test in Kotlin hat sein Gegenstück in Swift mit denselben Eingaben
  und Erwartungen. Die Testnächte in `shared/testdata` müssen beide Kerne gleich bewerten.
- **Fehler zuerst als Test:** Wer einen Fehler behebt, schreibt zuerst den Test, der ihn zeigt.
- **Tests sind schnell und deterministisch:** keine echte Uhrzeit, kein Zufall ohne festen Startwert, kein Netz.
- Abdeckung darf nicht sinken; die Grenzen werden nur angehoben.
- **Wear-Dienste unter Robolectric:** Ihre Aufgaben (Data Layer, Nachrichten) enden dort nie. Tests ersetzen darum
  die Wege dorthin (`OfflineApp`, `BackupManager.restore(…, resync)`, `PhoneListenerService.handleQueued`), statt auf
  eine Antwort zu warten.
- **Robolectric-Abbilder:** `android/gradle/robolectric.gradle.kts` hält je Robolectric-Version die passenden
  `android-all-instrumented`-Abbilder fest (`robolectricImagesByVersion`). Nach einem Update von `robolectric` in
  `libs.versions.toml` bricht Gradle beim Konfigurieren ab, bis die neue Version mit ihren Abbildern (aus
  `DefaultSdkProvider` dieser Version, für die SDKs der Tests) eingetragen ist.

## 5. Kommentare und Dokumentation

- **Sprache:** Kommentare und Dokumentation auf Deutsch (Schweizer Schreibung, kein «ß»), Bezeichner auf Englisch.
- **Jede öffentliche Deklaration im Kern** (Klasse, Funktion, Eigenschaft) hat einen KDoc- bzw. DocC-Kommentar:
  was sie tut, Einheiten (Minuten, Millisekunden, dBFS), Grenzwerte und Sonderfälle (`null`, leer, Zeitumstellung).
  Prüfung: `.github/scripts/doc_check.py` (Altlast als Zahl in `.github/quality/doc-baseline.txt`, darf nur sinken).
- **Kommentare erklären das Warum**, nicht das Was: Plattformgrenzen, Entscheide, Zahlen mit Herkunft
  («30 Min: Höchstdauer der Smart-Alarm-Sitzung von watchOS»). Ein Kommentar, der nur den Code wiederholt, fällt weg.
- **Konstanten statt Zahlen:** Jede fachliche Zahl hat einen Namen und einen Kommentar zur Herkunft.
- **Dokumente:** `docs/KONZEPT.md` (was und warum), `docs/PHASENERKENNUNG.md` (Erkennungsregel, verbindlich),
  `docs/ENTSCHEIDE.md` (jeder Entscheid mit Datum und Grund), `docs/DATENSCHUTZ.md`, `docs/TESTEN.md`,
  diese Datei. Eine Änderung, die eines davon unwahr macht, ändert es im selben Commit.

## 6. Texte und Barrierefreiheit

- Keine fest eingebauten Texte in Oberflächen; alle Texte in `strings.xml` (Android ist die Quelle, Apple-Kataloge
  werden erzeugt). Jede neue Zeichenkette in allen 74 Sprachen, Du-Form. Prüfung: `check-translations.py`,
  `gen-xcstrings.py --check`.
- Mengen mit Pluralformen, Platzhalter mit Position (`%1$s`).
- Jedes Bedienelement hat eine Beschreibung für Bildschirmleser; Ziele mindestens 48 dp bzw. 44 pt;
  Textkontrast mindestens 4,5 : 1 (WCAG AA); «Animationen entfernen» bzw. «Bewegung reduzieren» wird beachtet.
- **Farbe nie allein** (WCAG 1.4.1, Farbfehlsichtigkeit): Jede Information, die eine Farbe trägt, hat zusätzlich
  Form, Symbol, Muster oder Text (z. B. Weckgrund im Diagramm: Kreis, Dreieck, Quadrat, Raute). Bedienelemente und
  Grafiken mindestens 3 : 1 Kontrast (WCAG 1.4.11). Neue Farben werden für Deuteranopie, Protanopie und Tritanopie
  simuliert; iOS beachtet «Ohne Farbe unterscheiden».
- Keine Gesundheitsversprechen in Texten und Store-Einträgen.

## 7. Fehlerbehandlung und Nebenläufigkeit

- Kein stilles Verschlucken: Jeder abgefangene Fehler wird geloggt (ohne persönliche Daten) oder bewusst begründet.
- Nichts Langsames auf dem Hauptthread (Datei, Datenbank, Audio-Dekodierung, Modell laden).
- Kotlin: Coroutines mit klarem Scope; Swift: `@MainActor` für UI-Zustand, `Sendable` korrekt, keine erzwungenen
  Entpackungen (`!`) ausser bei Konstanten, die der Compiler nicht prüfen kann (mit Kommentar).
- **Datenweg Handy ↔ Uhr (Android, Audit C2, 10.11.2026):** Inhalte mit persönlichen Daten nie als Datenpaket
  (`DataClient.putDataItem` – Google sichert Datenpakete zusätzlich in der Cloud), sondern über die Warteschlange
  `android/shared/…/Outbox.kt`: `enqueue(schlüssel, pfad, fähigkeit, text)` legt den Eintrag im privaten Ordner
  `outbox` ab (gleicher Schlüssel ersetzt) und sendet ihn als Nachricht (`DataLayer.PATH_MSG_…`, über 90 KB über
  `ChannelClient`), Inhalt «Kennung, Zeilenumbruch, Text». Der Empfänger verarbeitet idempotent und bestätigt erst danach
  (`WearSync.receive`, `DataLayer.PATH_ACK`); bis dahin sendet `flush` den Eintrag bei jedem Anlass erneut (Einreihen,
  App-Start, `onCapabilityChanged`, jede empfangene Nachricht). Den Stand der Gegenseite holt `requestSync`
  (`PATH_SYNC_REQUEST`) statt `getDataItems`. Datenpakete nur noch für Uhr-Status, «Verlauf löschen» und Abbruch von
  Probewecker/Powernap. Keinen früheren Weg für ältere Versionen (seit 10.11.2026): weder Pakete schreiben noch die
  einer älteren Gegenseite lesen; eine Uhr mit `DataLayer.PROTOCOL` < 2 meldet nur der Nacht-Check («Uhr-App veraltet»).
  Einmalige Umstellung beim ersten Start (`WearSync.migrateTransport`, auf Handy und Uhr): eigene alte Pakete löschen,
  Nächte und Aufzeichnungen der Uhr vorher einreihen; die Lese-Funktionen der alten Formate (`WearSync.readNight`,
  `RecordSync.readRecording`, `OnsetShift.read`) dienen nur ihr. Tests: `OutboxTest` (Robolectric, Senden, Bestätigen,
  Wiederholen, Ersetzen, Umstellung).

## 8. Ablauf

- **Commits:** klein, eine Sache, deutscher Betreff im Präsens ohne Punkt; Autor r1AD; keine KI-Zuschreibung.
- **Zweige:** Der einzige dauerhafte Zweig für Code ist `main`. Jede Änderung kommt auf einen kurzen Arbeitszweig
  (`aenderung/<thema>`), die CI läuft dort beim Push von selbst; erst mit grüner CI geht genau dieser
  Stand auf `main` (die Regel für `main` verlangt grüne Prüfungen, verbietet Löschen und Force-Push). Grössere Runden
  bekommen vor dem Merge ein unabhängiges Review (Mensch oder frischer Agent ohne Vorwissen). Die Datenzweige
  `feiertage`, `klaenge` und `screenshots` füllen Workflows.
- **Aufräumen:** Der Workflow «Aufräumen» (montags und von Hand) löscht Arbeitszweige, die vollständig in `main`
  stecken, abgeschlossene Workflow-Läufe, die älter als 30 Tage sind, und nicht grüne Läufe (rot, abgebrochen,
  übersprungen) schon nach 7 Tagen; von Hand lassen sich weitere Zweige angeben (gelöscht nur, wenn sie in `main`
  stecken oder seit über 7 Tagen ruhen).
- **Definition of Done:** CI grün (Tests, Abdeckung, Grössen, Build-Nummern, Übersetzungen, 0 Warnungen),
  Bildschirmfotos aktuell, Dokumente nachgeführt, Entscheide eingetragen, Testversion gebaut.
