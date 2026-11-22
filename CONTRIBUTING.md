# Mitarbeiten

Danke fürs Mithelfen. Die verbindlichen Regeln für Code, Tests und Versionen stehen in
[docs/ENTWICKLUNG.md](docs/ENTWICKLUNG.md); hier nur der Ablauf.

1. **Zuerst ein Issue:** Fehler mit Gerät, Version (aus «Über» oder der Diagnose) und Schritten; Wünsche mit dem
   Zweck. Sicherheitslücken nicht als Issue, sondern wie in [SECURITY.md](SECURITY.md).
2. **Zweig `aenderung/<thema>`** von `main`. Auf `main` wird nur vorgespult (kein Merge-Commit, kein Force-Push);
   verlangt sind die grünen Prüfungen «Android (Kern, Handy, Uhr)», «Apple-Kern (Swift)», «Apple-Apps (iPhone, Watch)»
   und «Geheimnisse (gitleaks)».
3. **Beide Plattformen im selben Zug:** Regeln liegen im Kern (`android/core`, `ios/PhaseWakeCore`) mit gleichen
   Namen und gleichen Testfällen; Testnächte und Vektoren in `shared/testdata` gelten für beide.
4. **Fehler zuerst als Test**, dann die Behebung. Neue Texte auf Englisch in `values/strings.xml` und in allen
   Sprachen; die Apple-Kataloge erzeugt `.github/scripts/gen-xcstrings.py`.
5. **Vor dem Push lokal prüfen** (im Ordner `android`): die Prüfskripte aus `.github/scripts` (Zeilen, Code-Grenzen,
   Doku-Kommentare, Übersetzungen, Versionen) und `./gradlew :core:test`. Ein Überblick über alles:
   `python3 .github/scripts/repo_audit.py . --no-run` im Repo-Ordner.
6. **Entscheide und Doku:** Was Verhalten ändert, bekommt eine Zeile in [docs/ENTSCHEIDE.md](docs/ENTSCHEIDE.md);
   Regeln der Erkennung stehen in [docs/PHASENERKENNUNG.md](docs/PHASENERKENNUNG.md), neue Datenarten in
   [docs/DATENSCHUTZ.md](docs/DATENSCHUTZ.md). Änderungen für Tester und Stores in [CHANGELOG.md](CHANGELOG.md).

Beiträge stehen unter der Lizenz des Projekts ([LICENSE](LICENSE)).
