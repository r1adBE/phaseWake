# Phasenerkennung – Spezifikation

Diese Datei ist die verbindliche Regel. Android (`android/core/`, Kotlin) und Apple (`ios/PhaseWakeCore/`, Swift) setzen sie identisch um. Beide laufen gegen dieselben Testnächte in `shared/testdata/` und müssen dort dieselbe Epoche und denselben Grund liefern. Wer die Regel ändert, ändert diese Datei, beide Umsetzungen und die Testnächte im selben Commit.

## Begriffe

- **Epoche**: 30 Sekunden. Jede Epoche hat eine Bewegung (`motion`, in m/s², oder leer) und einen Puls (`heartRate`, in Schlägen pro Minute, oder leer).
- **Aufwärmphase**: die ersten 2 Epochen (1 Minute) des Weckfensters. Sie werden nur beobachtet.
- **Entscheidbare Epochen**: alle Epochen ab Index 2. Nur sie zählen in den Regeln unten.

## 1. Bewegung pro Epoche

Eingang: Beschleunigungswerte (x, y, z in m/s², Zeitstempel in Nanosekunden). Werte mit einer ungültigen Komponente (NaN, unendlich) werden verworfen und sind auch kein Bezugspunkt.

1. **Takt begrenzen**: Ein Wert wird nur übernommen, wenn seit dem zuletzt übernommenen mindestens 35 ms vergangen sind. So messen beide Plattformen mit höchstens rund 28 Hz, auch wenn der Sensor schneller liefert.
2. **Differenz**: Für jeden übernommenen Wert (ausser dem allerersten) wird der Abstand zum vorherigen übernommenen Wert berechnet: `d = √((x−x₀)² + (y−y₀)² + (z−z₀)²)`. Liegen zwischen den beiden Werten mehr als 1 s, wird diese Differenz verworfen (Sensorlücke).
3. **Rauschen abziehen**: `max(0, d − 0.05)`.
4. **Bewegung der Epoche** = Mittelwert dieser Werte über alle Differenzen der Epoche.
5. **Zu wenig Daten**: Weniger als 300 Differenzen in der Epoche → Bewegung ist leer.

Der letzte übernommene Wert bleibt über das Epochenende hinaus Bezugspunkt für die nächste Differenz.

## 2. Puls pro Epoche

Mittelwert aller Pulswerte der Epoche. Werte ≤ 0, > 250 oder ungültig (NaN, unendlich) werden ignoriert. Kein gültiger Wert → Puls leer.

## 3. Entscheidung je Epoche

Startwerte (werden später kalibriert, siehe 4, und nach dem Grundrauschen der Uhr angehoben, siehe 3a):

| Name | Wert |
|---|---|
| Leicht-Schwelle | 0.002 m/s² |
| Wach-Schwelle | 0.100 m/s² |
| Pulsanstieg | 5 % |

**Basispuls** = Mittelwert der vorhandenen Pulse der Aufwärmphase. Hat die Aufwärmphase keinen Puls, gilt der gelernte Ruhepuls (Abschnitt 10), sonst bleibt der Basispuls für das ganze Fenster leer.

Für jede Epoche mit Index *i*, in dieser Reihenfolge:

1. **Bereits geweckt** → dieselbe Weck-Entscheidung wie zuvor.
2. **Aufwärmphase** (*i* < 2) → warten.
3. **Wach**: Unter den letzten 3 entscheidbaren Epochen (inkl. *i*) haben mindestens 2 eine Bewegung ≥ Wach-Schwelle → wecken, Grund `AWAKE`. Leicht- und Wach-Schwelle sind hier und im nächsten Schritt die wirksamen Werte bei Epoche *i* (3a); sie gelten für alle Epochen im Fenster der Regel.
4. **Leichtschlaf**: Unter den letzten 4 entscheidbaren Epochen (inkl. *i*) haben mindestens 2 eine Bewegung ≥ Leicht-Schwelle **und** die Pulsbedingung ist erfüllt → wecken, Grund `LIGHT_SLEEP`.
   - **Aktueller Puls** = Mittelwert der vorhandenen Pulse der Epochen *i* und *i−1* (nur entscheidbare).
   - **Pulsbedingung**: Basispuls und aktueller Puls müssen vorhanden sein, und es muss gelten: aktueller Puls ≥ Basispuls × 1.05. Fehlt einer der beiden, ist die Bedingung **nicht** erfüllt: Bewegung allein reicht nicht, um leichten Schlaf zu behaupten (dann weckt die Uhr zur Zielzeit, ausser die Wach-Regel greift).
5. Sonst → warten.

Leere Bewegung zählt nie als Treffer.

### 3a. Schwelle relativ zum Rauschen der Uhr

Befund 07.11.2026 (Galaxy Watch von r1AD, automatische Einschlaf-Messung, Werte nach Abzug von 0.05, je 15 Min Mittel/Maximum): still liegend 0.0194/0.0203, 0.0217/0.0287, 0.0233/0.0280, 0.0199/0.0202 m/s²; unruhig 0.2358/0.8285, 0.1121/0.5423, 0.1414/0.4128, 0.0933/0.3556 m/s². Mit der Leicht-Schwelle 0.002 (Kalibrierung höchstens 0.004) lag jede Epoche darüber: Die Einschlafzeit wurde nie erkannt, und im Weckfenster entschied für `LIGHT_SLEEP` allein der Puls.

- **Grundrauschen** *F* = 20. Perzentil der vorhandenen Bewegungen der laufenden Messung bis und mit der aktuellen Epoche (Rangmethode: aufsteigend sortieren, Wert an Position ⌈0.2 · n⌉ = (n + 4) / 5 ganzzahlig, gezählt ab 1). Im Weckfenster zählen auch die Aufwärm-Epochen. Leere Bewegung zählt nicht. Weniger als 4 Werte → das gelernte Grundrauschen (Abschnitt 10, ebenfalls höchstens 0.04), ohne Lernphase kein Grundrauschen. **Obergrenze** *F* ≤ 0.04 m/s²: Beginnt eine Messung unruhig, darf mässige Bewegung nicht als ruhig gelten. 0.04 ist rund das Doppelte des stillen Mittels im Befund; so bleibt die wirksame Leicht-Schwelle ≤ 0.1 und die Wach-Schwelle ≤ 0.2.
- **Wirksame Leicht-Schwelle** = max(Leicht-Schwelle, 2.5 · *F*), **wirksame Wach-Schwelle** = max(Wach-Schwelle, 5 · *F*). Ohne Grundrauschen gelten die Schwellen unverändert. Die Regel hebt nur an; die Kalibrierung (4) bleibt, wie sie ist.
- Am Befund: *F* ≈ 0.02 → Leicht-Schwelle 0.05 (über dem stillen Maximum 0.0287), Wach-Schwelle 0.1. Eine Uhr mit kleinem Rauschen (*F* ≤ 0.0008) behält die Startwerte.
- **Diagnose-Protokoll**: Am Ende jeder Messung im Weckfenster (`MEASURE_END`, Galaxy Watch und Apple Watch) `floor=… light=… wake=…` (Grundrauschen und wirksame Schwellen am Ende, «-» ohne Grundrauschen).

**Zielzeit erreicht**, ohne dass geweckt wurde → wecken, Grund `DEADLINE`. Das löst der Weck-Dienst über einen eigenen Alarm aus, nicht die Epochen-Regel. Wecker «Genau» und kurze Nächte: siehe 8.

## 4. Kalibrierung

Ab 3 aufgezeichneten Nächten mit Messwerten werden die Schwellen pro Nutzer angepasst. Verwendet werden die letzten 14 davon.

1. Nur Nächte mit mindestens einer nicht leeren Bewegung in den entscheidbaren Epochen zählen. Davon die letzten 14 nehmen und alle nicht leeren Bewegungen ihrer entscheidbaren Epochen sammeln. Weniger als 3 solche Nächte oder weniger als 30 Werte → Startwerte.
2. **Leicht-Schwelle** = 80. Perzentil dieser Werte (Rangmethode: aufsteigend sortieren, Wert an Position ⌈0.8 · n⌉, gezählt ab 1). Gedanke: In einer typischen Fensterzeit liegen rund 20 % der Epochen im Bereich leichter Bewegung.
3. Begrenzen auf das 0.5- bis 2-Fache des Startwerts (0.001 bis 0.004 m/s²).
4. **Wach-Schwelle** = Leicht-Schwelle × 50 (Verhältnis der Startwerte).

Die kalibrierten Werte sind die Grundlage; das Grundrauschen der laufenden Messung hebt sie bei Bedarf an (3a).

## 5. Testnächte

Format `shared/testdata/*.night` (Text, UTF-8):

```
# Kommentar
@expect 22 LIGHT_SLEEP
motion,hr
0.000,52
0.004,
,56
```

- `@floor <m/s²>` und `@resting <bpm>` (optional, vor der Kopfzeile): gelerntes Grundrauschen bzw. gelernter Ruhepuls (Abschnitt 10) für diese Nacht. Ohne Angabe gelten keine gelernten Werte.
- `@expect <index> <AWAKE|LIGHT_SLEEP>` – erste Epoche, die weckt, mit Grund (nur die Epochen-Regel aus 3; `EXACT` und `SHORT_NIGHT` aus 8 entstehen erst im Weck-Dienst). `@expect none` – keine Epoche weckt (Wecken erst zur Zielzeit).
- Danach die Kopfzeile `motion,hr` und eine Zeile pro Epoche; leeres Feld = leer.
- Die Nächte gelten mit den Startwerten (und dem Grundrauschen aus 3a). Die Nächte `noisy_watch_*` bilden eine Uhr mit Grundrauschen 0.02 m/s² nach (Befund 07.11.2026). Die Nächte `learned_*` prüfen die gelernten Werte (Abschnitt 10); ohne sie entscheiden sie anders (Test in beiden Kernen).

Die aktuellen Testnächte sind synthetisch und decken die Regelfälle ab. Echte, ohne Namen exportierte Nächte kommen dazu, sobald es sie gibt.

## 6. Einschlaf-Modus

Für Wecker mit Schlafdauer («nach 7½ Std.»). Ab «Gute Nacht» misst die Uhr Bewegung stromsparend in denselben 30-s-Epochen.

- **Einschlafzeit** = Beginn der ersten Strecke von 30 aufeinanderfolgenden Epochen (15 Min), in denen die Bewegung vorhanden und kleiner als die wirksame Leicht-Schwelle ist. Leere Bewegung unterbricht die Strecke.
- **Wirksame Leicht-Schwelle** wie in 3a, aus den bisher gemessenen Epochen dieser Messung; bei jeder neuen Epoche neu berechnet. Die ruhige Strecke muss mit der Schwelle gelten, die beim Entscheid gilt.
- **Zielzeit** = Einschlafzeit + Schlafdauer, höchstens die eingestellte Weckzeit.
- Die Messung dauert höchstens 2 Std. ab ihrem Beginn (auch nach dem Wiederaufnehmen) und endet spätestens 15 Min vor der eingestellten Weckzeit. **Spätes Einschlafen** (09.11.2026): Ist die Höchstdauer erreicht und hatte in den letzten 15 Min (30 Epochen) mindestens eine gemessene Epoche Bewegung an oder über der wirksamen Leicht-Schwelle, verlängert sie sich um 15 Min statt aufzugeben, wiederholt bis höchstens 4 Std. ab ihrem Beginn, nie über den Beginn des Weckfensters und nie über 15 Min vor der Weckzeit hinaus; unter 30 % Akku der Uhr nicht. Nach einem Prozessende jenseits der 2 Std. wird nicht wieder aufgenommen. Wird bis dahin keine Einschlafzeit gefunden, gilt die eingestellte Weckzeit.
- «Gute Nacht» ist nur möglich, wenn die eingestellte Weckzeit höchstens 16 Std. entfernt ist.
- **Messart (nur Galaxy Watch)**: Hat die Uhr einen aufweckenden Beschleunigungssensor, dessen Puffer mindestens 1500 Werte (60 s) fasst, misst sie durchgehend, bündelt bis 5 Min und hält keinen Wakelock; es gilt die Regel oben. Sonst misst sie in **Stichproben**: Alle 5 Min weckt ein Alarm die Uhr kurz vor einer Epochengrenze, sie misst 2 volle Epochen (60 s) unter einem kurzen Wakelock und schläft wieder. Nie ein Wakelock über die ganze Messung (Akku-Befund 03.11.2026: früher bis 16 Std., rund 6 % Akku pro Stunde).
- **Einschlafzeit in Stichproben**: Ungemessene Epochen zwischen den Stichproben unterbrechen die Strecke nicht und zählen nicht. Die ruhigen gemessenen Epochen müssen von der ersten bis zur letzten mindestens 30 Epochen (15 Min) umfassen, also 4 Stichproben. Eine gemessene leere oder bewegte Epoche oder eine Lücke von mehr als 20 Epochen (10 Min, z. B. ein verpasster Alarm) beginnt von vorn. Sind alle Epochen gemessen, ist das genau die Regel oben.
- **Diagnose-Protokoll**: Beginn mit Messart, alle 15 Min eine Zeile (aufweckender Sensor, erhaltene Werte, gemessene und davon leere Epochen, mittlere und grösste Bewegung, wirksame Schwelle `thr=`, Grundrauschen `floor=`, Wakelock-Sekunden, Akku), jede Verlängerung (`MEASURE_START … extended until …`, verhindert nur vom Akku: `BATTERY … not extended: battery …`) und immer das Ende mit Grund (eingeschlafen, aufgegeben, «max duration», abgebrochen, Fenster begonnen).

## 7. Zuordnung zu Epochen

Sensorwerte werden über ihren eigenen Zeitstempel (monotone Uhr) einer Epoche zugeordnet, nicht über den Zeitpunkt, an dem sie bei der App ankommen. So darf der Sensor Werte gebündelt liefern.

- Epoche *k* umfasst die Zeitstempel von Fensterbeginn + *k*·30 s (einschliesslich) bis Fensterbeginn + (*k*+1)·30 s (ausschliesslich).
- Werte vor dem Fensterbeginn, Werte für eine bereits abgeschlossene Epoche und Werte nach Epoche 2880 (Zeitsprung der Sensoruhr) werden verworfen. Epoche 2880 beginnt genau 24 Stunden nach Fensterbeginn und gilt noch; verworfen wird ab Fensterbeginn + 24 Std. 30 s.
- Ein Wert für eine spätere Epoche schliesst die laufende und alle dazwischen liegenden ab; dazwischen liegende Epochen ohne Werte sind leer.
- Liefert der Sensor nichts, schliesst ein Zeitgeber Epochen ab, deren Ende mindestens die Bündelungs-Verzögerung plus 2 s zurückliegt (Ende ≤ jetzt − Nachfrist; genau auf der Grenze wird schon abgeschlossen).
- Galaxy Watch: Die Bewegung kommt gebündelt bis 10 s verspätet, der Puls je nach Quelle ebenso gebündelt (Pulssensor) oder sofort (Health Services). Die Uhr hält deshalb jeden Pulswert zurück, bis Bewegungswerte mit gleichem oder späterem Zeitstempel da sind (höchstens Bündelungs-Verzögerung plus 2 s). Sonst schlösse ein früher Pulswert die Epoche, bevor ihre Bewegung da ist. Die Regel oben bleibt gleich.
- Apple Watch: Die Smart-Alarm-Sitzung (höchstens 30 Min) beginnt bei max(Fensterbeginn, Zielzeit − 25 Min). Epochen davor bleiben leer und zählen nicht für die Erkennung.

## 8. «Genau» und kurze Nächte

Leitsatz: Pünktlich ist das Produkt, früher ist Bonus.

- **«Genau»** (Weckfenster 0, `AlarmSpec.isExact`): Die Uhr misst trotzdem die letzten 30 Min (`MEASURE_MINUTES`) für den Verlauf, weckt aber nie früher. Entscheidungen der Epochen-Regel werden übergangen; geweckt wird zur Zielzeit mit Grund `EXACT`.
- **Kurze Nacht** (`ShortNight.blocksEarly`): Ist die Einschlafzeit bekannt und seit ihr weniger als Schlafziel − 60 Min vergangen (Ziel 7½ Std. → unter 6½ Std.), wird ein früher Moment (`AWAKE` oder `LIGHT_SLEEP`) übergangen und nicht nachgeholt; die Erkennung läuft mit allen bisherigen Werten weiter (`WakeDetector.rearm`). In den letzten 10 Min vor der Zielzeit (`ShortNight.LATE_MINUTES`) gilt die Sperre nicht mehr: Ein neuer Moment dort weckt mit seinem Grund (r1AD 10.11.2026; Beispiel: eingeschlafen 01:51, Fenster 07:27–07:57, Moment 07:32 übergangen, ein Moment ab 07:47 weckt). Kommt keiner, weckt die Uhr zur Zielzeit mit Grund `SHORT_NIGHT`, auch wenn die Sperre im Fenster sonst später endet. Ohne bekannte Einschlafzeit gilt keine Sperre.
- **Einschlafzeit** (nur Galaxy Watch): aus «Gute Nacht» oder aus der automatischen Messung. Für den nächsten gespeicherten Wecker (kein Powernap, kein Probewecker), dessen Zielzeit höchstens 16 Std. entfernt ist, beginnt sie 60 Min vor der Bettzeit (`ShortNight.autoOnsetStart`: Zielzeit − Schlafziel, im Einschlaf-Modus Zielzeit − Schlafdauer) mit derselben stromsparenden Messung wie in 6 und endet beim erkannten Einschlafen, spätestens 2 Std. nach ihrem Beginn (bei spätem Einschlafen verlängert wie in 6, höchstens 4 Std.) oder 15 Min vor der Zielzeit; danach startet sie in dieser Nacht nicht noch einmal. Unter 30 % Akku der Uhr beginnt sie nicht. Im Einschlaf-Modus legt sie wie «Gute Nacht» die Zielzeit fest; sonst wird nur die Einschlafzeit gemerkt. Ohne Sensorrechte oder vor dem ersten Entsperren misst die Uhr nichts, und der Wecker weckt wie bisher.
- **Apple Watch**: Sie misst nur in der 30-Min-Sitzung; die Einschlafzeit ist dort unbekannt, die Untergrenze gilt nicht.
- **Speicherformat**: `EXACT` und `SHORT_NIGHT` stehen in der Nacht zusätzlich als `DEADLINE` (Feld 5) und im angehängten Feld 8, damit ältere Versionen die Nacht weiter lesen; ein unbekannter Grund gilt als `DEADLINE`.

## 9. «Nacht aufzeichnen» (Datensammlung)

Nur Galaxy Watch, auf Wunsch (07.11.2026) und in der Lernphase automatisch (Abschnitt 10; Apple Watch nur in der Lernphase, siehe dort). Die Aufzeichnungen fliessen nicht in die Kalibrierung (Abschnitt 4); aus den Aufzeichnungen der Lernphase stammen die gelernten Werte (Abschnitt 10).

- Epochen wie in 1, 2 und 7: Bewegung aus derselben Messung wie im Fenster (25 Hz, gebündelt 10 s), Puls als Mittel der Werte der Epoche, leer ohne Werte. Epoche `i` deckt Beginn + i · 30 s ab.
- **Wakelock** (Audit F-01, 10.11.2026): Hat die Uhr einen aufweckenden Beschleunigungssensor mit mindestens 1500 Werten Puffer (wie in 6), bündeln Bewegung und Puls bis 5 Min und wecken die Uhr selbst; der Dienst hält dann je Bündel nur kurz einen Wakelock (höchstens 10 s) und prüft dabei die Grenzen. Sonst hält er wie bisher einen Wakelock über die ganze Aufzeichnung (die Galaxy Watch 8 Classic hat keinen solchen Sensor, `fifo=-1`). Die Berechnung der Epochen ist in beiden Fällen gleich. Diagnose: Startzeile mit `wl=batch` bzw. `wl=hold`. Akku gemessen 10.11.2026 (mit Wakelock und Puls über den Sensor): ca. 6 % pro Stunde (71 % → 24 % in 7¾ Std.). Aufzeichnungen der Lernphase messen nur in Stichproben (Abschnitt 10) und halten nur je Stichprobe kurz einen Wakelock (`wl=sample mode=sample`).
- Höchstens 12 Std. (1440 Epochen); endet von Hand, nach 12 Std. oder unter 15 % Akku der Uhr; in der Lernphase begonnen zusätzlich beim Wecken (Grund `wake`).
- Erkennt die Einschlaf-Messung während einer Aufzeichnung das Einschlafen, steht in ihr die Zeile `onset=<Beginn der Einschlafdauer>,<Einschlafzeit>` (ms; ältere Versionen überspringen sie).
- Nach einem Prozessende wird bis zur Wiederaufnahme mit leeren Epochen aufgefüllt; danach kann die Epochengrenze um bis zu 30 s versetzt sein.
- Läuft unabhängig von Fenster- und Einschlaf-Messung; beide dürfen zugleich messen und beeinflussen sich nicht.

## 10. Lernphase

Galaxy Watch mit Android-Handy (Entscheid r1AD 09.11.2026) und Apple Watch mit iPhone (seit 10.11.2026, Aufzeichnung unten unter «Apple»). Rechnet das Handy bzw. iPhone (`Learning` im Kotlin- und im Swift-Kern, gleiche Regel und Tests); die Uhr bekommt den Stand und die Werte (Android `/phasewake/learning`, Apple im Anwendungskontext). Die Erkennung (3, 3a, 6) nutzt die Werte in beiden Kernen gleich.

- **Ablauf**: Beginnt nie von selbst, nur mit Zustimmung (Audit F-02, 10.11.2026): Einrichtungsschritt «Lernphase» bzw. einmal eine Karte auf der Startseite («Lernphase starten» / «Später»), danach der Schalter «Lernphase» in den Einstellungen. Aus: Die Uhr zeichnet nicht mehr automatisch auf (eine laufende Aufzeichnung der Lernphase endet, Grund `phone`), gelernte Werte bleiben; wieder an: läuft mit den bisher gezählten Nächten weiter. Von vorn mit «Neu lernen» und «Verlauf löschen». «Verlauf löschen» verwirft die gelernten Werte sofort; bei «Neu lernen» gelten die bisherigen Werte (Handy und Uhr) weiter, bis die neuen 7 Nächte gezählt sind, dann ersetzen die neuen sie (r1AD 09.11.2026). Ist «Einschlafen automatisch erkennen» aus, zählen nur Nächte mit «Gute Nacht» auf der Uhr; die Karte der Lernphase sagt das. Solange sie läuft, beginnt mit jeder Einschlaf-Messung (automatisch oder «Gute Nacht», nicht beim Wiederaufnehmen) eine Aufzeichnung (9), wenn die Uhr mindestens 50 % Akku hat und keine läuft. **Stichproben** (r1AD 10.11.2026): Diese Aufzeichnung misst wie die Stichproben in 6 alle 5 Min (Alarm auf eine Epochengrenze) 2 volle Epochen (60 s) Bewegung und Puls ohne Bündelung unter einem kurzen Wakelock, dazwischen sind die Sensoren aus und die Epochen leer (Format unverändert); Diagnose `learn record start … mode=sample`. Schätzung etwa 3 % Akku pro Stunde (noch zu messen). Eine von Hand begonnene Aufzeichnung misst weiter durchgehend. Sie endet beim Wecken (Klingeln beendet) oder ohne Klingeln zur eingestellten Weckzeit, sobald keine Nacht mehr läuft; die übrigen Grenzen aus 9 bleiben. Eine von Hand begonnene Aufzeichnung zählt ebenso und endet wie bisher.
- **Gezählte Nacht**: Aufzeichnung ab dem Beginn der Lernphase mit mindestens 72 Epochen mit Bewegung (36 Stichproben), die von der ersten bis zur letzten mindestens 360 Epochen (3 Std.) überspannen (seit 10.11.2026; vorher 360 Epochen mit Bewegung; eine durchgehende Aufzeichnung ab 3 Std. zählt wie bisher); je Nachtdatum (Beginn − 12 Std.) nur eine, die mit den meisten solchen Epochen. Fertig nach 7 gezählten Nächten (nicht Kalendertagen); gelernt wird aus den letzten 7.
- **Grundrauschen** *P* = Median der Grundrauschen *F* der Nächte (3a über alle Epochen der Nacht, höchstens 0.04; bei gerader Anzahl Mittel der beiden mittleren). Es gilt, solange die laufende Messung (Fenster oder Einschlaf-Messung) weniger als 4 Werte hat, mit denselben Faktoren 2.5 und 5 (3a); danach gilt *F* der Messung. Die Schwellen sinken dadurch nie unter die Startwerte bzw. die Kalibrierung. Im Fenster wirkt es nur, wenn zu Beginn Bewegungswerte fehlen (mit Aufwärmphase hat Epoche 3 schon 4 Werte).
- **Ruhepuls** *R* = Median über die Nächte des 10. Perzentils ihrer Pulse (Rangmethode, Position ⌈0.1 · n⌉ = (n + 9) / 10); nur Nächte mit mindestens 60 Epochen mit Puls (30 Stichproben, seit 10.11.2026; vorher 120), mindestens 3 solche Nächte, sonst kein Ruhepuls. *R* ist der Basispuls, wenn die Aufwärmphase des Fensters keinen Puls hat (3); die Pulsbedingung bleibt aktueller Puls ≥ Basispuls × 1.05.
- **Einschlafdauer** je Nacht = Einschlafzeit − Beginn, in ganzen Minuten, höchstens 60; Beginn nach «Gute Nacht» der Tastendruck, bei der automatischen Messung die Bettzeit (eingestellte Weckzeit − Schlafdauer bzw. Schlafziel), frühestens der Beginn der Messung. Liegt die Einschlafzeit davor, zählt die Nacht nicht. Gelernt: Median (gerundet) ab 3 Nächten mit Wert, sonst keiner.
- **Schlafenszeit**: spätestens Zielzeit − Schlafdauer bzw. Schlafziel − Einschlafdauer (ohne gelernte 0), abgerundet auf 5 Min; frühestens 30 Min davor. Am Handy am Abend für den Wecker dieser Nacht, bis zum Ende der Spanne.
- **Anzeige in Worten**: «Schwellen angepasst», wenn 2.5 · *P* über der Leicht-Schwelle liegt, sonst «Standard-Schwellen bleiben».
- **Apple** (10.11.2026; `AppleLearnRecord`, `RecorderEpochs` im Swift-Kern, `LearnRecorder` auf der Uhr): watchOS lässt eine App nicht die ganze Nacht selbst messen. Gleiche Abläufe, Texte, Grenzen und Zählregel wie oben; anders ist nur, woher die Epochen kommen:
  - **Bewegung**: Die Uhr startet den Bewegungs-Rekorder des Systems (`CMSensorRecorder.recordAccelerometer`, 50 Hz, höchstens 12 Std., läuft auch bei geschlossener App; Werte 3 Tage abrufbar, bis 3 Min verzögert). Gestartet wird, sobald der Rekorder bis 30 Min nach der Zielzeit reicht (Zielzeit + 30 Min − 12 Std.), nur ab 50 % Akku, nur mit Erlaubnis für Bewegungsdaten und nicht, wenn ab dann keine 3 Std. bis zur Zielzeit bleiben. Auslöser: jedes Neuplanen der Uhr-App (geöffnet, Wecker oder Stand vom iPhone, Ende einer Nacht) und eine Hintergrund-Aktualisierung auf den frühesten Start (watchOS bestimmt den genauen Zeitpunkt). Ausgewertet wird wie auf der Galaxy Watch ab dem Beginn der Einschlaf-Messung (60 Min vor der Bettzeit, `ShortNight.autoOnsetStart`), frühestens ab dem Start, bis zum Ende des Klingelns (höchstens Zielzeit + 30 Min), ohne Klingeln bis zur Zielzeit. Nach dem Wecken holt die Uhr die Werte in Abschnitten von 10 Min ab (mit offener App bis 20 s, in der Hintergrund-Aktualisierung 2,5 s Rechenzeit je Durchgang, Stand gespeichert; nie während einer Nacht misst, klingelt oder schlummert) und rechnet je Epoche dieselbe Bewegung wie in 1 (Takt 35 ms: von 50 Hz bleibt jeder zweite Wert, rund 750 Differenzen je Epoche; Rauschen 0.05; ab 300 Differenzen), Zuordnung über die Wanduhr der Werte wie in 7. Beginnt ein Durchgang neu, fehlt die erste Differenz nach der Grenze. Wird die Lernphase ausgeschaltet, endet eine laufende Aufzeichnung ohne Auswertung (`record end phone`); «Verlauf löschen» verwirft sie.
  - **Puls**: aus Apple Health (Werte, die die Uhr in Ruhe selbst ablegt, Apple nennt etwa einen je 5 Min), Mittel je Epoche wie in 2. Ruhepuls je Nacht darum schon ab 36 Epochen mit Puls (`Learning.minHeartRateEpochsApple`: ein Wert je 5 Min über 3 Std., wie die 36 Stichproben einer gezählten Nacht); sonst wie oben (10. Perzentil, Median ab 3 Nächten).
  - **Einschlafdauer**: Die Apple Watch hat keine Einschlaf-Messung. Das iPhone trägt sie aus Apple Health nach, sobald dort der Schlaf liegt: Beginn = früheste Probe «Im Bett», frühestens der Beginn der Aufzeichnung; Einschlafzeit = früheste Schlafprobe (jede Phase ausser «Wach»); nur Proben von Apple (Apple Watch, iPhone), die sich mit der Aufzeichnung überschneiden. Sonst gleiche Regel (höchstens 60 Min, verworfen, wenn die Einschlafzeit davor liegt, Median ab 3 Nächten). Ohne «Im Bett» (kein Schlafplan) oder ohne Zugriff auf Apple Health bleibt sie ungelernt.
  - **Gezählte Nacht**: dieselbe Regel (72 Epochen mit Bewegung über mindestens 3 Std.); der Rekorder liefert durchgehend, eine Nacht zählt also ab 3 Std. mit Werten.
  - **Abgleich**: Die fertige Aufzeichnung geht als Benutzerdaten im Format «phasewake-recording 1» (wie Android) ans iPhone, das die letzten 14 privat speichert; die Uhr behält sie nicht. Stand und Werte gehen im Anwendungskontext an die Uhr (`learnActive`, `learnFloor`, `learnRestingHr`, `learnUpdatedAt`; die Uhr übernimmt nur einen neueren Stand), die Smart-Alarm-Sitzung rechnet damit.
  - **Diagnose**: Uhr `learn record start bat=… mode=recorder`, `learn record skipped: battery …% / too late / recorder unavailable / motion not allowed`, `record end wake epochs=… motion=… hr=…`, `record end phone`, `learning active=… floor=… hr=…`; iPhone wie Android (`learn consent …`, `learn night counted N/7`, `learn done …`, `learn relearn`, `learn restarted`).

## 11. Schlafwert

Nur zur Orientierung, keine medizinische Bewertung. Rechnet der Kern (`SleepScore`, Android und Swift gleich). Jeder Faktor gibt 0–100 Punkte (ganze Zahl, kaufmännisch gerundet); Prozente sind die Anteile der Phasen wie auf der Detailseite (wach, REM, leicht, tief zusammen 100).

| Faktor | Gewicht | 100 Punkte | 0 Punkte | dazwischen |
|---|---|---|---|---|
| Schlafdauer (ohne Wachphasen) | 35 | ab Schlafziel | höchstens halbes Ziel | gerade |
| Tief | 20 | ab 13 % | 0 % | anteilig (Prozent / 13) |
| REM | 20 | ab 20 % | 0 % | anteilig (Prozent / 20) |
| Wach | 15 | bis 9 % | ab 30 % | gerade |
| Einschlafdauer | 10 | bis 20 Min | ab 60 Min | gerade |

- **Wert** = Σ (Punkte · Gewicht) / Σ Gewichte der vorhandenen Faktoren, gerundet. Fehlt ein Faktor (keine Phasen, keine Einschlafdauer), fällt er weg; ohne Schlafdauer kein Wert. Kein Abzug für Schlaf über dem Ziel. Die Grenzen von Tief, REM und Wach sind die üblichen Bereiche der Detailseite (`StageStats.TYPICAL`).
- **Vorläufig**: Fehlen alle Schlafphasen (weder Tief noch REM noch Wach, z. B. nur Schlafdauer und Einschlafdauer aus einer Aufzeichnung oder einer Sitzung ohne Phasen), ist der Wert vorläufig und höchstens 84 (also höchstens «Gut»): Dauer und Einschlafdauer allein sagen zu wenig über die Qualität des Schlafs. Die Detailseite zeigt unter dem Bogen «Ohne Schlafphasen – vorläufiger Wert», die Liste den Ring gestrichelt. Die Punkte der Faktoren bleiben unverändert.
- **Bewertung** (Wert und jeder Faktor): ab 85 «Ausgezeichnet», ab 70 «Gut», ab 50 «Mittel», sonst «Schwach».
- **Quellen**: Phasen aus Health Connect (eigene Nacht über das Schlafprofil, Nacht aus einer anderen App über die ganze Sitzung; Sitzung ohne Phasen nur ihre Dauer). Ohne Phasen bei einer eigenen Nacht die überschneidende Aufzeichnung der Uhr (9), wenn sie das Einschlafen kennt: Schlafdauer = Einschlafzeit bis Ende (höchstens bis zum Wecken), Einschlafdauer = Beginn bis Einschlafzeit. Unruhige Epochen zählen nicht als «Wach».
- **Beispiel**: 6 Std. 45 Min bei Ziel 7 Std. 30 Min (80), Tief 10 % (77), REM 20 % (100), Wach 15 % (71), Einschlafdauer 40 Min (50) → (80·35 + 77·20 + 100·20 + 71·15 + 50·10) / 100 = 79 → «Gut».
- **Beispiel vorläufig**: 8 Std. bei Ziel 7 Std. 30 Min (100), Einschlafdauer 12 Min (100), keine Phasen → gerechnet 100, vorläufig 84 → «Gut».
- **Säulen «Dein Schlaf»** (`SleepOverviews`): Eine Säule wird nur nach Tief, Leicht und REM aufgeteilt, wenn alle ihre Nächte Phasen haben (Woche/Monat); sonst zeigt sie nur die Dauer, neben aufgeteilten Säulen neutral mit der Legende «Ohne Phasen».
