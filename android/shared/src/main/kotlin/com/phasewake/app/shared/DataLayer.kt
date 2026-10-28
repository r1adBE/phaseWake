package com.phasewake.app.shared

/** Pfade und Schlüssel der Verbindung Handy ↔ Uhr (Wearable Data Layer). Beide Apps nutzen genau diese. */
object DataLayer {
    /** Datenpaket mit allen Weckern; Schlüssel [KEY_TEXT], [KEY_UPDATED_AT]. Schreiben beide Seiten. */
    const val PATH_ALARMS = "/phasewake/alarms"

    /** Präfix der Nacht-Pakete der Uhr, gefolgt vom Fensterbeginn in Millisekunden. */
    const val PATH_NIGHT_PREFIX = "/phasewake/night/"

    /** Nachricht Uhr → Handy: Weckvorgang hat begonnen; Inhalt = Wecker-Kennung (UTF-8). */
    const val PATH_RING = "/phasewake/ring"

    /** Nachricht Uhr → Handy oder Handy → Uhr: Weckvorgang beendet (gestoppt oder geschlummert). */
    const val PATH_STOP = "/phasewake/stop"

    /** Nachricht Handy → Uhr: am Handy wurde Schlummern gewählt. */
    const val PATH_SNOOZE = "/phasewake/snooze"

    /** Nachricht Handy → Uhr: Verlauf auf Wunsch des Nutzers löschen. */
    const val PATH_CLEAR_HISTORY = "/phasewake/clear-history"

    const val KEY_TEXT = "text"
    const val KEY_UPDATED_AT = "updatedAt"

    /** Fähigkeiten, über die sich die Apps gegenseitig finden (res/values/wear.xml). */
    const val CAPABILITY_PHONE = "phasewake_phone"
    const val CAPABILITY_WATCH = "phasewake_watch"
}
