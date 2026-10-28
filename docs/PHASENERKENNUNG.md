# Phasenerkennung – Spezifikation

Diese Datei ist die verbindliche Regel. Android (`android/core/`, Kotlin) und Apple (`ios/PhaseWakeCore/`, Swift) setzen sie identisch um. Beide laufen gegen dieselben Testnächte in `shared/testdata/` und müssen dort dieselbe Epoche und denselben Grund liefern. Wer die Regel ändert, ändert diese Datei, beide Umsetzungen und die Testnächte im selben Commit.

## Begriffe

- **Epoche**: 30 Sekunden. Jede Epoche hat eine Bewegung (`motion`, in m/s², oder leer) und einen Puls (`heartRate`, in Schlägen pro Minute, oder leer).
- **Aufwärmphase**: die ersten 2 Epochen (1 Minute) des Weckfensters. Sie werden nur beobachtet.
- **Entscheidbare Epochen**: alle Epochen ab Index 2. Nur sie zählen in den Regeln unten.

## 1. Bewegung pro Epoche

Eingang: Beschleunigungswerte (x, y, z in m/s², Zeitstempel in Nanosekunden).

1. **Takt begrenzen**: Ein Wert wird nur übernommen, wenn seit dem zuletzt übernommenen mindestens 35 ms vergangen sind. So messen beide Plattformen mit höchstens rund 28 Hz, auch wenn der Sensor schneller liefert.
2. **Differenz**: Für jeden übernommenen Wert (ausser dem allerersten) wird der Abstand zum vorherigen übernommenen Wert berechnet: `d = √((x−x₀)² + (y−y₀)² + (z−z₀)²)`. Liegen zwischen den beiden Werten mehr als 1 s, wird diese Differenz verworfen (Sensorlücke).
3. **Rauschen abziehen**: `max(0, d − 0.05)`.
4. **Bewegung der Epoche** = Mittelwert dieser Werte über alle Differenzen der Epoche.
5. **Zu wenig Daten**: Weniger als 300 Differenzen in der Epoche → Bewegung ist leer.

Der letzte übernommene Wert bleibt über das Epochenende hinaus Bezugspunkt für die nächste Differenz.

## 2. Puls pro Epoche

Mittelwert aller Pulswerte der Epoche. Werte ≤ 0 oder > 250 werden ignoriert. Kein gültiger Wert → Puls leer.

## 3. Entscheidung je Epoche

Startwerte (werden später kalibriert, siehe 4):

| Name | Wert |
|---|---|
| Leicht-Schwelle | 0.002 m/s² |
| Wach-Schwelle | 0.100 m/s² |
| Pulsanstieg | 5 % |

**Basispuls** = Mittelwert der vorhandenen Pulse der Aufwärmphase. Hat die Aufwärmphase keinen Puls, bleibt der Basispuls für das ganze Fenster leer.

Für jede Epoche mit Index *i*, in dieser Reihenfolge:

1. **Bereits geweckt** → dieselbe Weck-Entscheidung wie zuvor.
2. **Aufwärmphase** (*i* < 2) → warten.
3. **Wach**: Unter den letzten 3 entscheidbaren Epochen (inkl. *i*) haben mindestens 2 eine Bewegung ≥ Wach-Schwelle → wecken, Grund `AWAKE`.
4. **Leichtschlaf**: Unter den letzten 4 entscheidbaren Epochen (inkl. *i*) haben mindestens 2 eine Bewegung ≥ Leicht-Schwelle **und** die Pulsbedingung ist erfüllt → wecken, Grund `LIGHT_SLEEP`.
   - **Aktueller Puls** = Mittelwert der vorhandenen Pulse der Epochen *i* und *i−1* (nur entscheidbare).
   - **Pulsbedingung**: Sind Basispuls und aktueller Puls vorhanden, muss gelten: aktueller Puls ≥ Basispuls × 1.05. Fehlt einer der beiden, gilt die Bedingung als erfüllt (dann entscheidet die Bewegung allein).
5. Sonst → warten.

Leere Bewegung zählt nie als Treffer.

**Zielzeit erreicht**, ohne dass geweckt wurde → wecken, Grund `DEADLINE`. Das löst der Weck-Dienst über einen eigenen Alarm aus, nicht die Epochen-Regel.

## 4. Kalibrierung

Ab 3 aufgezeichneten Nächten werden die Schwellen pro Nutzer angepasst. Verwendet werden die letzten 14 Nächte.

1. Alle nicht leeren Bewegungen der entscheidbaren Epochen dieser Nächte sammeln. Weniger als 3 Nächte oder weniger als 30 Werte → Startwerte.
2. **Leicht-Schwelle** = 80. Perzentil dieser Werte (Rangmethode: aufsteigend sortieren, Wert an Position ⌈0.8 · n⌉, gezählt ab 1). Gedanke: In einer typischen Fensterzeit liegen rund 20 % der Epochen im Bereich leichter Bewegung.
3. Begrenzen auf das 0.5- bis 2-Fache des Startwerts (0.001 bis 0.004 m/s²).
4. **Wach-Schwelle** = Leicht-Schwelle × 50 (Verhältnis der Startwerte).

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

- `@expect <index> <AWAKE|LIGHT_SLEEP>` – erste Epoche, die weckt, mit Grund. `@expect none` – keine Epoche weckt (Wecken erst zur Zielzeit).
- Danach die Kopfzeile `motion,hr` und eine Zeile pro Epoche; leeres Feld = leer.
- Die Nächte gelten mit den Startwerten.

Die aktuellen Testnächte sind synthetisch und decken die Regelfälle ab. Echte, anonym exportierte Nächte kommen dazu, sobald es sie gibt.

## 6. Einschlaf-Modus

Für Wecker mit Schlafdauer («nach 7½ Std.»). Ab «Gute Nacht» misst die Uhr Bewegung stromsparend in denselben 30-s-Epochen.

- **Einschlafzeit** = Beginn der ersten Strecke von 30 aufeinanderfolgenden Epochen (15 Min), in denen die Bewegung vorhanden und kleiner als die Leicht-Schwelle ist. Leere Bewegung unterbricht die Strecke.
- **Zielzeit** = Einschlafzeit + Schlafdauer, höchstens die eingestellte Weckzeit.
- Wird bis 15 Min vor der eingestellten Weckzeit keine Einschlafzeit gefunden, gilt die eingestellte Weckzeit.
