# Phase Wake testen

Die Testversion baut GitHub bei jeder Änderung neu:
**https://github.com/r1adBE/phaseWake/releases/tag/test**

Dort liegen zwei Dateien:

- `PhaseWake-Handy.apk` – für das Android-Handy
- `PhaseWake-Uhr.apk` – für die Galaxy Watch

## 1. Handy

1. Auf dem Handy die Seite oben öffnen und `PhaseWake-Handy.apk` herunterladen.
2. Die Datei öffnen. Beim ersten Mal fragt Android, ob der Browser Apps installieren darf: **Einstellungen → Zulassen**, zurück, **Installieren**.
3. Phase Wake öffnen, Mitteilungen erlauben und einen Wecker anlegen. Zeigt der Nacht-Check einen roten Punkt, auf dessen Knopf tippen und die Erlaubnis geben.

## 2. Uhr (einmalig einrichten)

Die Uhr hat keinen Browser für Apps; die Datei kommt vom Computer über WLAN. Uhr und Computer müssen im **gleichen WLAN** sein.

1. **Platform-Tools** von Google auf den Computer laden und entpacken: https://developer.android.com/tools/releases/platform-tools
   Darin liegt `adb`. Im entpackten Ordner ein Terminal öffnen (Windows: Rechtsklick → «Im Terminal öffnen»).
2. Auf der Uhr **Einstellungen → Info zur Uhr → Softwareinformationen** öffnen und **5× auf «Softwareversion»** tippen. Jetzt gibt es **Entwickleroptionen**.
3. **Einstellungen → Entwickleroptionen**: **ADB-Debugging** und **Kabelloses Debugging** einschalten.
4. **Kabelloses Debugging → Neues Gerät koppeln.** Die Uhr zeigt eine Adresse wie `192.168.1.23:41234` und einen sechsstelligen Code.
5. Am Computer (Adresse und Code von der Uhr):

   ```sh
   adb pair 192.168.1.23:41234
   ```

   Code eingeben.
6. Unter **Kabelloses Debugging** zeigt die Uhr eine zweite Adresse (IP-Adresse und Port). Damit verbinden:

   ```sh
   adb connect 192.168.1.23:37555
   ```

## 3. Uhr-App installieren

`PhaseWake-Uhr.apk` am Computer herunterladen, in den Platform-Tools-Ordner legen und:

```sh
adb install -r PhaseWake-Uhr.apk
```

«Success» heisst: installiert. Phase Wake auf der Uhr öffnen und alle Berechtigungen erlauben: Sensoren (Puls, Bewegung), Mitteilungen und danach «Puls im Hintergrund» («Immer erlauben»). Zeigt die Uhr den Hinweis weiter, nochmals antippen: Dann öffnen sich die Einstellungen der App bzw. die Erlaubnis für den Weckbildschirm.

Für spätere Testversionen genügen Schritt 6 (`adb connect …`) und Schritt 3.

## 4. Erste Probe

1. Auf dem Handy muss der Nacht-Check **«Bereit für die Nacht»** zeigen.
2. Wecker auf **jetzt + 15 Minuten** stellen, Fenster **10 Minuten**.
3. Nach 5 Minuten beginnt die Uhr zu messen (Symbol in der Statusleiste der Uhr). Spätestens zur Zielzeit vibriert sie.
4. Danach auf dem Handy unter «Dein Morgen» das Befinden antippen; im Verlauf steht die Nacht.

Schneller: In den Einstellungen oder auf dem Nacht-Check **«In 2 Minuten testen»**. Die Uhr klingelt nach 2 Minuten (ohne Schlummern, beenden durch Halten), danach zeigt das Handy, was geklappt hat. Der Test erscheint nicht im Verlauf.

### Wecker bei eingeschaltetem und bei gesperrtem Bildschirm, Uhr und Handy

Je einmal «In 2 Minuten testen» (Handy-Wecker eingeschaltet), Weckbildschirm nur durch Halten beenden:

| Fall | Uhr | Handy | Erwartet |
|---|---|---|---|
| A | Display aus (Arm unten, auch mit Always-on) | gesperrt, Display aus | Beide zeigen den Weckbildschirm von selbst als Vollbild. |
| B | Display an, Zifferblatt oder andere App | entsperrt, eine andere App offen | Mitteilung oben mit «Öffnen». Wird nichts angetippt: Display aus­gehen lassen bzw. Handy sperren – spätestens nach 20 s kommt der Weckbildschirm als Vollbild. |
| C | Phase Wake offen | Phase Wake offen | Der Weckbildschirm erscheint sofort. |
| D | nach «Schlummern» (echter Wecker, Display aus) | nach «Schlummern» am Handy-Zweitwecker | Nach 5 Min wieder Vollbild wie in A. |

Danach im Diagnose-Protokoll je Gerät: `RING_SCREEN … FULL_SCREEN|HEADS_UP|DIRECT screen=… locked=… fsi=…` und
`RING_SCREEN … shown via fullscreen|alarm|direct|tap`. Fehlt «shown via», kam der Weckbildschirm nicht; `fsi=false`
heisst: Erlaubnis für den Weckbildschirm fehlt (Nacht-Check bzw. Hinweis auf der Uhr).

Bei einem Fehler: In den Einstellungen 7× auf die Versionszeile tippen, dann «Diagnose» → «Uhr-Protokoll holen» → «Protokoll teilen» und den Text mitschicken.

## Was zurückmelden

- Hat die Uhr geweckt – und wann (früher oder zur Zielzeit)?
- Steht im Morgenbericht «Gemessen: Bewegung und Puls» oder «Nur Bewegung»?
- Hat das Handy mitgeklingelt?
- Alles, was komisch aussah – am besten mit Bildschirmfoto, als Issue auf GitHub.

## Hinweise

- Testversionen sind mit einem öffentlichen Test-Schlüssel signiert (`android/testing`). Eine spätere Store-Version lässt sich deshalb nicht darüber installieren: vorher die Testversion deinstallieren.
- Kabelloses Debugging nach dem Test wieder ausschalten; es braucht Akku.
