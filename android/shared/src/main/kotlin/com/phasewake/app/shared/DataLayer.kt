package com.phasewake.app.shared

/** Pfade und Schlüssel der Verbindung Handy ↔ Uhr (Wearable Data Layer). Beide Apps nutzen genau diese. */
object DataLayer {
    /** Datenpaket mit allen Weckern; Schlüssel [KEY_TEXT], [KEY_UPDATED_AT]. Schreiben beide Seiten. */
    const val PATH_ALARMS = "/phasewake/alarms"

    /**
     * Präfix der Nacht-Pakete der Uhr, gefolgt von «fensterbeginnMs-kennung» (Kennung URL-kodiert).
     * Ältere Uhren schicken nur den Fensterbeginn; gelesen wird deshalb nach Präfix.
     */
    const val PATH_NIGHT_PREFIX = "/phasewake/night/"

    /** Nachricht Uhr → Handy: Weckvorgang hat begonnen; Inhalt = [RingMessage]. */
    const val PATH_RING = "/phasewake/ring"

    /** Nachricht Uhr → Handy oder Handy → Uhr: Weckvorgang beendet oder auf der Uhr geschlummert; Inhalt = [RingMessage] ohne Schlummer-Runden. */
    const val PATH_STOP = "/phasewake/stop"

    /**
     * Nachricht Handy → Uhr: am Handy wurde Schlummern gewählt. Hat die Uhr keine Runde mehr,
     * antwortet sie mit [PATH_RING], damit das Handy weiterklingelt.
     */
    const val PATH_SNOOZE = "/phasewake/snooze"

    /**
     * Nachricht Handy → Uhr: Die Weckaufgabe ist auf dem Handy wirklich zu sehen; Inhalt = [RingMessage]
     * (Kennung, Zielzeit). Kommt sofort und dann alle [TASK_SHOWN_REFRESH_MS], solange sie sichtbar ist.
     * Erst damit schaltet die Uhr das Halten ab; bleibt die Meldung aus, gilt dort wieder Halten.
     */
    const val PATH_TASK_SHOWN = "/phasewake/task-shown"

    /**
     * Nachricht Handy → Uhr: Das Handy-Klingeln mit Weckaufgabe endete, ohne dass sie gelöst wurde
     * (Zeitgrenze, Dienst beendet). Die Uhr erlaubt sofort wieder Halten. Inhalt = [RingMessage].
     */
    const val PATH_TASK_GONE = "/phasewake/task-gone"

    /** So oft wiederholt das Handy [PATH_TASK_SHOWN]. */
    const val TASK_SHOWN_REFRESH_MS = 30_000L

    /** So lange gilt eine Meldung [PATH_TASK_SHOWN] auf der Uhr; danach wieder Halten. */
    const val TASK_SHOWN_VALID_MS = 60_000L

    /** Nur so lange nach dem Klingeln schaltet eine erste Meldung [PATH_TASK_SHOWN] auf «Aufgabe am Handy». */
    const val TASK_CONFIRM_MS = 10_000L

    /**
     * Datenpaket Handy → Uhr: Verlauf auf Wunsch des Nutzers löschen; Schlüssel [KEY_UPDATED_AT].
     * Ein Paket statt einer Nachricht, damit es auch eine gerade nicht erreichbare Uhr erreicht.
     */
    const val PATH_CLEAR_HISTORY = "/phasewake/clear-history"

    /**
     * Datenpaket Uhr → Handy: Ist die Uhr bereit für die Nacht (Sensoren, Mitteilungen und Weckbildschirm erlaubt)?
     * Schlüssel [KEY_READY], [KEY_UPDATED_AT]. Der Nacht-Check am Handy zeigt es an.
     */
    const val PATH_WATCH_STATUS = "/phasewake/watch-status"
    const val KEY_READY = "ready"

    /** Akkustand der Uhr in Prozent (0–100), im selben Paket wie [KEY_READY]; fehlt bei älteren Versionen. */
    const val KEY_BATTERY = "battery"

    /**
     * Felder je Weckerzeile, die die Uhr kennt (`Codec.ALARM_FIELDS`), im selben Paket wie [KEY_READY]. Fehlt bei
     * Uhren vor «Überspringen» und «Feiertage»; das Handy blendet beides dann aus (`Codec.watchKnowsSkip`).
     */
    const val KEY_ALARM_FIELDS = "alarmFields"

    /**
     * Stand der Verbindung Handy ↔ Uhr (Review O-04), wie eine Formatversion: steigt, wenn eine ältere Uhr etwas, das das
     * Handy schickt, nicht mehr wie eingestellt umsetzt (z. B. die Weckart «Genau»). Die Uhr meldet ihren Stand im
     * Status-Paket ([KEY_PROTOCOL]); ist er kleiner als der des Handys, warnt der Nacht-Check (`WearSync.watchOutdated`).
     */
    const val PROTOCOL = 1

    /** [PROTOCOL] der Uhr im selben Paket wie [KEY_READY]; fehlt bei Uhren vor dieser Angabe (gilt dann als 0). */
    const val KEY_PROTOCOL = "protocol"

    /**
     * Pulsrechte der Uhr (`WatchPulse`) im selben Paket wie [KEY_READY]: Puls ([KEY_PULSE]) und Puls im Hintergrund
     * ([KEY_PULSE_BACKGROUND]). Fehlen bei älteren Uhren; dann warnt der Nacht-Check nicht.
     */
    const val KEY_PULSE = "pulse"
    const val KEY_PULSE_BACKGROUND = "pulseBackground"

    /**
     * Nachricht Handy → Uhr: Probewecker «In 2 Minuten testen»; Inhalt = `Transient.encode` (Art TEST).
     * Die Uhr bestätigt mit [PATH_TEST_ACK] und führt ihn wie eine Nacht, ohne Eintrag im Verlauf.
     */
    const val PATH_TEST = "/phasewake/test"

    /** Nachricht Uhr → Handy: Probewecker empfangen; Inhalt = seine Kennung (UTF-8). */
    const val PATH_TEST_ACK = "/phasewake/test-ack"

    /** Nachricht in beide Richtungen: Powernap gestartet; Inhalt = `Transient.encode` (Art NAP). */
    const val PATH_NAP = "/phasewake/nap"

    /** Nachricht in beide Richtungen: Probewecker oder Powernap abgebrochen; Inhalt = Kennung (UTF-8). */
    const val PATH_TRANSIENT_CANCEL = "/phasewake/transient-cancel"

    /**
     * Datenpaket in beide Richtungen, zusätzlich zu [PATH_TRANSIENT_CANCEL]: derselbe Abbruch, damit ihn auch ein
     * gerade nicht erreichbares Gerät nachholt (Nachrichten werden nicht nachgeliefert). Schlüssel [KEY_TEXT] =
     * Kennung, [KEY_UPDATED_AT].
     */
    const val PATH_TRANSIENT_CANCELLED = "/phasewake/transient-cancelled"

    /**
     * Datenpaket Handy → Uhr: Feiertage der nächsten 400 Tage für «An Feiertagen nicht wecken»;
     * Schlüssel [KEY_TEXT] (`Holidays.encode`, z. B. «CH-BE|2026-12-25,2026-12-26») und [KEY_UPDATED_AT].
     */
    const val PATH_HOLIDAYS = "/phasewake/holidays"

    /**
     * Datenpaket Handy → Uhr: Schlafziel für den Einschlafpunkt auf dem Ring der Uhr; Schlüssel [KEY_SLEEP_GOAL]
     * (Minuten) und [KEY_UPDATED_AT] (Zeitpunkt der Änderung am Handy). Ohne Paket gilt auf der Uhr der Standard.
     */
    const val PATH_SLEEP_GOAL = "/phasewake/sleep-goal"
    const val KEY_SLEEP_GOAL = "sleepGoalMinutes"

    /**
     * Datenpaket Handy → Uhr: Schalter «Einschlafen automatisch erkennen» ([KEY_AUTO_ONSET]) mit [KEY_UPDATED_AT]
     * (Zeitpunkt der Änderung am Handy). Ohne Paket oder Schlüssel gilt auf der Uhr «an».
     */
    const val PATH_AUTO_ONSET = "/phasewake/auto-onset"
    const val KEY_AUTO_ONSET = "autoOnset"

    /**
     * Datenpaket Uhr → Handy: frühere Zielzeit einer Nacht aus dem Einschlaf-Modus (`OnsetShift`, Review K-07);
     * Schlüssel [KEY_TEXT] (Kennung), [KEY_ORIGINAL_TARGET], [KEY_TARGET] (ms) und [KEY_UPDATED_AT]. Ein Paket, damit es
     * auch ein gerade nicht erreichbares Handy erreicht; der Zweitwecker dieser Nacht klingelt dann ebenfalls früher.
     */
    const val PATH_ONSET_SHIFT = "/phasewake/onset-shift"
    const val KEY_ORIGINAL_TARGET = "originalTarget"
    const val KEY_TARGET = "target"

    /** Nachricht Handy → Uhr: Diagnose-Bericht anfordern (ohne Inhalt); Antwort [PATH_DIAG_LOG]. */
    const val PATH_DIAG_REQUEST = "/phasewake/diag-request"

    /** Nachricht Uhr → Handy: Diagnose-Bericht; Inhalt = `DiagReports.encode`. */
    const val PATH_DIAG_LOG = "/phasewake/diag-log"

    /** Nachricht Handy → Uhr: «Nacht aufzeichnen» starten (ohne Inhalt). Nur Galaxy Watch. */
    const val PATH_RECORD_START = "/phasewake/record-start"

    /** Nachricht Handy → Uhr: laufende Aufzeichnung stoppen (ohne Inhalt). */
    const val PATH_RECORD_STOP = "/phasewake/record-stop"

    /**
     * Datenpaket Uhr → Handy: Stand von «Nacht aufzeichnen»; Schlüssel [KEY_RECORD_SINCE] (Beginn in ms, 0 = keine läuft).
     * Fehlt es, kennt die Uhr die Aufzeichnung nicht (ältere Version, Apple Watch): Das Handy zeigt dann keinen Knopf.
     */
    const val PATH_RECORD_STATUS = "/phasewake/record-status"
    const val KEY_RECORD_SINCE = "since"

    /**
     * Präfix der Pakete Uhr → Handy mit einer abgeschlossenen Aufzeichnung, gefolgt vom Beginn (ms); Schlüssel [KEY_TEXT]
     * (`NightRecording.encode`, höchstens 12 Std., rund 20 KB). Das Handy speichert sie und löscht das Paket.
     */
    const val PATH_RECORDING_PREFIX = "/phasewake/recording/"

    /**
     * Datenpaket Handy → Uhr: Lernphase ([LearningSnapshot]): läuft sie ([KEY_LEARN_ACTIVE], dann zeichnet die Uhr nachts
     * automatisch auf) und die gelernten Werte für den Detektor ([KEY_LEARN_FLOOR], [KEY_LEARN_RESTING_HR], fehlen ohne
     * Wert), mit [KEY_UPDATED_AT]. Ohne Paket gilt auf der Uhr: keine Lernphase, keine gelernten Werte.
     */
    const val PATH_LEARNING = "/phasewake/learning"
    const val KEY_LEARN_ACTIVE = "learnActive"
    const val KEY_LEARN_FLOOR = "learnFloor"
    const val KEY_LEARN_RESTING_HR = "learnRestingHr"

    const val KEY_TEXT = "text"
    const val KEY_UPDATED_AT = "updatedAt"

    /** Inhalt einer Nachricht als Text (UTF-8); ohne Inhalt leer. */
    fun text(data: ByteArray?): String = data?.decodeToString().orEmpty()

    /** Fähigkeiten, über die sich die Apps gegenseitig finden (res/values/wear.xml). */
    const val CAPABILITY_PHONE = "phasewake_phone"
    const val CAPABILITY_WATCH = "phasewake_watch"
}
