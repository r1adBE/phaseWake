# Offene Punkte

Stand 04.11.2026, Phase Wake 16.2.2. Erledigtes wandert nach [ENTSCHEIDE.md](ENTSCHEIDE.md).

## Erledigt am 10.11.2026 (Details in ENTSCHEIDE.md)

- Schlafwert ohne Schlafphasen vorläufig (höchstens 84, Hinweis und gestrichelter Ring), neutrale Säulen «Ohne Phasen» in «Dein Schlaf» (Android und iPhone).
- Auswertung auf dem iPhone (Teil von 11l): Schlafwert und Übersicht im Swift-Kern (`SleepScore`, `SleepOverviews`, gleiche Tests wie Kotlin), Detailseite mit Schlafwert und Puls im Weckfenster, Ring in der Liste, «Dein Schlaf» mit «Tage / Wochen / Monate». Gerätetest (r1AD): iPhone mit einer Nacht aus Apple Health mit Phasen, einer Sitzung ohne Phasen (vorläufiger Wert) und einer eigenen Nacht ohne Phasen (kein Schlafwert); Android eine nur aufgezeichnete Nacht (vorläufig, höchstens 84).

## Erledigt am 09.11.2026 (Details in ENTSCHEIDE.md)

- Lernphase von 7 Nächten (früher 11h, ersetzt 11b; Android, Apple folgt): Die Galaxy Watch zeichnet ab der Einschlaf-Messung bis zum Wecken automatisch auf (ab 50 % Akku), das Handy zählt die Nächte («Lernphase: Nacht N von 7») und lernt danach eigenes Grundrauschen, Ruhepuls und Einschlafdauer; Empfehlung der Schlafenszeit am Abend, Karte «Angepasst an dich», «Neu lernen» in den Einstellungen. Gerätetest (r1AD): automatischer Start mit der Einschlaf-Messung und Ende beim Wecken (Diagnose `learn record start`, `record end wake`), Zählen am Handy (`learn night counted N/7`), Akku über eine Lernnacht, nach 7 Nächten die Werte am Handy und `learning active=0 floor=…` auf der Uhr.
- Puls auf der Galaxy Watch laufend (früher 11f): Weckfenster und «Nacht aufzeichnen» lesen den Pulssensor direkt (Rückfall Health Services), Diagnose `hrsrc=` und `hr=`. Gerätetest (r1AD): Anteil der Epochen mit Puls in Fenster und Aufzeichnung, Akku über eine aufgezeichnete Nacht.
- Einschlaf-Messung bei spätem Einschlafen (früher 11j): bei Unruhe verlängert um je 15 Min bis höchstens 4 Std., nie ins Weckfenster, nicht unter 30 % Akku. Gerätetest (r1AD): Zeile «extended until» und erkannte Einschlafzeit in einer unruhigen Nacht.
- Export nur in den Einstellungen (früher 11c), aktiv mit Nächten oder Aufzeichnungen; Knopf am Ende des Verlaufs entfernt.
- Karte «Nacht-Aufzeichnung» auf der Handy-Startseite neu angeordnet (früher 11d).
- Sonnenaufgang im Diagnose-Protokoll, Handy und Galaxy Watch (früher 11e).
- Auswertung (früher 11k; Android-Handy, Apple folgt, siehe 11l): Schlafwert 0–100 mit Bewertung und Faktoren (Schlafdauer, Tief, REM, Wach, Einschlafdauer; Regel PHASENERKENNUNG.md 11) auf der Detailseite und als Kurzzeile in der Liste, Puls- und Bewegungsverlauf aus der Aufzeichnung der Uhr (sonst Puls im Weckfenster), Übersicht «Tage / Wochen / Monate» oben im Verlauf mit Ø Schlafdauer, Ø Schlafenszeit und Ø Aufwachzeit. Die Empfehlung der Schlafenszeit gibt es schon (Lernphase). Gerätetest (r1AD): Werte und Quelle bei einer Nacht mit Samsung Health, bei einer nur aufgezeichneten Nacht und bei einer Nacht ohne beides (dann kein Schlafwert).

## Erledigt am 07.11.2026 (Details in ENTSCHEIDE.md)

- «Nacht aufzeichnen» auf der Galaxy Watch (Knopf auf Uhr und Android-Handy): ganze Nacht Bewegung und Puls je 30 s, höchstens 12 Std., endet unter 15 % Akku; im Verlauf und im Export, mit «Verlauf löschen» gelöscht, Diagnose stündlich.

- Bewegungsschwelle relativ zum Rauschen der Uhr: Auf der Galaxy Watch lag still liegend jede Epoche über der Leicht-Schwelle (Einschlafzeit nie erkannt, frühes Wecken nur nach Puls). Leicht- und Wach-Schwelle steigen jetzt mit dem Grundrauschen der Nacht (PHASENERKENNUNG.md 3a); Diagnose mit `floor=`.

## Erledigt am 06.11.2026 (Details in ENTSCHEIDE.md)

- Galaxy Watch: Grund im Diagnose-Protokoll, wenn sie nicht messen darf (Fehler und Sensorrechte), und bei jedem stillen Abbruch der Einschlaf-Messung.
- Nacht-Check am Handy warnt rot, wenn die Uhr den Puls nachts (im Hintergrund) nicht messen darf; die Diagnose zeigt den Stand.

## Erledigt am 04.11.2026 (Details in ENTSCHEIDE.md)

- Review 04.11.: Verlauf der Apple Watch nicht in der Sicherung, Apple Watch nach einer Reise (einmal wecken, zur Ortszeit), Health Connect im Protokoll ohne App-Namen, CSV-Satz in DATENSCHUTZ.md, Fehler beim Rückfall der Apple Watch im Protokoll.
- Review 04.11. b: Zeitzonenwechsel im laufenden Weckfenster (Nacht bleibt zur geplanten Zeit, Uhr und Handy, P1), abgebrochene Messung der Galaxy Watch hebt die neue Sicherung nicht auf (P2).
- Review 04.11. c: Rückfall der Apple Watch ab Messbeginn zum festen Zeitpunkt (kein zweites Wecken nach einer Reise nach Osten im Fenster).
- Runde 04.11. d: Handy-Klingeln nimmt nach einem Prozessende auch wieder auf, wenn die Uhr führt (früher Punkt 4); Store-Build für Google Play über die CI (Workflow «Store-Build», Upload-Schlüssel, Anleitung in PLAY.md); Grenzfälle der ehrlichen Texte (früher Punkt 6); iPhone: Wecker und Einstellungen nicht mehr in der iCloud-Sicherung (früher Punkt 1).
- Apple Watch: Wecker und Einstellungen nicht mehr in der Sicherung (früher Punkt 1); in der Sicherung der Uhr bleiben nur technische Merker.
- Review 04.11. e: zweiter Zweitwecker am Handy in der Nachfrist wird nachgeholt (P1).
- Audit 04.11. (Android): Lizenztexte in der App (M-05), eigene Kennung der Testversion (M-04), verweigerter Start des Weckdienstes der Galaxy Watch (N-05), Tests und Mindestabdeckung für Wecken und Planen (M-06), Liste vor dem ersten Entsperren geordnet (I-05), keine Build-Nummern in Kommentaren (N-06), README zum Wecken je Gerät.
- Audit 04.11. (Apple): Weckmitteilung als Kette (H-01), Versprechen «spätestens zur Zielzeit» präzisiert (H-01/N-04), Lizenzseite am iPhone (M-05), Mitteilungsrecht der Apple Watch im Nacht-Check (M-03), Pulsdichte und Fehler im Protokoll (M-02, N-03), Keyword (N-08); Testversion heisst «Phase Wake Test».
- Nachaudit 04.11. (Android): Robolectric-Abbilder je Robolectric-Version geprüft (NEU-04), Hinweis «Lizenzen: in der Handy-App» auf der Galaxy Watch (NEU-06).
- Nachaudit 04.11. (Apple): Platz für Mitteilungen des Zweitweckers vor iOS 26 (NEU-01/NEU-03), Erlaubnis der Apple Watch auch beim Planen (NEU-02), AlarmKit-Fehler im Protokoll (N-03); Dependabot-PRs #4 und #6 geschlossen (NEU-05).
- GitHub aufgeräumt (05.11.): nur noch `main` als Code-Zweig, Workflow «Aufräumen» für erledigte Zweige und alte Läufe, Zweige `runde2` und `runde3-zwischenstand` gelöscht.
- Nachaudit 04.11. (Apple, 05.11.): AlarmKit-Abfrage mit einem zweiten Versuch nach 1 s (NEU-07); Hinweis im Nacht-Check, wenn der Stand der Apple Watch älter als 3 Tage ist (NEU-02 Rest).

## Erledigt am 02.11.2026 (Details in ENTSCHEIDE.md)

- Entschieden mit r1AD: ehrliche Texte statt «leichter Schlaf» (`reason_deadline`, `history_light_share`, `hint_title`, `editor_advice_text`), Warnung «Zweitwecker vor iOS 26» im Nacht-Check, Hinweis im iPhone-Editor zum Einschlaf-Modus, ⚠ statt ✕ in der Nacht-Check-Karte, Widget «Details von heute».
- Review Build 45 (Android): Einschlaf-Zielzeit nach jedem Einschlafen ans Handy (H-1), Start-Arbeiten nach dem Entsperren (M-1), Tests für Planen und Klingeln am Handy (M-2), PLAY.md zu den Vordergrund-Diensten (M-3), Einschlaf-Zielzeit beim Start abholen und danach löschen (N-1), DATENSCHUTZ.md (N-2), Marke des Wiederherstellens (N-3), Warnung ohne exakte Alarme (N-4), Nachfrist nach Uhr (N-5), verspätetes «klingeln» (LATE). `lintRelease` nicht zusätzlich: `assembleRelease` prüft schon mit Lint-Vital (N-6).
- Review Build 43 (Android): Guava 33.7.2 (K-01), Kotlin-Warnung im Test (K-04), Klingeln am Handy nimmt nach einem Prozessende wieder auf (K-06), Einschlaf-Modus auch für den Zweitwecker am Handy (K-07), CSV-Export im Hintergrund (K-09), Nacht-Check «Uhr-App veraltet» (O-04), Wiederherstellen mit Marke gegen Prozessende (K-03), Tests für Klingeln am Handy und Planen/Wecken der Uhr (W-01).
- Audit 31.10.: K1 (Nachfrist), K2 (zwei Wecker am Handy), N1 («stop» der Uhr), H2 (Schlummern am Handy nach Neustart), H4 (Klingeln der Galaxy Watch nach Prozessende), H5/M6 (exakte Alarme der Uhr), H6 (zweiter Wecker während einer Nacht), Mitteilung «Nächster Wecker» privat, PBKDF2-Kommentar, Rundung und Einschlafklänge (Parität), iOS-Verlauf, Notizen und Diagnose ausserhalb der iCloud-Sicherung, DATENSCHUTZ.md (Inhalt der `.pwbak`, Diagnoseprotokoll, iCloud, Sperrbildschirm).

## Noch offen aus dem Audit 31.10.

1. **iOS vor 26: kein Schlummern beim Zweitwecker** (Mitteilung statt AlarmKit, seit 04.11. als Kette von 4 Mitteilungen). Der Nacht-Check warnt vor dem Stummschalter; Schlummern fehlt weiter.
2. **AlarmKit: feste Uhrzeit nach einer Reise.** Nach einem Zeitzonenwechsel ohne App-Start bleibt der Zweitwecker auf der alten Zeit.
3. **`versionCode` / `CURRENT_PROJECT_VERSION` = 1 ohne CI-Build-Nummer** – für Store-Uploads nur über die CI bauen (Android: Workflow «Store-Build»).

Geprüft und so belassen: Apple-Sitzung misst die ersten 5 Min. eines 30-Min.-Fensters nicht (watchOS-Grenze, entschieden); `ACTIVITY_RECOGNITION` auf der Uhr wird für den Vordergrunddienst «Gesundheit» gebraucht; ENTSCHEIDE.md ±45 Min. (Trend-Hinweis) und ±30 Min. (Regelmässigkeit) sind zwei verschiedene Regeln.

## Vor dem Store-Start

4. **Play-Signierung:** Die CI baut die signierten Bundles (Workflow «Store-Build», docs/PLAY.md). Offen bei r1AD: Upload-Schlüssel mit `keytool` erstellen, die vier Secrets `PLAY_UPLOAD_*` auf GitHub anlegen, App in der Play Console anlegen (Play App Signing an, Formfaktor Wear OS).
5. **iPhone / Apple Watch:** TestFlight und App Store brauchen ein Apple-Entwicklerkonto.
6. **Übersetzungen** von Muttersprachlern prüfen lassen (Albanisch: r1AD), auch die neuen Texte vom 02.11. (Zweitwecker vor iOS 26, Einschlaf-Modus nur mit Galaxy Watch, «kein guter Moment», «bei Unruhe früher geweckt»). Schwedisch: «måltid» (Mahlzeit) wurde durch «önskad tid» ersetzt.

## Gerätetests (r1AD)

7. «Genau» klingelt nie früher; Nacht erscheint mit Messdaten im Verlauf.
8. Automatische Einschlaf-Messung (Galaxy Watch, 1 Std. vor dem Einschlafpunkt; Diagnose «auto onset …») und Untergrenze kurzer Nächte («Kurze Nacht – erst zur Zielzeit»).
9. Frühes Wecken nur noch mit gemessenem Pulsanstieg: seltener früh als vorher – beobachten, ob es zu selten wird.
10. Sperrbildschirm: leise Abend-Meldung sichtbar? Widget auf dem Sperrbildschirm auswählbar (hängt von Android-/One-UI-Version ab)? Widget «Details von heute» (Android und iPhone): nach dem Wecken «Letzte Nacht», Befinden erscheint nach dem Antippen, um 12 Uhr zurück zum nächsten Wecker?
11. Messwerte aus echten Nächten für die nächste Bewertung: verpasste Wecker (Ziel 0), Frühwecken in den ersten 5 Min., Befinden, Apple-Sitzung ohne abends geöffnete App.

11a. «Nacht aufzeichnen» an der Galaxy Watch prüfen: Akku über eine ganze Nacht (grobe Schätzung, nicht gemessen: 2–5 % je Stunde, also rund 15–40 % für 8 Std.; vor allem Puls und Wakelock; die Diagnose schreibt den Akku stündlich), Puls gleichzeitig mit dem Weckfenster (zwei Messungen derselben App), Start und Stopp vom Handy bei dunkler Uhr, Wiederaufnahme nach «Beenden erzwingen» bzw. Neustart.
11g. «Leichter Schlaf» auch bei längerer Bewegung ohne Pulsanstieg (z. B. ≥ 2 Min über der Leicht-Schwelle) – erst nach Prüfung an mehreren aufgezeichneten Nächten. Gewünscht von r1AD am 08.11.
11l. Lernphase auf iPhone und Apple Watch: Apple folgt (Swift-Kern kennt die gelernten Werte schon, PHASENERKENNUNG.md 10; Aufzeichnung der ganzen Nacht auf der Apple Watch, Zählen und Anzeige fehlen dort). Die Auswertung (früher 11k) gibt es auf dem iPhone seit 10.11.; erst mit der Aufzeichnung der Apple Watch kommen dort Schlafwert ohne Apple Health sowie Puls und Bewegung über die ganze Nacht dazu.
11i. Fenster «Genau»: Hinweis von r1AD am 08.11. – damit gibt es kein Fenster für die Phasen. Geprüft und so belassen: «Genau» bleibt (r1AD 09.11.).

## Bekannte Grenzen

12. Apple Watch misst nur die letzten 25 Min. vor der Zielzeit; Einschlafzeit und Untergrenze kurzer Nächte gibt es dort nicht (steht im iOS-Store-Text).
13. Ältere Uhr-Apps kennen «Genau» (Fenster 0) nicht und verwerfen solche Wecker; Handy und Uhr gemeinsam aktualisieren (der Zweitwecker am Handy klingelt trotzdem). Der Nacht-Check am Handy warnt jetzt («Uhr-App veraltet», Review O-04).
14. Apple Watch: Startet die Sitzung einer Nacht nicht (vom System abgelehnt, App nie offen), läuft auf der Uhr kein Prozess; ihr Rückfall bleibt dann Kalenderzeit ohne Zone und folgt einer Reise im Fenster der Ortszeit (watchOS lässt ihn ohne laufende App nicht umstellen).

## Aufräumen

15. **GitHub-Einstellungen (r1AD, im Browser):** Wiki und Projects aus, Topics setzen, Regel für `main` (Löschen und Force-Push verbieten, grüne Prüfungen «Android (Kern, Handy, Uhr)», «Apple-Kern (Swift)», «Apple-Apps (iPhone, Watch)» verlangen). Aus der Sitzung sind Einstellungen nicht erlaubt.
