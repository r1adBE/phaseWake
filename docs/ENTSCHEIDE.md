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
| 28.10.2026 | Stoppen nur durch Halten | Handy, Galaxy Watch und Apple Watch: Wecker beenden nur durch 1,2 s Halten, auch im Schlummern; Schlummern bleibt ein Tipp. Mit Bildschirmleser genügt ein Doppeltipp. | Entschieden mit r1AD (Review): Ein Tipp im Halbschlaf soll nicht die Nacht beenden. |
| 28.10.2026 | Weckart auf Apple | Uhr «Vibration + Ton» und iPhone «Nur Vibration» ausgeblendet; iPhone: aus oder Ton. | Entschieden mit r1AD (Review): Die Apple Watch weckt nur per Haptik, AlarmKit klingelt immer mit Ton. |
| 28.10.2026 | Nacht-Check «Nicht stören» | Blockiert → «‹Nicht stören› blockiert Wecker»; ohne Sonderrecht nicht lesbar → «‹Nicht stören› ist an» mit Bitte um Prüfung, statt fälschlich grün. | Entschieden mit r1AD (Review). |
| 28.10.2026 | Uhr ohne Sensorrechte | Die Uhr weckt trotzdem zur Zielzeit (Vordergrund als Wecker-App, ohne Messung); der Nacht-Check am Handy meldet «Phase Wake auf der Uhr». | Ein Wecker muss wecken, auch wenn die Erkennung nicht laufen darf. |
| 28.10.2026 | Apple Watch: Rückfall | Lokale Mitteilung 1 Min nach der Zielzeit, beim Wecken entfernt; Planung nur bei offener App und ≤ 36 h, sonst Hinweis auf Uhr und iPhone. | Grenzen von watchOS; so weckt die Uhr auch, wenn die Sitzung scheitert. |
| 28.10.2026 | Pluralformen | Android `<plurals>`, Apple «name|kategorie» mit CLDR-Regeln in `Shared/Plural.swift`. | Eine Quelle, korrekte Grammatik in allen 32 Sprachen. |
| 28.10.2026 | Direct Boot (Uhr) | Nicht umgesetzt: Nach einem Neustart ohne Entsperren plant die Uhr erst nach dem ersten Entsperren; das Handy weckt als Rückfall. | Wear-Uhren werden meist sofort entsperrt; Speicher im Geräteschutz wäre ein grosser Umbau. |
| 28.10.2026 | Gleichzeitige Änderungen | Die Weckerliste gilt als Ganzes: Die zuletzt gespeicherte Liste gewinnt (Handy ↔ Uhr). | Einfach und vorhersehbar; gleichzeitiges Bearbeiten auf beiden Geräten ist selten. |
| 28.10.2026 | «Fenster kürzen» auf der Uhr | Nicht auf der Uhr, nur im Editor am Handy. | Kleiner Bildschirm; der Hinweis erscheint am Handy. |
| 28.10.2026 | Später geprüft | Swift 6 (strikte Nebenläufigkeit) und Trainings-Sitzung für dichtere Herzfrequenz auf der Apple Watch. | Erst nach Tests auf echten Geräten sinnvoll. |
