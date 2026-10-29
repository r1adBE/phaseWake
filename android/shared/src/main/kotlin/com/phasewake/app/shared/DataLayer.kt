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
     * Datenpaket Handy → Uhr: Feiertage der nächsten 400 Tage für «An Feiertagen nicht wecken»;
     * Schlüssel [KEY_TEXT] (`Holidays.encode`, z. B. «CH-BE|2026-12-25,2026-12-26») und [KEY_UPDATED_AT].
     */
    const val PATH_HOLIDAYS = "/phasewake/holidays"

    /** Nachricht Handy → Uhr: Diagnose-Bericht anfordern (ohne Inhalt); Antwort [PATH_DIAG_LOG]. */
    const val PATH_DIAG_REQUEST = "/phasewake/diag-request"

    /** Nachricht Uhr → Handy: Diagnose-Bericht; Inhalt = `DiagReports.encode`. */
    const val PATH_DIAG_LOG = "/phasewake/diag-log"

    const val KEY_TEXT = "text"
    const val KEY_UPDATED_AT = "updatedAt"

    /** Inhalt einer Nachricht als Text (UTF-8); ohne Inhalt leer. */
    fun text(data: ByteArray?): String = data?.decodeToString().orEmpty()

    /** Fähigkeiten, über die sich die Apps gegenseitig finden (res/values/wear.xml). */
    const val CAPABILITY_PHONE = "phasewake_phone"
    const val CAPABILITY_WATCH = "phasewake_watch"
}
