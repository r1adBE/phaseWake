# Entscheide ohne Rückfrage

Hier steht, was während der Arbeit ohne Rückfrage entschieden wurde (jeweils die empfohlene Variante). Wer anders will: Zeile ändern, Code folgt.

| Datum | Thema | Entscheid | Grund |
|---|---|---|---|
| 28.10.2026 | Einschlaf-Modus auf Apple | Im iPhone-Editor ausgeblendet; auf der Apple Watch gilt immer die eingestellte Zeit. | Die Smart-Alarm-Sitzung von watchOS läuft höchstens 30 Min und kann nicht ab «Gute Nacht» messen. Ein Schalter ohne Wirkung wäre irreführend. |
| 28.10.2026 | Texte der Apple-Apps | Aus den Android-Texten erzeugt (`.github/scripts/gen-xcstrings.py`), CI prüft, dass sie aktuell sind. | Eine Quelle für 32 Sprachen, keine doppelte Pflege. |
| 28.10.2026 | Berechtigungstexte Apple | Englisch als Basis, übersetzt in `InfoPlist.xcstrings`. | Entwicklungssprache des Projekts ist Englisch, wie bei Android. |
| 28.10.2026 | App-Icon Apple | Gleiches Motiv wie Android, Nachthimmel-Verlauf randlos; auf der Uhr leicht verkleinert, damit Welle und Punkt im Kreis bleiben. | watchOS schneidet das Icon rund zu. |
| 28.10.2026 | Mindestversion Apple | iOS 17 / watchOS 10; Zweitwecker über AlarmKit ab iOS 26, darunter Mitteilung mit Ton. | Deckt fast alle aktiven iPhones ab. |
