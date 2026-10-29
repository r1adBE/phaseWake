package com.phasewake.app.core

/**
 * Weg, auf dem der Weckbildschirm nach vorn kommt (Android: Handy und Galaxy Watch). Apple zeigt das
 * Wecken über das System (AlarmKit, Smart-Alarm-Sitzung); dort nur für gleiche Fälle in beiden Kernen.
 */
enum class RingRoute {
    /** Die App hat ein sichtbares Fenster: Sie darf den Weckbildschirm selbst öffnen. */
    DIRECT,

    /**
     * Begleit-App einer verbundenen Uhr (CompanionDeviceManager): Android erlaubt ihr den Start aus dem Hintergrund,
     * die App öffnet den Weckbildschirm selbst – auch bei Display an und entsperrt.
     */
    COMPANION,

    /** Display aus oder gesperrt, Vollbild erlaubt: Das System öffnet den Weckbildschirm über die Weck-Mitteilung. */
    FULL_SCREEN,

    /** Gerät in Gebrauch (Display an, entsperrt) oder Vollbild nicht erlaubt: Das System zeigt nur eine Mitteilung oben. */
    HEADS_UP,
}

/**
 * Regeln, damit der Weckbildschirm erscheint. Android startet das Vollbild einer Mitteilung nur, wenn sie
 * neu erscheint – nie bei einer Aktualisierung unter derselben Kennung (AOSP InCallUI, Bug 11578080) – und
 * nur bei dunklem oder gesperrtem Display. Darum bekommt jede Klingel-Runde und jede Erinnerung eine neue
 * Kennung ([nextId]), und solange der Weckbildschirm nicht zu sehen ist, erscheint die Mitteilung alle
 * [REALERT_SECONDS] neu: Geht das Display inzwischen aus, kommt der Bildschirm dann als Vollbild.
 */
object RingAlert {
    /** Abstand der Erinnerungen in Sekunden, solange es klingelt und der Weckbildschirm nicht zu sehen ist. */
    const val REALERT_SECONDS: Int = 20

    /**
     * Erwarteter Weg für die Diagnose. [appVisible]: ein Fenster der App ist sichtbar; [companion]: Zuordnung als
     * Begleit-App einer Uhr (nur Handy); [interactive]: Display an (Always-on/Ambient zählt als aus); [locked]:
     * Sperrbildschirm; [fullScreenAllowed]: Recht für Vollbild (Android 14+).
     */
    fun route(appVisible: Boolean, companion: Boolean, interactive: Boolean, locked: Boolean, fullScreenAllowed: Boolean): RingRoute = when {
        appVisible -> RingRoute.DIRECT
        companion -> RingRoute.COMPANION
        fullScreenAllowed && (!interactive || locked) -> RingRoute.FULL_SCREEN
        else -> RingRoute.HEADS_UP
    }

    /**
     * Kennung für die nächste Weck-Mitteilung: immer eine andere als [current] (`null` = noch keine), abwechselnd
     * [first] und [second], damit sie als neue Mitteilung erscheint und das Vollbild auslöst.
     */
    fun nextId(current: Int?, first: Int, second: Int): Int = if (current == first) second else first
}
