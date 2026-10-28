# Entscheide ohne Rückfrage

Hier steht, was während der Arbeit ohne Rückfrage entschieden wurde (jeweils die empfohlene Variante). Wer anders will: Zeile ändern, Code folgt.

| Datum | Thema | Entscheid | Grund |
|---|---|---|---|
| 28.10.2026 | Einschlaf-Modus auf Apple | Im iPhone-Editor ausgeblendet; auf der Apple Watch gilt immer die eingestellte Zeit. | Die Smart-Alarm-Sitzung von watchOS läuft höchstens 30 Min und kann nicht ab «Gute Nacht» messen. Ein Schalter ohne Wirkung wäre irreführend. |
| 28.10.2026 | Texte der Apple-Apps | Aus den Android-Texten erzeugt (`.github/scripts/gen-xcstrings.py`), CI prüft, dass sie aktuell sind. | Eine Quelle für 32 Sprachen, keine doppelte Pflege. |
| 28.10.2026 | Berechtigungstexte Apple | Englisch als Basis, übersetzt in `InfoPlist.xcstrings`. | Entwicklungssprache des Projekts ist Englisch, wie bei Android. |
| 28.10.2026 | App-Icon Apple | Gleiches Motiv wie Android, Nachthimmel-Verlauf randlos; auf der Uhr leicht verkleinert, damit Welle und Punkt im Kreis bleiben. | watchOS schneidet das Icon rund zu. |
| 28.10.2026 | Mindestversion Apple | iOS 17 / watchOS 10; Zweitwecker über AlarmKit ab iOS 26, darunter Mitteilung mit Ton. | Deckt fast alle aktiven iPhones ab. |
| 28.10.2026 | Nacht-Check ersetzt Warnkarten | Die bisherigen Karten «Berechtigung fehlt» und «Nicht stören» sind jetzt Teil des Nacht-Checks. | Eine Karte statt mehrerer; zeigt auch, wenn alles stimmt. |
| 28.10.2026 | Morgenbericht | Gilt 12 Stunden nach dem Wecken; danach heisst die Karte wieder «Letzte Nacht». Befinden-Frage nur in dieser Zeit. | Abends nach dem Befinden am Morgen zu fragen, wäre ungenau. |
| 28.10.2026 | Trends-Schwellen | Wochenwerte ab 3 Nächten, Schwankung der Einschlafzeit ab 5 Nächten in 14 Tagen; Hinweis auf regelmässige Schlafzeiten ab ± 45 Min. | Lieber nichts sagen als etwas Zufälliges. |
| 28.10.2026 | Apple-eigene Texte | In `ios/Strings`, gleiches Format wie Android, gleiche Prüfung, in die Kataloge erzeugt. | Eine Werkzeugkette für alle Texte. |
| 28.10.2026 | Kein Fenster über 30 Min | Nach 3 Nächten ohne leichten Schlaf nur ein Hinweis zur Ursache, kein längeres Fenster. | Entschieden mit r1AD: gleiche Grenze wie Apple Watch, in den letzten 30 Min gibt es fast immer leichten Schlaf. |
