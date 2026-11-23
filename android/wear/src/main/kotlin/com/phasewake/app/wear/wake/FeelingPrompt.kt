package com.phasewake.app.wear.wake

import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.FeelingAsk
import com.phasewake.app.core.NightRecord
import com.phasewake.app.wear.PhaseWakeWear
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Befinden nach dem Wecken auf der Galaxy Watch ([FeelingAsk], Entscheid 23.11.2026, gleich wie die Apple Watch). Der
 * Weckdienst stellt die Frage beim Stopp auf der Uhr ([ask], in `finishNight`), der Weckbildschirm öffnet danach den
 * eigenen Bildschirm (`FeelingActivity`), die Wahl speichert [choose]. Der Stopp selbst läuft vollständig wie ohne
 * Abfrage; hier wird nur gemerkt. Prozessweit und nur im Speicher: Endet der Prozess, entfällt die Frage, und die
 * Startseite des Handys fragt wie bisher.
 */
internal object FeelingPrompt {
    /** Gefragte Nacht (Wecker und Fensterbeginn, wie im Verlauf) und Zeitpunkt der Frage in ms seit 1970. */
    data class Ask(val alarmId: String, val windowStart: Instant, val askedAtMs: Long)

    /**
     * So lange wartet eine Wahl darauf, dass der Abschluss die Nacht gespeichert und eingereiht hat. Er braucht
     * Sekundenbruchteile; 30 s decken auch eine langsame Uhr. Danach gibt es die Nacht nicht (Fehler beim Speichern).
     */
    private const val QUEUE_WAIT_MS = 30_000L

    /** So viele zuletzt eingereihte Nächte merkt sich [nightQueued] (zwei Wecker am selben Morgen, mit Reserve). */
    private const val KEEP_QUEUED = 4

    private val open = MutableStateFlow<Ask?>(null)

    /** Nächte (Wecker, Fensterbeginn), die der Abschluss gespeichert und fürs Handy eingereiht hat; die jüngsten. */
    private val queued = MutableStateFlow<List<Pair<String, Instant>>>(emptyList())

    /** Fragt nach der Nacht [alarmId] mit Fensterbeginn [windowStart], gestellt um [nowMs]; ersetzt eine ältere. */
    fun ask(alarmId: String, windowStart: Instant, nowMs: Long) {
        open.value = Ask(alarmId, windowStart, nowMs)
    }

    /** Offene Frage um [nowMs]; `null` ohne Frage oder nach [FeelingAsk.MILLIS] ([FeelingAsk.isOpen]). */
    fun current(nowMs: Long): Ask? = open.value?.takeIf { FeelingAsk.isOpen(it.askedAtMs, nowMs) }

    /** Schliesst die Frage [ask] (gewählt, «Später», abgelaufen); eine inzwischen neuere bleibt offen. */
    fun close(ask: Ask) {
        open.compareAndSet(ask, null)
    }

    /** Der Abschluss (`saveFinishedNight`) hat [night] gespeichert und eingereiht: Eine Wahl darf sie jetzt ändern. */
    fun nightQueued(night: NightRecord) {
        queued.update { (it + (night.alarmId to night.windowStart)).takeLast(KEEP_QUEUED) }
    }

    /**
     * Speichert die Wahl [feeling] (1–5) zur Nacht von [ask] im Verlauf der Uhr und reiht die so geänderte Nacht fürs
     * Handy ein: gleicher Schlüssel der Warteschlange, ersetzt also eine noch wartende Fassung ohne Befinden und wird
     * wiederholt, bis das Handy bestätigt. Wartet dafür, bis der Abschluss die Nacht gespeichert und eingereiht hat
     * ([nightQueued]); sonst ersetzte seine Fassung ohne Befinden die Wahl. Im Protokoll nur, ob es klappte, nie der
     * Wert. [waitMs]: Wartezeit auf den Abschluss (Tests kürzer). @return ob gespeichert und eingereiht.
     */
    suspend fun choose(app: PhaseWakeWear, ask: Ask, feeling: Int, waitMs: Long = QUEUE_WAIT_MS): Boolean {
        close(ask)
        val key = ask.alarmId to ask.windowStart
        val ready = withTimeoutOrNull(waitMs) { queued.first { key in it } } != null
        val night = if (ready) app.nights.setFeeling(ask.alarmId, ask.windowStart, feeling) else null
        if (night == null) {
            app.diag.log(DiagKind.SYNC, "feeling not saved, night missing")
            return false
        }
        app.sync.publishNight(night)
        app.diag.log(DiagKind.SYNC, "feeling from watch queued")
        return true
    }

    /** Für Tests: offene Frage und eingereihte Nächte vergessen (das Objekt lebt über einen Test hinaus). */
    fun reset() {
        open.value = null
        queued.value = emptyList()
    }
}
