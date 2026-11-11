package com.phasewake.app.shared

/**
 * Pfade und Schlüssel der Verbindung Handy ↔ Uhr (Wearable Data Layer). Beide Apps nutzen genau diese.
 *
 * Seit [PROTOCOL] 2 (Audit C2, Entscheid r1AD 10.11.2026) gehen alle Inhalte mit persönlichen Daten als Nachricht über
 * die Warteschlange [Outbox] (Pfade `PATH_MSG_…`, Bestätigung [PATH_ACK]), nicht mehr als Datenpaket, das Google in der
 * Cloud sichert. Als Datenpaket bleiben nur der Gerätestatus der Uhr ([PATH_WATCH_STATUS]), «Verlauf löschen»
 * ([PATH_CLEAR_HISTORY], nur ein Zeitpunkt) und der Abbruch eines Probeweckers oder Powernaps ([PATH_TRANSIENT_CANCELLED],
 * nur eine Kennung). Den früheren Weg für ältere Uhr-Apps gibt es nicht mehr (Entscheid r1AD 10.11.2026): Die Pfade der
 * früheren Datenpakete (Wecker, Nächte, Aufzeichnungen, Lernphase, …) dienen nur noch der einmaligen Umstellung, die die
 * eigenen alten Pakete löscht ([QueueMessages.legacyItems]).
 */
object DataLayer {
    /** Früheres Datenpaket mit allen Weckern; nur noch für die Umstellung (löschen). */
    const val PATH_ALARMS = "/phasewake/alarms"

    /**
     * Präfix der früheren Nacht-Pakete der Uhr, gefolgt von «fensterbeginnMs-kennung» (Kennung URL-kodiert), Schlüssel
     * [KEY_TEXT]. Nur noch für die Umstellung: Die Uhr reiht ihre eigenen in die Warteschlange ein und löscht sie.
     */
    const val PATH_NIGHT_PREFIX = "/phasewake/night/"

    /** Nachricht in beide Richtungen (Warteschlange): alle Wecker, Inhalt «updatedAt», Zeilenumbruch, `Codec.encodeAlarms`. */
    const val PATH_MSG_ALARMS = "/phasewake/m/alarms"

    /** Nachricht Uhr → Handy (Warteschlange): eine Nacht, Inhalt `Codec.encodeNight`. */
    const val PATH_MSG_NIGHT = "/phasewake/m/night"

    /** Nachricht Uhr → Handy (Warteschlange, gross über einen Kanal): abgeschlossene Aufzeichnung, `NightRecording.encode`. */
    const val PATH_MSG_RECORDING = "/phasewake/m/recording"

    /** Nachricht Uhr → Handy (Warteschlange): Stand von «Nacht aufzeichnen», Inhalt Beginn in ms (0 = keine läuft). */
    const val PATH_MSG_RECORD_STATUS = "/phasewake/m/record-status"

    /** Nachricht Uhr → Handy (Warteschlange): frühere Zielzeit, Inhalt `OnsetShift.encode`. */
    const val PATH_MSG_ONSET_SHIFT = "/phasewake/m/onset-shift"

    /** Nachricht Handy → Uhr (Warteschlange): Lernphase, Inhalt `LearningSnapshot.encode`. */
    const val PATH_MSG_LEARNING = "/phasewake/m/learning"

    /** Nachricht Handy → Uhr (Warteschlange): Schlafziel, Inhalt «minuten|updatedAt». */
    const val PATH_MSG_SLEEP_GOAL = "/phasewake/m/sleep-goal"

    /** Nachricht Handy → Uhr (Warteschlange): Schalter der Einschlaf-Messung, Inhalt «1|updatedAt» bzw. «0|updatedAt». */
    const val PATH_MSG_AUTO_ONSET = "/phasewake/m/auto-onset"

    /** Nachricht Handy → Uhr (Warteschlange): Feiertage, Inhalt «updatedAt», Zeilenumbruch, `Holidays.encode`. */
    const val PATH_MSG_HOLIDAYS = "/phasewake/m/holidays"

    /** Nachricht in beide Richtungen: Empfang eines Eintrags der Warteschlange bestätigt; Inhalt = seine Kennung. */
    const val PATH_ACK = "/phasewake/ack"

    /**
     * Nachricht in beide Richtungen (ohne Inhalt): Die Gegenseite soll ihren aktuellen Stand einreihen (Wecker; vom Handy
     * auch Lernphase, Schlafziel, Schalter, Feiertage; von der Uhr auch den Stand der Aufzeichnung). Ersetzt das frühere
     * Abholen aus dem Data Layer, etwa für eine frisch installierte Seite.
     */
    const val PATH_SYNC_REQUEST = "/phasewake/sync-request"

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
     * Stand der Verbindung Handy ↔ Uhr (Review O-04), wie eine Formatversion: steigt, wenn eine ältere Uhr etwas, das das
     * Handy schickt, nicht mehr wie eingestellt umsetzt (z. B. die Weckart «Genau»). Die Uhr meldet ihren Stand im
     * Status-Paket ([KEY_PROTOCOL]); ist er kleiner als der des Handys, warnt der Nacht-Check (`WearSync.watchOutdated`).
     */
    const val PROTOCOL = 2

    /** [PROTOCOL] der Uhr im selben Paket wie [KEY_READY]; fehlt bei Uhren vor dieser Angabe (gilt dann als 0). */
    const val KEY_PROTOCOL = "protocol"

    /**
     * Pulsrechte der Uhr (`WatchPulse`) im selben Paket wie [KEY_READY]: Puls ([KEY_PULSE]) und Puls im Hintergrund
     * ([KEY_PULSE_BACKGROUND]). Fehlen bei älteren Uhren; dann warnt der Nacht-Check nicht.
     */
    const val KEY_PULSE = "pulse"
    const val KEY_PULSE_BACKGROUND = "pulseBackground"

    /**
     * Messung vor dem Schlafen dieser Nacht nicht gestartet: nur der Grund (`RecorderSkip.Reason.code`, Audit F-06, ohne
     * Uhrzeit – technische Angabe wie der Akku) im selben Paket wie [KEY_READY]; leer ohne Meldung oder wenn die Nacht
     * vorbei ist, fehlt bei älteren Uhren. Der Nacht-Check am Handy zeigt dann einen Hinweis.
     */
    const val KEY_RECORDER_SKIP = "recorderSkip"

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

    /** Früheres Datenpaket Handy → Uhr mit den Feiertagen; nur noch für die Umstellung (löschen). */
    const val PATH_HOLIDAYS = "/phasewake/holidays"

    /** Früheres Datenpaket Handy → Uhr mit dem Schlafziel; nur noch für die Umstellung (löschen). */
    const val PATH_SLEEP_GOAL = "/phasewake/sleep-goal"

    /** Früheres Datenpaket Handy → Uhr mit dem Schalter der Einschlaf-Messung; nur noch für die Umstellung (löschen). */
    const val PATH_AUTO_ONSET = "/phasewake/auto-onset"

    /**
     * Früheres Datenpaket Uhr → Handy mit der früheren Zielzeit (`OnsetShift`, Review K-07); Schlüssel [KEY_TEXT]
     * (Kennung), [KEY_ORIGINAL_TARGET], [KEY_TARGET] (ms) und [KEY_UPDATED_AT]. Nur noch für die Umstellung der Uhr.
     */
    const val PATH_ONSET_SHIFT = "/phasewake/onset-shift"
    const val KEY_ORIGINAL_TARGET = "originalTarget"
    const val KEY_TARGET = "target"

    /** Nachricht Handy → Uhr: Diagnose-Bericht anfordern (ohne Inhalt); Antwort [PATH_DIAG_LOG]. */
    const val PATH_DIAG_REQUEST = "/phasewake/diag-request"

    /** Uhr → Handy: Diagnose-Bericht, Inhalt = `DiagReports.encode`; bis 90 KB als Nachricht, grösser über einen Kanal ([Outbox.sendOnce]). */
    const val PATH_DIAG_LOG = "/phasewake/diag-log"

    /** Nachricht Handy → Uhr: «Nacht aufzeichnen» starten (ohne Inhalt). Nur Galaxy Watch. */
    const val PATH_RECORD_START = "/phasewake/record-start"

    /** Nachricht Handy → Uhr: laufende Aufzeichnung stoppen (ohne Inhalt). */
    const val PATH_RECORD_STOP = "/phasewake/record-stop"

    /**
     * Früheres Datenpaket Uhr → Handy mit dem Stand von «Nacht aufzeichnen»; Schlüssel [KEY_RECORD_SINCE] (Beginn in ms,
     * 0 = keine läuft). Nur noch für die Umstellung der Uhr.
     */
    const val PATH_RECORD_STATUS = "/phasewake/record-status"
    const val KEY_RECORD_SINCE = "since"

    /**
     * Präfix der früheren Pakete Uhr → Handy mit einer abgeschlossenen Aufzeichnung, gefolgt vom Beginn (ms); Schlüssel
     * [KEY_TEXT] (`NightRecording.encode`). Nur noch für die Umstellung der Uhr.
     */
    const val PATH_RECORDING_PREFIX = "/phasewake/recording/"

    /** Früheres Datenpaket Handy → Uhr mit der Lernphase; nur noch für die Umstellung (löschen). */
    const val PATH_LEARNING = "/phasewake/learning"

    const val KEY_TEXT = "text"
    const val KEY_UPDATED_AT = "updatedAt"

    /** Inhalt einer Nachricht als Text (UTF-8); ohne Inhalt leer. */
    fun text(data: ByteArray?): String = data?.decodeToString().orEmpty()

    /** Fähigkeiten, über die sich die Apps gegenseitig finden (res/values/wear.xml). */
    const val CAPABILITY_PHONE = "phasewake_phone"
    const val CAPABILITY_WATCH = "phasewake_watch"
}
