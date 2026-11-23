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
   Neues kommt auf beiden Plattformen im selben Zug, nie «erst Android, Apple folgt»; was eine
   Plattform nicht kann, steht als Grenze in den Dokumenten.
4. **Einfach vor schlau.** Lieber eine klare Funktion mehr als eine Abstraktion, die nur einmal gebraucht wird.

## 2. Struktur und Grösse

| Regel | Grenze | Prüfung |
|---|---|---|
| Zeilen je Datei (Kotlin, Swift) | höchstens **400** | `.github/scripts/line_limit.py` |
| Zeichen je Zeile (Kotlin, Swift, auch Tests) | höchstens **120** | `.github/scripts/code_limits.py` |
| Zeilen je Funktion | höchstens **60**; Oberflächen (`@Composable`, `@ViewBuilder`, `some View`) höchstens **120**; auch Ausdruckskörper (`= run { … }`), `init`, Getter und berechnete Eigenschaften | `.github/scripts/code_limits.py` |
| Parameter je Funktion | höchstens **7** (sonst Datenklasse bzw. Struct mit gleichem Namen in Kotlin und Swift); Konstruktoren ausgenommen | `.github/scripts/code_limits.py` |
| Verschachtelung | höchstens **4** Ebenen | Review |
| Build-Nummern im Produktivcode | **0** | `.github/scripts/no_build_numbers.py` |
| `TODO`, `FIXME`, `XXX` im Produktivcode | **0** – offene Punkte gehören ins Issue bzw. in `docs/ENTSCHEIDE.md` | `.github/scripts/no_build_numbers.py` |
| Auskommentierter Code | 0 | Review |
| Warnungen (Kotlin, Swift, Lint, Xcode) | **0** | CI-Hinweise «Warnungen» |

- **Massstab:** Die Grenzen entsprechen den Standardregeln von detekt (Kotlin) und SwiftLint (Swift); die CI prüft
  sie bei jedem Lauf und kennt keine Ausnahmen. Die Prüfskripte haben Selbsttests (`.github/scripts/test_*.py`,
  Musterfälle); wer ein Skript ändert, ergänzt dort den Fall.
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
- Produktivcode liest die Build-Nummer (`versionCode`, `longVersionCode`, `CFBundleVersion`) nur in `BuildNumber`
  (android/shared, ios/Shared; für «Über», Diagnose und Nacht-Check, Entscheid 14.11.2026) und enthält keine
  Versions- oder Build-Literale. Die Versionsbezeichnung (`versionName`, `CFBundleShortVersionString`) zeigen nur «Über»
  bzw. die Einstellungsseite und die Diagnose.
- Kennung: Store-Version `com.phasewake.app`, Testversionen (Debug) von Handy und Uhr `com.phasewake.app.test`
  (gleich auf beiden, Pflicht für den Data Layer). Die Testversion installiert sich neben der Store-Version; eine
  ältere Testversion mit der Store-Kennung einmal deinstallieren (Daten vorher als `.pwbak` sichern). Im Code nie die
  Kennung ausschreiben, sondern `${applicationId}` bzw. `context.packageName`; `release_check.py` prüft die Kennung
  des Release-Builds. Namespace bleibt `com.phasewake.app`.
- **Exportierte Komponenten und Rechte:** `release_check.py` führt je App eine Positivliste
  (`EXPORTED`, `PERMISSIONS`) für das zusammengeführte Release-Manifest. Jede Abweichung (neu oder weggefallen,
  auch eingeschleppt von einer Bibliothek) macht die CI rot. Neues erst prüfen (sonst `tools:node="remove"`), dann
  die Liste und die Play-Erklärung (docs/PLAY.md) nachführen. Dieselbe Liste gilt für die Testversion
  (`release_check.py --debug`, CI nach dem Debug-Build): `src/debug/AndroidManifest.xml` entfernt dort die
  exportierte `PreviewActivity` aus ui-tooling.
- **Prüfsummen der Abhängigkeiten:** `android/gradle/verification-metadata.xml` hält die sha256
  jedes Artefakts (Bibliotheken, Plugins, Lint, AAPT2, Robolectric-Abbilder, Werkzeuge der Bildschirmfotos); Gradle
  bricht bei einem unbekannten oder veränderten Artefakt ab. Nach jedem Update in `libs.versions.toml` (auch aus einem
  Dependabot-PR) die Datei im Ordner `android` neu erzeugen, mit allen Aufgaben der Workflows:
  `./gradlew --no-configuration-cache --write-verification-metadata sha256 :core:test :core:jacocoTestReport
  :shared:testDebugUnitTest :shared:createDebugUnitTestCoverageReport :app:testDebugUnitTest
  :app:createDebugUnitTestCoverageReport :wear:testDebugUnitTest :wear:createDebugUnitTestCoverageReport
  :app:assembleDebug :wear:assembleDebug :app:assembleRelease :wear:assembleRelease :app:lintDebug :wear:lintDebug
  :app:bundleRelease :wear:bundleRelease :app:updateDebugScreenshotTest :wear:updateDebugScreenshotTest`, die neuen
  Einträge prüfen (nur die erwarteten Gruppen und Versionen) und mit dem Update einchecken. Quell- und Javadoc-Jars
  (Android Studio) sind ausgenommen.
- Verhalten hängt nie von der eigenen Version ab. Formate tragen ihre eigene Formatversion (z. B. `phasewake-alarms 1`)
  und lesen ältere Stände tolerant.

## 4. Tests und Abdeckung

| Bereich | Mindestabdeckung (Zeilen) | Prüfung |
|---|---|---|
| Kotlin-Kern `android/core` | **90 %** | JaCoCo + `.github/scripts/coverage_check.py`, CI |
| Swift-Kern `PhaseWakeCore` | **90 %** | `swift test --enable-code-coverage` + `.github/scripts/coverage_check.py`, CI |
| `android/shared` (Formate, Nachrichten) | **70 %** | JaCoCo + `.github/scripts/coverage_check.py`, CI |
| Zweitwecker am Handy (`android/app`, Paket `ring`), Wecken und Planen der Uhr (`android/wear`, Pakete `wake` und `schedule`) | **70 %** je Paket | JaCoCo + `.github/scripts/coverage_check.py --package`, CI |
| Handy-App `android/app` gesamt, Uhr-App `android/wear` gesamt | **45 / 54 %** (Messung 23.11.2026 minus 5 Punkte) | JaCoCo + `.github/scripts/coverage_check.py`, CI |
| Zweitwecker am iPhone (`ios/PhaseWake/BackupAlarm.swift`, `+Reschedule`, `+Plan`, `+Snooze` ohne die Karte, `+Transient`) | je Datei **65 / 65 / 50 / 80 / 80 %** | `-enableCodeCoverage YES`, `xccov` + `.github/scripts/xccov_check.py`, Job «Apple-Apps», CI |
| Wecken der Apple Watch (`ios/PhaseWakeWatch/Alarm/`: `NightRules`, `SessionRules`, `SmartAlarm+Upcoming`, `SmartAlarm`; Regeln `MeasuringRules`, `RingingRules`, `SessionRules+Phase`, `NightRules+Kept`) | je Datei **95 / 95 / 75 / 29 %** (erste Messung 23.11.2026 minus 5 Punkte), Regeln je **95 %** | `-enableCodeCoverage YES`, `xccov` + `.github/scripts/xccov_check.py`, Job «Apple-Apps», CI |
| übrige iPhone- und Watch-App (`ios/PhaseWakeTests`, `ios/PhaseWakeWatchTests`, App als Testwirt), auch `SmartAlarm+Measuring`, `+Ringing`, `+Session` (nur noch Aufrufe von watchOS; die Entscheidungen stehen in den Regeln oben) | keine (nur Tests) | `xcodebuild test` im Simulator, Job «Apple-Apps», CI |
| Bildschirme | jeder Bildschirm mit Vorschau im Screenshot-Test (hell/dunkel, Uhr rund); jede Abweichung über 0,1 % der Bildpunkte ist ein Fehler (darunter nur Rauschen der Kantenglättung, Entscheid 18.11.2026) | `validateDebugScreenshotTest`, Job «Android», CI; Referenzen rendert der Workflow «Bildschirmfotos» von Hand gestartet (Artefakt `referenzbilder`) |

- **Referenzbilder erneuern:** Workflow «Bildschirmfotos» auf dem Arbeitszweig von Hand starten (nur dann wird
  gerendert), Artefakt `referenzbilder` herunterladen und nach `android/{app,wear}/src/screenshotTestDebug/reference/`
  übernehmen (alte Dateien vorher löschen). Nach einem Push auf main legt der Workflow ohne Gradle die eingecheckten
  Referenzbilder in den Zweig «screenshots» (die CI hat sie gerade geprüft). Lokal gerenderte Bilder weichen in der Kantenglättung ab und taugen nicht als Referenz. Vorschauen
  zeigen feste Zeiten und feste Zufallswerte (Uhrzeit am Rand der Uhr, Rechenaufgabe), sonst weichen sie bei jedem
  Lauf ab.

- **Gleiche Fälle in beiden Kernen:** Ein Kern-Test in Kotlin hat sein Gegenstück in Swift mit denselben Eingaben
  und Erwartungen. Die Testnächte in `shared/testdata` müssen beide Kerne gleich bewerten.
- **Fehler zuerst als Test:** Wer einen Fehler behebt, schreibt zuerst den Test, der ihn zeigt.
- **Tests sind schnell und deterministisch:** keine echte Uhrzeit, kein Zufall ohne festen Startwert, kein Netz.
- Abdeckung darf nicht sinken; die Grenzen werden nur angehoben.
- **iPhone-Zweitwecker je Datei:** Eine Summe über alle fünf Dateien liesse den Wegfall der
  Tests einer kleinen Datei nicht auffallen. Die Schwellen liegen etwa 10 Punkte unter dem erwarteten Stand: Fällt
  eine Testgruppe weg (z. B. «Uhr hat übernommen» in `BackupAlarm.swift`, Schlummern oder Probewecker/Powernap),
  wird die CI rot. `+Plan` liegt tiefer, weil die Mitteilungs-Kette (vor iOS 26), `noteWatchState` (WCSession) und
  der Nacht-Check dort ohne Gerät nicht laufen. Nach dem ersten grünen Lauf auf den gemessenen Stand minus 5 Punkte
  anheben (Werte stehen als Hinweis im Lauf).
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
  Prüfung: `.github/scripts/doc_check.py` (Altlast als Zahl in `.github/quality/doc-baseline.txt`, darf nur sinken);
  zählt auch Mitglieder von `companion object` und `public extension`, nur `/**` bzw. `///` gilt als Doku.
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
- **Datenweg Handy ↔ Uhr (Android):** Inhalte mit persönlichen Daten nie als Datenpaket
  (`DataClient.putDataItem` – Google sichert Datenpakete zusätzlich in der Cloud), sondern über die Warteschlange
  `android/shared/…/Outbox.kt`: `enqueue(schlüssel, pfad, fähigkeit, text)` legt den Eintrag im privaten Ordner
  `outbox` ab (gleicher Schlüssel ersetzt) und sendet ihn als Nachricht (`DataLayer.PATH_MSG_…`, über 90 KB über
  `ChannelClient`), Inhalt «Kennung, Zeilenumbruch, Text». Der Empfänger verarbeitet idempotent und bestätigt erst danach
  (`WearSync.receive`, `DataLayer.PATH_ACK`); bis dahin sendet `flush` den Eintrag bei jedem Anlass erneut (Einreihen,
  App-Start, `onCapabilityChanged`, jede empfangene Nachricht). Den Stand der Gegenseite holt `requestSync`
  (`PATH_SYNC_REQUEST`) statt `getDataItems`; beim Prozessstart `requestSyncOnStart` (höchstens alle 30 Min,
  `SyncGate`), und eigene Zustände reiht der Start mit `enqueueIfChanged` nur ein, wenn sie nicht schon bestätigt sind
  (`AckedContent`). Datenpakete nur noch für Uhr-Status, «Verlauf löschen» und Abbruch von
  Probewecker/Powernap. Keinen früheren Weg für ältere Versionen (seit 10.11.2026): weder Pakete schreiben noch die
  einer älteren Gegenseite lesen; eine Uhr mit `DataLayer.PROTOCOL` < 2 meldet nur der Nacht-Check («Uhr-App veraltet»).
  Einmalige Umstellung beim ersten Start (`WearSync.migrateTransport`, auf Handy und Uhr): eigene alte Pakete löschen,
  Nächte und Aufzeichnungen der Uhr vorher einreihen; die Lese-Funktionen der alten Formate (`WearSync.readNight`,
  `RecordSync.readRecording`, `OnsetShift.read`) dienen nur ihr. Tests: `OutboxTest` (Robolectric, Senden, Bestätigen,
  Wiederholen, Ersetzen, Umstellung).
- **Grenze für Inhalte der Gegenseite:** Ein Kanal wird nie ohne Grenze gelesen
  (`PeerLimit.read`, höchstens `PeerLimit.MAX_BYTES` = 8 MB, sonst Abbruch mit Log); grössere Nachrichten verarbeitet
  `WearSync.receive` nicht. Die längste Aufzeichnung (12 Std., 1440 Epochen) hat rund 65 KB. Apple gleich:
  `SyncKeys.maxPeerBytes` in `PhoneConnectivity` (Texte der Uhr). Tests: `PeerLimitTest`, `PeerLimitTests`.
- **Diagnose-Bericht der Uhr (11.11.2026):** Ein voller Bericht (500 Einträge à 200 Zeichen, über 100 KB) passt in
  keine Nachricht. Galaxy Watch: `Outbox.sendOnce` (bis 90 KB Nachricht, sonst `ChannelClient`; das Handy liest den
  Kanal mit `WearSync.readChannel`, höchstens 8 MB, `PhoneListenerService.handleChannel`). Apple Watch: bis 48 KB in der
  Antwort auf `diagRequest`, sonst `transferFile` (`DiagTransfer`, Antwort `SyncKeys.diagFile`). Ohne Bericht nach 15 s
  protokolliert das Handy «watch report missing», die Uhr ein gescheitertes Senden mit «diag report not sent». Tests:
  `WatchReportTest`, `WatchDiagTest`, `DiagTransferTests`.
- **Absturzberichte:** kein Absturz-SDK in der App (Grundsatz 2). Nach dem Release kommen
  Abstürze und ANRs über die Systeme der Stores: Android in der Play Console unter «Android Vitals» (Abstürze und ANRs,
  Handy und Uhr getrennt), Apple in App Store Connect bzw. im Xcode Organizer (Abstürze, Energie, Hänger; nur von
  Nutzern, die das Teilen mit Entwicklern erlaubt haben). Dazu das lokale Diagnose-Protokoll der App, das Nutzer selbst
  freischalten und teilen (Einstellungen bzw. Uhr). Vorgehen siehe `docs/PLAY.md`, Abschnitt «Nach dem Release».
  Prozessenden stehen auch im Diagnose-Protokoll, als `ERROR`-Zeilen
  `exit <art> at … key=value` ohne Stacks: iPhone über MetricKit (`ExitReports`: crash mit Signal/Exception-Typ/Code,
  hang, cpu, disk; je Art höchstens 5 je Bericht, keiner doppelt), Android und Galaxy Watch über `ApplicationExitInfo`.
  Die Apple Watch hat kein MetricKit.
- **Keine Rückfälle für ältere App-Versionen zwischen Handy und Uhr (10.11.2026, Android und Apple):** «klingeln» und
  «stop» (sowie die Meldungen zur Weckaufgabe) ohne Sendezeit verwirft der Empfänger, ein «klingeln» der Uhr auch ohne
  Kennung oder Schlummer-Runden (`RingMessage.isCurrent`/`isCompleteRing`, iPhone `PhoneConnectivity`); «snooze» vom
  Handy bleibt ohne Inhalt. Kein Feld «Felder je Weckerzeile» mehr im Uhr-Status: Überspringen und Feiertage sind immer
  zu sehen. «Verlauf löschen» ohne Zeitpunkt löscht nichts. Eine veraltete Uhr-App meldet allein der Nacht-Check «Uhr-App
  veraltet» (Status bzw. Kontext ohne Verbindungs-Stand zählt als 0).

## 8. Ablauf

- **Commits:** klein, eine Sache, deutscher Betreff im Präsens ohne Punkt; Autor r1AD; keine KI-Zuschreibung.
- **Zweige:** Der einzige dauerhafte Zweig für Code ist `main`. Jede Änderung kommt auf einen kurzen Arbeitszweig
  (`aenderung/<thema>`), die CI läuft dort beim Push von selbst; erst mit grüner CI geht genau dieser
  Stand auf `main` (die Regel für `main` verlangt grüne Prüfungen, verbietet Löschen und Force-Push). Grössere Runden
  bekommen vor dem Merge ein unabhängiges Review (Mensch oder frischer Agent ohne Vorwissen). Die Datenzweige
  `feiertage`, `klaenge` und `screenshots` füllen Workflows. Workflows, die schreiben, haben zwei Jobs:
  Der erste baut mit Leserecht und `persist-credentials: false` und legt das Ergebnis als Artefakt
  ab, der zweite schreibt mit `contents: write` und führt keinen Code aus dem Repo oder aus Paketen aus.
- **Aufräumen:** Der Workflow «Aufräumen» (montags und von Hand) löscht Workflow-Läufe samt Artefakten und
  Protokollen, die älter als 1 Tag sind (es bleiben der neuste abgeschlossene Lauf je Workflow, alle Läufe für den
  Stand von `main` und «test» und laufende Läufe), Artefakte ohne behaltenen Lauf, Caches, die 7 Tage unbenutzt sind,
  Releases und Tags ausser «test» und Arbeitszweige, die vollständig in `main` stecken; von Hand lassen sich weitere
  Zweige angeben (gelöscht nur, wenn sie in `main` stecken oder seit über 7 Tagen ruhen).
- **CI robust:** Jeder Job hat eine Frist (`timeout-minutes`). Bekommt ein Apple-Job keinen macOS-Runner («not
  acquired by Runner»), startet der Workflow «CI wiederholen» (`ci-retry.yml`, ohne Checkout) nur die gescheiterten
  Jobs neu, bis zum 3. Versuch; scheitert daneben ein anderer Job, bleibt der Lauf rot. Die Watch-Tests installieren
  die App vorher im Simulator und wiederholen nur bei «failed to launch … Unknown application display identifier»
  einmal. Testversion und Store-Build verlangen einen grünen Push-Lauf der CI für genau ihren Commit. Die Warnungen
  stehen je Job vollständig in einem Hinweis (Zahl im Titel).
- **Definition of Done:** CI grün (Tests, Abdeckung, Grössen, Build-Nummern, Übersetzungen, 0 Warnungen, keine
  Geheimnisse – Job «Geheimnisse (gitleaks)», Ausnahmen für Testpasswörter in `.gitleaks.toml`),
  Bildschirmfotos aktuell, Dokumente nachgeführt, Entscheide eingetragen, Testversion gebaut.
