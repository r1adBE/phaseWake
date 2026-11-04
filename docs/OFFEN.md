# Offene Punkte

Stand 04.11.2026, Phase Wake 16.2.2, Build 51. Erledigtes wandert nach [ENTSCHEIDE.md](ENTSCHEIDE.md).

## Erledigt am 04.11.2026 (Details in ENTSCHEIDE.md)

- Review 04.11.: Verlauf der Apple Watch nicht in der Sicherung, Apple Watch nach einer Reise (einmal wecken, zur Ortszeit), Health Connect im Protokoll ohne App-Namen, CSV-Satz in DATENSCHUTZ.md, Fehler beim Rückfall der Apple Watch im Protokoll.

## Erledigt am 02.11.2026 (Details in ENTSCHEIDE.md)

- Entschieden mit r1AD: ehrliche Texte statt «leichter Schlaf» (`reason_deadline`, `history_light_share`, `hint_title`, `editor_advice_text`), Warnung «Zweitwecker vor iOS 26» im Nacht-Check, Hinweis im iPhone-Editor zum Einschlaf-Modus, ⚠ statt ✕ in der Nacht-Check-Karte, Widget «Details von heute».
- Review Build 45 (Android): Einschlaf-Zielzeit nach jedem Einschlafen ans Handy (H-1), Start-Arbeiten nach dem Entsperren (M-1), Tests für Planen und Klingeln am Handy (M-2), PLAY.md zu den Vordergrund-Diensten (M-3), Einschlaf-Zielzeit beim Start abholen und danach löschen (N-1), DATENSCHUTZ.md (N-2), Marke des Wiederherstellens (N-3), Warnung ohne exakte Alarme (N-4), Nachfrist nach Uhr (N-5), verspätetes «klingeln» (LATE). `lintRelease` nicht zusätzlich: `assembleRelease` prüft schon mit Lint-Vital (N-6).
- Review Build 43 (Android): Guava 33.7.2 (K-01), Kotlin-Warnung im Test (K-04), Klingeln am Handy nimmt nach einem Prozessende wieder auf (K-06), Einschlaf-Modus auch für den Zweitwecker am Handy (K-07), CSV-Export im Hintergrund (K-09), Nacht-Check «Uhr-App veraltet» (O-04), Wiederherstellen mit Marke gegen Prozessende (K-03), Tests für Klingeln am Handy und Planen/Wecken der Uhr (W-01).
- Audit 31.10.: K1 (Nachfrist), K2 (zwei Wecker am Handy), N1 («stop» der Uhr), H2 (Schlummern am Handy nach Neustart), H4 (Klingeln der Galaxy Watch nach Prozessende), H5/M6 (exakte Alarme der Uhr), H6 (zweiter Wecker während einer Nacht), Mitteilung «Nächster Wecker» privat, PBKDF2-Kommentar, Rundung und Einschlafklänge (Parität), iOS-Verlauf, Notizen und Diagnose ausserhalb der iCloud-Sicherung, DATENSCHUTZ.md (Inhalt der `.pwbak`, Diagnoseprotokoll, iCloud, Sperrbildschirm).

## Noch offen aus dem Audit 31.10.

1. **iOS: Einstellungen in der iCloud-Sicherung.** Wecker und Einstellungen (z. B. Schlafziel) liegen in `UserDefaults` und gehen mit; so steht es jetzt auch in DATENSCHUTZ.md. Offen, ob sie ebenfalls ausgenommen werden sollen.
2. **iOS vor 26: kein Schlummern beim Zweitwecker** (Mitteilung statt AlarmKit). Der Nacht-Check warnt vor dem Stummschalter; Schlummern fehlt weiter.
3. **AlarmKit: feste Uhrzeit nach einer Reise.** Nach einem Zeitzonenwechsel ohne App-Start bleibt der Zweitwecker auf der alten Zeit.
4. **Android: Handy-Klingeln, während die Uhr führt** (`PhoneRingService`). Führt das Handy allein, nimmt ein Alarm kurz nach der Zeitgrenze das Klingeln nach einem Prozessende wieder auf. Führt die Uhr und endet der Prozess des Handys, verstummt nur das Handy; die Uhr klingelt weiter und hat ihren eigenen Wiederaufnahme-Alarm.
5. **`versionCode` / `CURRENT_PROJECT_VERSION` = 1 ohne CI-Build-Nummer** – für Store-Uploads nur über die CI bauen.

Geprüft und so belassen: Apple-Sitzung misst die ersten 5 Min. eines 30-Min.-Fensters nicht (watchOS-Grenze, entschieden); `ACTIVITY_RECOGNITION` auf der Uhr wird für den Vordergrunddienst «Gesundheit» gebraucht; ENTSCHEIDE.md ±45 Min. (Trend-Hinweis) und ±30 Min. (Regelmässigkeit) sind zwei verschiedene Regeln.

## Zum Entscheiden (r1AD)

6. Grenzfälle der ehrlichen Texte: `home_no_alarm_title` «Im richtigen Moment aufwachen», `health_rationale` «…den Weckmoment zu prüfen», README «Abgleich mit den Schlafphasen».

## Vor dem Store-Start

7. **Play-Signierung:** eigener Upload-Schlüssel und Play App Signing; die Testversionen sind Debug-Builds mit dem öffentlichen Testschlüssel. Die CI prüft den Release-Build bereits (`release_check.py`).
8. **iPhone / Apple Watch:** TestFlight und App Store brauchen ein Apple-Entwicklerkonto.
9. **Übersetzungen** von Muttersprachlern prüfen lassen (Albanisch: r1AD), auch die neuen Texte vom 02.11. (Zweitwecker vor iOS 26, Einschlaf-Modus nur mit Galaxy Watch, «kein guter Moment», «bei Unruhe früher geweckt»). Schwedisch: «måltid» (Mahlzeit) wurde durch «önskad tid» ersetzt.

## Gerätetests (r1AD)

10. «Genau» klingelt nie früher; Nacht erscheint mit Messdaten im Verlauf.
11. Automatische Einschlaf-Messung (Galaxy Watch, 1 Std. vor dem Einschlafpunkt; Diagnose «auto onset …») und Untergrenze kurzer Nächte («Kurze Nacht – erst zur Zielzeit»).
12. Frühes Wecken nur noch mit gemessenem Pulsanstieg: seltener früh als vorher – beobachten, ob es zu selten wird.
13. Sperrbildschirm: leise Abend-Meldung sichtbar? Widget auf dem Sperrbildschirm auswählbar (hängt von Android-/One-UI-Version ab)? Widget «Details von heute» (Android und iPhone): nach dem Wecken «Letzte Nacht», Befinden erscheint nach dem Antippen, um 12 Uhr zurück zum nächsten Wecker?
14. Messwerte aus echten Nächten für die nächste Bewertung: verpasste Wecker (Ziel 0), Frühwecken in den ersten 5 Min., Befinden, Apple-Sitzung ohne abends geöffnete App.

## Bekannte Grenzen

15. Apple Watch misst nur die letzten 25 Min. vor der Zielzeit; Einschlafzeit und Untergrenze kurzer Nächte gibt es dort nicht (steht im iOS-Store-Text).
16. Ältere Uhr-Apps kennen «Genau» (Fenster 0) nicht und verwerfen solche Wecker; Handy und Uhr gemeinsam aktualisieren (der Zweitwecker am Handy klingelt trotzdem). Der Nacht-Check am Handy warnt jetzt («Uhr-App veraltet», Review O-04).

## Aufräumen

17. Branch `runde3-zwischenstand` auf GitHub löschen (aus der Sitzung nicht erlaubt).
