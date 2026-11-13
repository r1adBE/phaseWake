# Testnächte

Nächte im Format `.night`, die Android (`android/core`) und Apple (`ios/PhaseWakeCore`) mit den Startwerten (bzw. den gelernten Werten aus `@floor` und `@resting`) gleich bewerten müssen: dieselbe erste Weck-Epoche, derselbe Grund. Format und Regel: [docs/PHASENERKENNUNG.md](../../docs/PHASENERKENNUNG.md).

Die Nächte sind meist synthetisch und decken je einen Regelfall ab; `noisy_watch_*` nutzen die Werte einer Uhr mit Grundrauschen rund 0.02 m/s² (Befund 07.11.2026, Regel 3a). `plateau_arm_position` ist ein Ausschnitt einer echten, ohne Namen aufgezeichneten Nacht (10.11.2026, OFFEN 11g). Vollaudit 11.11.2026: `restless_start_learned_floor` (K-1), `resting_fallback_plateau` (K-2), `plateau_arm_position` (K-3) und `rearm_new_hits` (K-5).

Fix KN-6 (12.11.2026): je Regelwert mindestens eine Nacht, die bei einer Änderung kippt – Ruhepuls-Begrenzung × 1.3
(`resting_clamp`), Median der ersten 6 Epochen (`baseline_median_six`), mindestens 2 Pulse (`min_baseline_two`),
Ersatz-Basispuls × 1.10 (`resting_fallback_factor`), Obergrenze 0.04 und Wach-Faktor 5 (`max_floor_restless`),
Leicht-Faktor 2.5 (`light_factor`), Grundrauschen ab 4 Werten (`min_floor_epochs_before`/`_after`), Aufwärmphase und
Fenster der Regeln (`warmup_two`, `awake_window_three`, `light_window_four`), Faktor 1.5 des Vorwissens (`prior_factor`),
Grundrauschen der Einschlaf-Messung (`onset_floor_*`) und Plausibilität des Vorwissens (Fix KN-2: `low_prior_ignored`,
`plausible_prior_caps`, `noisy_watch_*_low_prior`). Die Mutationsprüfung dazu (jede geänderte Regel lässt mindestens
eine Nacht scheitern) liegt nicht im Repo.

Zusätzlich zum Format: `@onset-floor <wert>` (optional, vor der Kopfzeile) – Grundrauschen der Einschlaf-Messung dieser
Nacht (`DetectorConfig.withOnsetFloor`), zusätzlich zu einem gelernten aus `@floor`. `@rearm-until <index>` (optional, vor der Kopfzeile) – jeder Moment vor dieser Epoche wird übergangen wie bei einer kurzen Nacht (`WakeDetector.rearm`), die Erkennung läuft weiter; `@expect` nennt den ersten Moment danach.

## Vollständigkeit

`MANIFEST` listet jede Datei dieses Ordners (ausser `README.md` und `MANIFEST`). Beide Kerne prüfen, dass genau diese Dateien da sind und so viele Nächte bewertet werden: Eine neue Datei gehört in `MANIFEST`, eine fehlende fällt auf.

## Lern-Übersicht

`learn-nights.vectors`: je Zeile erwarteter Grund (`LearnNights.Reason`), Tabulator, die Fakten einer Nacht als «Schlüssel=Wert» (Format im Kopf der Datei). Beide Kerne prüfen alle Zeilen und dass jeder Grund mindestens einmal vorkommt.

## Passwort-Regel

`backup-password.vectors`: je Zeile erwartetes Ergebnis (`OK`, `TOO_SHORT`, `TRIVIAL`), Tabulator, Passwort (UTF-8, teils in NFD). Beide Kerne prüfen alle Zeilen (`BackupPassword`), Kotlin zusätzlich mit Türkisch als Gerätesprache.

## Sicherung

`backup-v1.pwbak.b64` ist eine verschlüsselte Sicherung (Base64) im alten Format «PWBK1» (210 000 Runden, wird nur noch
gelesen), `backup-v2.pwbak.b64` derselbe Inhalt im aktuellen Format «PWBK2» (600 000 Runden, wird geschrieben);
`backup-v1.password` ist das Passwort beider Dateien und `backup-v1.json` der Klartext. `backup-v1-nfc.*` und
`backup-v2-nfc.pwbak.b64` prüfen dasselbe mit einem Passwort in NFD. Erzeugt mit
`python3 .github/scripts/make-backup-vector.py` (Python «cryptography», feste Werte für Salz und Nonce); `--check` prüft,
ob die Dateien aktuell sind. Beide Kerne müssen alle Dateien entschlüsseln und denselben Inhalt lesen.
