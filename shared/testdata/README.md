# Testnächte

Nächte im Format `.night`, die Android (`android/core`) und Apple (`ios/PhaseWakeCore`) mit den Startwerten (bzw. den gelernten Werten aus `@floor` und `@resting`) gleich bewerten müssen: dieselbe erste Weck-Epoche, derselbe Grund. Format und Regel: [docs/PHASENERKENNUNG.md](../../docs/PHASENERKENNUNG.md).

Die Nächte sind meist synthetisch und decken je einen Regelfall ab; `noisy_watch_*` nutzen die Werte einer Uhr mit Grundrauschen rund 0.02 m/s² (Befund 07.11.2026, Regel 3a). `plateau_arm_position` ist ein Ausschnitt einer echten, ohne Namen aufgezeichneten Nacht (10.11.2026, OFFEN 11g). Dazu `restless_start_learned_floor` (Vorwissen begrenzt das Grundrauschen eines unruhigen Fensters), `resting_fallback_plateau` (Ersatz-Basispuls), `plateau_arm_position` (Basispuls als Median) und `rearm_new_hits` (nach einer kurzen Nacht braucht es neue Treffer).

Je Regelwert mindestens eine Nacht, die bei einer Änderung kippt – Ruhepuls-Begrenzung × 1.3
(`resting_clamp`), Median der ersten 6 Epochen (`baseline_median_six`), mindestens 2 Pulse (`min_baseline_two`),
Ersatz-Basispuls × 1.10 (`resting_fallback_factor`), Obergrenze 0.04 und Wach-Faktor 5 (`max_floor_restless`),
Leicht-Faktor 2.5 (`light_factor`), Grundrauschen ab 4 Werten (`min_floor_epochs_before`/`_after`), Aufwärmphase und
Fenster der Regeln (`warmup_two`, `awake_window_three`, `light_window_four`), Faktor 1.5 des Vorwissens (`prior_factor`),
Grundrauschen der Einschlaf-Messung (`onset_floor_*`) und Plausibilität des Vorwissens (`low_prior_ignored`,
`plausible_prior_caps`, `noisy_watch_*_low_prior`). Die Mutationsprüfung dazu (jede geänderte Regel lässt mindestens
eine Nacht scheitern) liegt nicht im Repo.

Zusätzlich zum Format: `@onset-floor <wert>` (optional, vor der Kopfzeile) – Grundrauschen der Einschlaf-Messung dieser
Nacht (`DetectorConfig.withOnsetFloor`), zusätzlich zu einem gelernten aus `@floor`. `@rearm-until <index>` (optional, vor der Kopfzeile) – jeder Moment vor dieser Epoche wird übergangen wie bei einer kurzen Nacht (`WakeDetector.rearm`), die Erkennung läuft weiter; `@expect` nennt den ersten Moment danach.

## Vollständigkeit

`MANIFEST` listet jede Datei dieses Ordners (ausser `README.md` und `MANIFEST`). Beide Kerne prüfen, dass genau diese Dateien da sind und so viele Nächte bewertet werden: Eine neue Datei gehört in `MANIFEST`, eine fehlende fällt auf.

`light_needs_floor`: Die Leicht-Regel wartet ohne Grundrauschen; `min_floor_epochs_before`/`_after` sind dafür neu
gerechnet. `hr_rise_exact`: Puls genau Basispuls × 1.05 weckt («mindestens»).

`real_window_7`: echtes, ohne Namen aufgezeichnetes Weckfenster (17.11.2026) mit gelerntem Grundrauschen 0.0197 (`@floor`):
Eine andere Armlage hebt das Grundrauschen im Fenster auf 0.0296; die Leicht-Schwelle im Weckfenster bleibt bei 2.5 ×
gelernt (PHASENERKENNUNG 3a) und weckt in Epoche 31 statt erst zur Zielzeit. Den Faktor 1.5 × Grundrauschen dieser
Regel prüfen beide Kerne in `NoiseFloorTest`/`NoiseFloorTests`.

Regel S5 (eine starke Bewegung ≥ wirksame Wach-Schwelle in den letzten 2 Epochen mit
Pulsanstieg): `real_window_1` bis `_6` sind echte, ohne Namen aufgezeichnete Weckfenster (Nacht 6 = 15.11.2026, jetzt
Epoche 49 statt Zielzeit; 1–5 unverändert), `strong_motion_at_wake_threshold`/`_below_wake_threshold` (genau an bzw. knapp
unter der Schwelle), `strong_motion_no_hr_rise`, `strong_motion_window_two`, `strong_motion_needs_floor` und
`strong_awake_same_epoch` (Wach hat in derselben Epoche Vorrang). `awake_precedes_light` weckt seither in Epoche 15 sanft.

## Kalibrierung

`calibration-real.vectors`: 4 echte, ohne Namen aufgezeichnete Fensternächte einer Galaxy Watch, je Zeile eine Nacht
(Bewegung je Epoche, Komma-getrennt, leer = keine), dazu `@expect <leicht> <wach>`. Beide Kerne müssen diese Schwellen
kalibrieren (Abschnitt 4: Grundrauschen je Nacht abziehen, Wach höchstens 0.1).

## Lern-Übersicht

`learn-nights.vectors`: je Zeile erwarteter Grund (`LearnNights.Reason`), Tabulator, die Fakten einer Nacht als «Schlüssel=Wert» (Format im Kopf der Datei). Beide Kerne prüfen alle Zeilen und dass jeder Grund mindestens einmal vorkommt.

## Auffälligkeiten

`findings.vectors`: je Zeile die erwarteten Auffälligkeiten (`Findings`), Nachtdatum «heute»
und die Fakten der Nächte (Format im Kopf der Datei): jede Grenze knapp darunter, genau und knapp darüber, zu wenige
Nächte, keine Phasen, Zusammenhang, Zeitzone, Priorität und die echten Werte des Besitzers. Beide Kerne prüfen alle Zeilen.

`findings-onset.vectors`: je Zeile die erwartete Einschlafdauer aus der eigenen Messung der Uhr (`Findings.ownLatency`),
die Nacht der Gesundheits-App und die Aufzeichnungen mit erkanntem Einschlafen (Format im
Kopf der Datei): keine, nur berührt, Einschlafzeit vor dem Beginn, längste Überschneidung, Gleichstand, Nacht der Uhr vor
der Aufzeichnung. Beide Kerne prüfen alle Zeilen.

## Nachtformat mit Einschlafen

`night-onset.vectors`: je Zeile Grund, Akku und Einschlafen der Uhr (Felder 11 und 12 von `Codec.encodeNight`),
wie sie gelesen werden, und ob Schreiben genau die Zeile ergibt: alte Stände (6 und 7 Felder, mit Akku),
neue mit und ohne Akku, nur Beginn, unlesbar, ausserhalb 1970–2100, weiteres Feld einer neueren Version. Beide Kerne prüfen alle Zeilen.

## Validierung

`validation.vectors`: Validierungs-Modus (`Validation`, PHASENERKENNUNG 12). Zeilen `ref`:
Referenzphase an den Grenzen ±60 s (abgedeckt, Beginn, Ende, 59/60/61 s, Gleichstand, «unbekannt» übergangen,
Überlappung). Fälle `case`: Nächte, Aufzeichnungen, Sitzungen und gelernte Werte mit den erwarteten Zeilen des Exports und
den Summen – Schattenfenster (leicht, ohne Moment, wach), Nacht ohne Referenz, Stichproben, Zeitzone der Sitzung, 45/44
von 60 Epochen gemessen, gelernter Ruhepuls und Kalibrierung nur aus Nächten vor der Aufzeichnung. Beide Kerne prüfen alle
Zeilen.

## Planung und Abgleich der Wecker

`schedule.vectors`: `AlarmSchedule` mit Weckern im `Codec`-Format, Zeilen `next` (`nextTarget`,
gleich `earliestPlan`, und `skipNext`), `planned` (`inGrace`, `begunNight`) und `missed` (`missedPlan`, `earliestPlan`),
Format im Kopf der Datei: Wochentage, Einmal-Wecker, Sommer- und Winterzeit in Zürich (29.03./25.10.2026, auch 02:30 in
der Lücke und in der doppelten Stunde), Überspringen, Feiertage, erledigte Nächte, Nachfrist, Reisen (Tokio, New York) und
Nachholen. `alarm-stamps.vectors`: `AlarmStamps.merge` je Wecker (Konflikte, gleiche Zeitpunkte, Löschen, alte und
unplausible gelöschte Wecker, Gleichstand nach der Codec-Zeile, doppelte Kennung: der letzte Eintrag), jede Zeile in
beiden Richtungen. Die Erwartungen stammen aus dem Kotlin-Kern (Stand
17.11.2026) und sind von Hand geprüft. Beide Kerne prüfen alle Zeilen (Kotlin für `merge` in `android/shared`).

## Passwort-Regel

`backup-password.vectors`: je Zeile erwartetes Ergebnis (`OK`, `TOO_SHORT`, `TRIVIAL`), Tabulator, Passwort (UTF-8, teils in NFD). Beide Kerne prüfen alle Zeilen (`BackupPassword`), Kotlin zusätzlich mit Türkisch als Gerätesprache.

## Sicherung

`backup-v1.pwbak.b64` ist eine verschlüsselte Sicherung (Base64) im alten Format «PWBK1» (210 000 Runden, wird nur noch
gelesen), `backup-v2.pwbak.b64` derselbe Inhalt im aktuellen Format «PWBK2» (600 000 Runden, wird geschrieben);
`backup-v1.password` ist das Passwort beider Dateien und `backup-v1.json` der Klartext. `backup-v1-nfc.*` und
`backup-v2-nfc.pwbak.b64` prüfen dasselbe mit einem Passwort in NFD. Erzeugt mit
`python3 .github/scripts/make-backup-vector.py` (Python «cryptography», feste Werte für Salz und Nonce); `--check` prüft,
ob die Dateien aktuell sind. Beide Kerne müssen alle Dateien entschlüsseln und denselben Inhalt lesen.
