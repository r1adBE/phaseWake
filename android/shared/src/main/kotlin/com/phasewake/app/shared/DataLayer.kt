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

    const val KEY_TEXT = "text"
    const val KEY_UPDATED_AT = "updatedAt"

    /** Fähigkeiten, über die sich die Apps gegenseitig finden (res/values/wear.xml). */
    const val CAPABILITY_PHONE = "phasewake_phone"
    const val CAPABILITY_WATCH = "phasewake_watch"
}
