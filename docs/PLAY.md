# Play Console: Erklärungen zu Berechtigungen

Entwürfe für die Formulare der Play Console (App-Inhalte, Berechtigungen, Gesundheits-Apps), abgeleitet aus
`android/app/src/main/AndroidManifest.xml` (Handy) und `android/wear/src/main/AndroidManifest.xml` (Galaxy Watch).
Stand 02.11.2026 (Review W-07). Vor dem Einreichen gegen die aktuellen Manifeste und die Formularfelder prüfen; die
Play Console verlangt meist englischen Text, die deutsche Fassung dient zur Kontrolle.

Ohne Erklärung: `VIBRATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `USE_BIOMETRIC`. Kein
`INTERNET` und kein `ACCESS_NETWORK_STATE` (in beiden Manifesten entfernt): Die App sendet nichts.

## Übersicht

| Berechtigung / Erklärung | Handy | Uhr | Wofür |
|---|---|---|---|
| `USE_EXACT_ALARM` (ab Android 13), `SCHEDULE_EXACT_ALARM` (bis Android 12L) | ✓ | ✓ | Wecker zur eingestellten Zeit |
| `USE_FULL_SCREEN_INTENT` | ✓ | ✓ | Weckbildschirm bei gesperrtem Gerät |
| Vordergrund-Dienst `systemExempted` | ✓ | ✓ | Klingeln bzw. Wecken ohne Messung |
| Vordergrund-Dienst `health` | – | ✓ | Messung im Weckfenster |
| Vordergrund-Dienst `mediaPlayback` | ✓ | – | Einschlafklänge |
| Vordergrund-Dienst `microphone`, `RECORD_AUDIO` | ✓ | – | Geräusche in der Nacht (Option) |
| `BODY_SENSORS` (bis Wear OS 5), `BODY_SENSORS_BACKGROUND` (bis Wear OS 5) | – | ✓ | Puls im Weckfenster |
| Health Connect `READ_HEART_RATE`, `READ_HEALTH_DATA_IN_BACKGROUND` (ab Wear OS 6) | – | ✓ | Puls im Weckfenster |
| Health Connect `READ_SLEEP` | ✓ | – | Schlafphasen nachträglich anzeigen (Option) |
| `ACTIVITY_RECOGNITION` | – | ✓ | Voraussetzung des Vordergrund-Dienstes `health` |
| `POST_NOTIFICATIONS` | ✓ | ✓ | Wecker, Nächster Wecker, Erinnerungen |
| Begleit-App (CompanionDeviceManager), `BLUETOOTH_CONNECT` | ✓ | – | Uhr als Begleitgerät verknüpfen |

## Deutsch

**Exakte Alarme (`USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM`).** Phase Wake ist eine Wecker-App. Kernfunktion ist ein
Wecker, den du einstellst: Die Uhr beginnt zur Startzeit des Weckfensters zu messen und weckt spätestens zur Zielzeit;
das Handy klingelt als Zweitwecker, wenn die Uhr nicht weckt. Beides braucht Alarme auf die Minute genau, auch im
Ruhemodus des Geräts. Die Alarme erscheinen im System als Wecker (Uhrzeit in der Statusleiste).

**Vollbild-Benachrichtigung (`USE_FULL_SCREEN_INTENT`).** Nur beim Wecken: Der Weckbildschirm mit «Schlummern» und
«Beenden» erscheint auch bei gesperrtem Handy bzw. dunkler Uhr. Keine anderen Vollbild-Meldungen.

**Vordergrund-Dienst «systemExempted» (Handy und Uhr).** Läuft nur, während ein Wecker klingelt oder schlummert
(Handy: Zweitwecker oder Weiterleitung der Uhr; Uhr: bei jedem Klingeln und Schlummern, dazu im Weckfenster, wenn nicht
gemessen werden kann – vor dem ersten Entsperren nach einem Neustart oder ohne Sensorrechte). Er hält Ton, Vibration und
Weckbildschirm am Leben, bis du beendest oder schlummerst. Als Wecker-App mit `USE_EXACT_ALARM` ist dieser Typ vorgesehen.

**Vordergrund-Dienst «health» (Uhr).** Läuft nur im Weckfenster (10–30 Minuten vor der Zielzeit) und kurz am Abend
bis zum Einschlafen (Einschlaf-Messung, ab Werk an, in den Einstellungen abschaltbar). Er liest Bewegung (Beschleunigungssensor) und Puls, um einen leichten Schlafmoment zum
Wecken zu finden. Die Messung zeigt eine laufende Benachrichtigung und endet mit dem Wecken.

**Vordergrund-Dienst «mediaPlayback» (Handy).** Einschlafklänge (Rauschen, Regen, Meer …), die du startest; mit
Medien-Benachrichtigung und Timer. Endet beim Stoppen oder nach Ablauf des Timers.

**Mikrofon (`RECORD_AUDIO`, Vordergrund-Dienst «microphone», Handy).** Nur wenn du «Geräusche aufnehmen» für eine
Nacht einschaltest (standardmässig aus). Die App misst die Lautstärke und behält kurze Ausschnitte lauter Geräusche
(höchstens 10 Sekunden), die ein Modell auf dem Handy als mögliches Schnarchen oder Sprechen einordnet. Alles bleibt
auf dem Handy, nichts wird gesendet, Ausschnitte werden nach 7 Tagen gelöscht. Die Aufnahme zeigt eine Benachrichtigung
und endet spätestens 30 Minuten nach dem Wecker.

**Körpersensoren und Puls (`BODY_SENSORS`, `BODY_SENSORS_BACKGROUND`, `health.READ_HEART_RATE`,
`health.READ_HEALTH_DATA_IN_BACKGROUND`, Uhr).** Der Puls im Weckfenster hilft zu erkennen, ob du wach oder leicht
schläfst. Das Weckfenster beginnt per Alarm bei dunklem Display, darum im Hintergrund. Die Werte bleiben auf Uhr und
Handy (Verlauf), werden nicht gesendet und nicht in Health Connect geschrieben. Ohne das Recht misst die Uhr nur
Bewegung.

**Health Connect (Datentypen und Begründung).** Lesen, nie Schreiben:
- Handy: Schlaf (`READ_SLEEP`) – optional, um die Schlafphasen einer Nacht nachträglich im Verlauf zu zeigen und mit dem
  Weckmoment zu vergleichen, und für Trends (Schlafdauer, Regelmässigkeit). Die Daten bleiben in Health Connect und
  werden nicht kopiert, gesichert oder gesendet.
- Uhr: Herzfrequenz (`READ_HEART_RATE`) und Lesen im Hintergrund (`READ_HEALTH_DATA_IN_BACKGROUND`) – ab Wear OS 6 der
  Ersatz für `BODY_SENSORS`, nur für den Puls im Weckfenster (siehe oben).
Die Begründungsseite (`ACTION_SHOW_PERMISSIONS_RATIONALE`) und die Datenschutzerklärung (docs/DATENSCHUTZ.md) liegen vor.

**Aktivitätserkennung (`ACTIVITY_RECOGNITION`, Uhr).** Voraussetzung des Vordergrund-Dienstes «health» für die
Bewegungsmessung im Weckfenster (auch ohne Pulsrecht). Keine Erkennung von Sport oder Schritten.

**Benachrichtigungen (`POST_NOTIFICATIONS`).** Wecker und Schlummern, laufende Messung bzw. Aufnahme, auf Wunsch
«Nächster Wecker», Bettzeit-Erinnerung und Wochenrückblick. Keine Werbung.

**Begleit-App (CompanionDeviceManager, `BLUETOOTH_CONNECT`, Handy).** Nur auf Knopfdruck: Das Handy verknüpft sich mit
deiner Galaxy Watch als Begleit-App, damit der Weckbildschirm auch bei benutztem Handy nach vorn kommen darf.
`BLUETOOTH_CONNECT` liest die gekoppelten Geräte nur, um die Uhr direkt zur Bestätigung anzubieten; nichts wird
gespeichert oder gesendet.

## English

**Exact alarms (`USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM`).** Phase Wake is an alarm clock app. Its core function is
an alarm the user sets: the watch starts measuring when the wake window begins and wakes the user at the target time at
the latest; the phone rings as a backup alarm if the watch does not. Both need alarms to the minute, also in Doze. The
alarms are shown by the system as alarm clocks (time in the status bar).

**Full-screen intent (`USE_FULL_SCREEN_INTENT`).** Only when an alarm rings: the alarm screen with “Snooze” and “Stop”
appears even when the phone is locked or the watch screen is off. No other full-screen notifications.

**Foreground service type “systemExempted” (phone and watch).** Runs only while an alarm rings or is snoozed (phone:
backup alarm or forwarded watch alarm; watch: on every ring and snooze, plus during the wake window when measuring is not
possible – before the first unlock after a restart or without sensor permissions). It keeps sound,
vibration and the alarm screen alive until the user stops or snoozes. This type is intended for alarm clock apps
holding `USE_EXACT_ALARM`.

**Foreground service type “health” (watch).** Runs only during the wake window (10–30 minutes before the target time)
and briefly in the evening until the user falls asleep (falling-asleep measurement, on by default, can be switched off in
the settings). It reads movement (accelerometer) and heart rate to
find a light-sleep moment to wake the user. A notification is shown while measuring; it ends when the user wakes.

**Foreground service type “mediaPlayback” (phone).** Sleep sounds (noise, rain, sea …) started by the user, with a media
notification and a timer. Ends when stopped or when the timer runs out.

**Microphone (`RECORD_AUDIO`, foreground service type “microphone”, phone).** Only if the user turns on “Record
sounds” for a night (off by default). The app measures loudness and keeps short clips of loud sounds (at most 10
seconds), which an on-device model classifies as possible snoring or talking. Everything stays on the phone, nothing is
sent, clips are deleted after 7 days. Recording shows a notification and ends at the latest 30 minutes after the alarm.

**Body sensors and heart rate (`BODY_SENSORS`, `BODY_SENSORS_BACKGROUND`, `health.READ_HEART_RATE`,
`health.READ_HEALTH_DATA_IN_BACKGROUND`, watch).** Heart rate during the wake window helps tell whether the user is
awake or sleeping lightly. The wake window starts from an alarm while the screen is off, hence in the background. Values
stay on the watch and phone (history), are never sent and never written to Health Connect. Without the permission the
watch measures movement only.

**Health Connect (data types and rationale).** Read only, never write:
- Phone: Sleep (`READ_SLEEP`) – optional, to show the sleep stages of a night afterwards in the history, compare them
  with the wake moment, and for trends (sleep duration, regularity). The data stays in Health Connect and is not copied,
  backed up or sent.
- Watch: Heart rate (`READ_HEART_RATE`) and background reads (`READ_HEALTH_DATA_IN_BACKGROUND`) – from Wear OS 6 the
  replacement for `BODY_SENSORS`, only for heart rate during the wake window (see above).
The rationale screen (`ACTION_SHOW_PERMISSIONS_RATIONALE`) and the privacy policy (docs/DATENSCHUTZ.md) are in place.

**Activity recognition (`ACTIVITY_RECOGNITION`, watch).** Prerequisite of the “health” foreground service for measuring
movement during the wake window (also without the heart-rate permission). No detection of workouts or steps.

**Notifications (`POST_NOTIFICATIONS`).** Alarms and snoozing, ongoing measurement or recording, and optionally “Next
alarm”, bedtime reminder and weekly review. No advertising.

**Companion device (CompanionDeviceManager, `BLUETOOTH_CONNECT`, phone).** Only when the user taps the button: the phone
associates with the user’s Galaxy Watch as its companion app, so the alarm screen may come to the front even while the
phone is in use. `BLUETOOTH_CONNECT` reads the paired devices only to offer the watch directly for confirmation; nothing
is stored or sent.
