# Offene Punkte

Stand 30.10.2026, Phase Wake 16.2.2 (Build 41). Erledigtes wandert nach [ENTSCHEIDE.md](ENTSCHEIDE.md).

## Zum Entscheiden (r1AD)

1. **Texte, die noch leichten Schlaf behaupten**, alle 74 Sprachen. Vorschlag:
   - `reason_deadline` «Kein leichter Schlaf erkannt» → «Kein guter Moment im Fenster»
   - `history_light_share` «32 % im leichten Schlaf» → «32 % früher geweckt»
   - `hint_title` «3 Nächte ohne leichten Schlaf» → «3 Nächte ohne guten Moment»
   - `editor_advice_text` «…kam kein leichter Schlaf ins Fenster…» → «…kam kein guter Moment ins Fenster…»
   - Grenzfälle: `home_no_alarm_title` «Im richtigen Moment aufwachen», `health_rationale` «…den Weckmoment zu prüfen», README «Abgleich mit den Schlafphasen».
2. **Widget mit Details von heute** (Mo 02.11., 18:00): z. B. letzte Nacht mit Schlafdauer, geweckt um, Grund, Befinden.
3. **Schalter «Wecker auf dem Sperrbildschirm» in die Sicherung** aufnehmen (heute nicht gesichert, nach dem Wiederherstellen an).
3a. **Nacht-Check-Karte «Vor dem Schlafen prüfen»: das ✕ schliesst nichts** (gemeldet von r1AD, 30.10.). Es ist nur das Status-Symbol «nicht bereit», sieht aber wie ein Schliessen-Knopf aus. Vorschlag: Symbol durch ⚠ ersetzen (Android + iPhone) – oder die Karte bis zum nächsten Check wegtippbar machen.

## Audit 31.10.2026 (Weckpfad + Datenschutz) – Besprechung Mo 02.11., 18:00

Noch nicht geprüft und nicht freigegeben, nur festgehalten:
- Kritisch: iOS löscht den Zweitwecker, wenn die App in der Nachfrist (Zielzeit bis +2 Min.) startet; Android markiert einen zweiten Wecker als erledigt, während schon einer klingelt.
- Hoch: Einschlaf-Modus verschiebt nur die Uhr, nicht den Zweitwecker am Handy; Schlummern auf der Apple Watch beendet die Nacht auf dem iPhone; Schlummern am Android-Handy überlebt keinen Neustart; vor iOS 26 kein echter Wecker (Stummschalter); Zeitzonenwechsel auf iOS nur bei laufender App; Wear `setAlarmClock` ohne Absicherung; zwei Wecker während einer laufenden Nacht; Weckdienst der Uhr beim Klingeln nicht sticky; Einschlaf-Modus auf Apple nach Wiederherstellen ohne Wirkung.
- Mittel: Apple-Sitzung misst erste 5 Min. nicht; iOS-Zweitwecker ohne Schlummern; Force-Stop auf der Uhr; `backupRang` vor dem Klingeln; Alter von `PATH_STOP` ungeprüft; README «ab der dritten Nacht»; `versionCode` ohne CI = 1.
- Datenschutz: iOS-Verlauf und Diagnose landen in der iCloud-Sicherung; DATENSCHUTZ.md zum Inhalt der `.pwbak` und zum Diagnoseprotokoll ungenau; PBKDF2-Kommentar (OWASP) falsch; Sperrbildschirm-Mitteilung standardmässig an; `ACTIVITY_RECOGNITION` auf der Uhr unbenutzt.
- Parität/Doku: Health-Minuten runden (Swift) vs. abschneiden (Kotlin); Einschlafklänge-Startzeit; Epochen-Abschluss in PHASENERKENNUNG.md; ENTSCHEIDE.md ±45 vs. 30 Min.

## Vor dem Store-Start

4. **Play-Signierung:** eigener Upload-Schlüssel und Play App Signing; die Testversionen sind Debug-Builds mit dem öffentlichen Testschlüssel. Die CI prüft den Release-Build bereits (`release_check.py`).
5. **iPhone / Apple Watch:** TestFlight und App Store brauchen ein Apple-Entwicklerkonto.
6. **Übersetzungen** von Muttersprachlern prüfen lassen (Albanisch: r1AD). Schwedisch: «måltid» (Mahlzeit) wurde durch «önskad tid» ersetzt.

## Gerätetests (r1AD)

7. «Genau» klingelt nie früher; Nacht erscheint mit Messdaten im Verlauf.
8. Automatische Einschlaf-Messung (Galaxy Watch, 1 Std. vor dem Einschlafpunkt; Diagnose «auto onset …») und Untergrenze kurzer Nächte («Kurze Nacht – erst zur Zielzeit»).
9. Frühes Wecken nur noch mit gemessenem Pulsanstieg: seltener früh als vorher – beobachten, ob es zu selten wird.
10. Sperrbildschirm: leise Abend-Meldung sichtbar? Widget auf dem Sperrbildschirm auswählbar (hängt von Android-/One-UI-Version ab)?
11. Messwerte aus echten Nächten für die nächste Bewertung: verpasste Wecker (Ziel 0), Frühwecken in den ersten 5 Min., Befinden, Apple-Sitzung ohne abends geöffnete App.

## Bekannte Grenzen

12. Apple Watch misst nur die letzten 25 Min. vor der Zielzeit; Einschlafzeit und Untergrenze kurzer Nächte gibt es dort nicht (steht im iOS-Store-Text).
13. Ältere Uhr-Apps kennen «Genau» (Fenster 0) nicht und verwerfen solche Wecker; Handy und Uhr gemeinsam aktualisieren (der Zweitwecker am Handy klingelt trotzdem).

## Aufräumen

14. Branch `runde3-zwischenstand` auf GitHub löschen (aus der Sitzung nicht erlaubt).
