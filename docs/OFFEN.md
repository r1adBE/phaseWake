# Offene Punkte

Stand 02.11.2026, Phase Wake 16.2.2 (Build 42). Erledigtes wandert nach [ENTSCHEIDE.md](ENTSCHEIDE.md).

## Erledigt am 02.11.2026 (Details in ENTSCHEIDE.md)

- Entschieden mit r1AD: ehrliche Texte statt «leichter Schlaf» (`reason_deadline`, `history_light_share`, `hint_title`, `editor_advice_text`), Warnung «Zweitwecker vor iOS 26» im Nacht-Check, Hinweis im iPhone-Editor zum Einschlaf-Modus, ⚠ statt ✕ in der Nacht-Check-Karte, Widget «Details von heute».
- Audit 31.10.: K1 (Nachfrist), K2 (zwei Wecker am Handy), N1 («stop» der Uhr), H2 (Schlummern am Handy nach Neustart), H4 (Klingeln der Galaxy Watch nach Prozessende), H5/M6 (exakte Alarme der Uhr), H6 (zweiter Wecker während einer Nacht), Mitteilung «Nächster Wecker» privat, PBKDF2-Kommentar, Rundung und Einschlafklänge (Parität), iOS-Verlauf, Notizen und Diagnose ausserhalb der iCloud-Sicherung, DATENSCHUTZ.md (Inhalt der `.pwbak`, Diagnoseprotokoll, iCloud, Sperrbildschirm).

## Noch offen aus dem Audit 31.10.

1. **iOS: Einstellungen in der iCloud-Sicherung.** Wecker und Einstellungen (z. B. Schlafziel) liegen in `UserDefaults` und gehen mit; so steht es jetzt auch in DATENSCHUTZ.md. Offen, ob sie ebenfalls ausgenommen werden sollen.
2. **iOS vor 26: kein Schlummern beim Zweitwecker** (Mitteilung statt AlarmKit). Der Nacht-Check warnt vor dem Stummschalter; Schlummern fehlt weiter.
3. **AlarmKit: feste Uhrzeit nach einer Reise.** Nach einem Zeitzonenwechsel ohne App-Start bleibt der Zweitwecker auf der alten Zeit.
4. **Android: Klingel-Dienst des Handys nicht sticky** (`PhoneRingService`); beendet das System den Prozess, endet das Klingeln.
5. **Einschlaf-Modus (Galaxy Watch):** Die frühere Zielzeit gilt nur auf der Uhr; der Zweitwecker am Handy bleibt auf der eingestellten Zeit (M1).
6. **`versionCode` / `CURRENT_PROJECT_VERSION` = 1 ohne CI-Build-Nummer** – für Store-Uploads nur über die CI bauen.

Geprüft und so belassen: Apple-Sitzung misst die ersten 5 Min. eines 30-Min.-Fensters nicht (watchOS-Grenze, entschieden); `ACTIVITY_RECOGNITION` auf der Uhr wird für den Vordergrunddienst «Gesundheit» gebraucht; ENTSCHEIDE.md ±45 Min. (Trend-Hinweis) und ±30 Min. (Regelmässigkeit) sind zwei verschiedene Regeln.

## Zum Entscheiden (r1AD)

7. Grenzfälle der ehrlichen Texte: `home_no_alarm_title` «Im richtigen Moment aufwachen», `health_rationale` «…den Weckmoment zu prüfen», README «Abgleich mit den Schlafphasen».

## Vor dem Store-Start

8. **Play-Signierung:** eigener Upload-Schlüssel und Play App Signing; die Testversionen sind Debug-Builds mit dem öffentlichen Testschlüssel. Die CI prüft den Release-Build bereits (`release_check.py`).
9. **iPhone / Apple Watch:** TestFlight und App Store brauchen ein Apple-Entwicklerkonto.
10. **Übersetzungen** von Muttersprachlern prüfen lassen (Albanisch: r1AD), auch die neuen Texte vom 02.11. (Zweitwecker vor iOS 26, Einschlaf-Modus nur mit Galaxy Watch, «kein guter Moment», «bei Unruhe früher geweckt»). Schwedisch: «måltid» (Mahlzeit) wurde durch «önskad tid» ersetzt.

## Gerätetests (r1AD)

11. «Genau» klingelt nie früher; Nacht erscheint mit Messdaten im Verlauf.
12. Automatische Einschlaf-Messung (Galaxy Watch, 1 Std. vor dem Einschlafpunkt; Diagnose «auto onset …») und Untergrenze kurzer Nächte («Kurze Nacht – erst zur Zielzeit»).
13. Frühes Wecken nur noch mit gemessenem Pulsanstieg: seltener früh als vorher – beobachten, ob es zu selten wird.
14. Sperrbildschirm: leise Abend-Meldung sichtbar? Widget auf dem Sperrbildschirm auswählbar (hängt von Android-/One-UI-Version ab)? Widget «Details von heute» (Android und iPhone): nach dem Wecken «Letzte Nacht», Befinden erscheint nach dem Antippen, um 12 Uhr zurück zum nächsten Wecker?
15. Messwerte aus echten Nächten für die nächste Bewertung: verpasste Wecker (Ziel 0), Frühwecken in den ersten 5 Min., Befinden, Apple-Sitzung ohne abends geöffnete App.

## Bekannte Grenzen

16. Apple Watch misst nur die letzten 25 Min. vor der Zielzeit; Einschlafzeit und Untergrenze kurzer Nächte gibt es dort nicht (steht im iOS-Store-Text).
17. Ältere Uhr-Apps kennen «Genau» (Fenster 0) nicht und verwerfen solche Wecker; Handy und Uhr gemeinsam aktualisieren (der Zweitwecker am Handy klingelt trotzdem).

## Aufräumen

18. Branch `runde3-zwischenstand` auf GitHub löschen (aus der Sitzung nicht erlaubt).
