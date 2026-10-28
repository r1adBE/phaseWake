package com.phasewake.app.wear.schedule

import android.content.Context
import android.os.UserManager
import androidx.core.content.edit
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.WakePlan
import java.time.Duration
import java.time.Instant

/**
 * Nächster Wecker im geräteverschlüsselten Speicher: Startet die Uhr nachts neu (z. B. Update) und ist
 * eine PIN gesetzt, sind Wecker und Einstellungen bis zum ersten Entsperren nicht lesbar. Bis dahin
 * gilt nur dieser Eintrag: Die Uhr weckt zur Zielzeit, ohne zu messen. Nach dem Entsperren plant
 * [SystemEventReceiver] alles neu.
 *
 * Vor dem Entsperren nur diese Datei benutzen, nie den DataStore oder Dateien der App (Absturz).
 */
object DirectBoot {
    private const val FILE = "wake_direct_boot"
    private const val KEY_ID = "alarmId"
    private const val KEY_WINDOW_START = "windowStart"
    private const val KEY_TARGET = "target"
    private const val KEY_AT = "at"
    private const val KEY_SOUND = "watchSound"
    private const val KEY_LABEL = "label"
    private const val KEY_RANG_ID = "rangId"
    private const val KEY_RANG_TARGET = "rangTarget"

    /** Während des Neustarts verpasst: noch nachholen, aber nicht Stunden später (wie am Handy). */
    private val MISSED_DURING_BOOT: Duration = Duration.ofMinutes(AlarmSpec.MAX_WINDOW_MINUTES.toLong())
    private val FORWARD_DELAY: Duration = Duration.ofSeconds(1)

    /** Ablage für vor dem Entsperren: Plan samt Uhr-Ton und Name (der Wecker selbst ist nicht lesbar). */
    data class Saved(val plan: WakePlan, val at: Instant, val watchSound: Boolean, val label: String)

    fun isUnlocked(context: Context): Boolean = context.getSystemService(UserManager::class.java).isUserUnlocked

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Bei jedem Neuplanen: der geplante Plan; geweckt wird zur Zielzeit. */
    fun save(context: Context, plan: WakePlan, spec: AlarmSpec?) {
        prefs(context).edit {
            putString(KEY_ID, plan.alarmId)
            putLong(KEY_WINDOW_START, plan.windowStart.toEpochMilli())
            putLong(KEY_TARGET, plan.target.toEpochMilli())
            putLong(KEY_AT, plan.target.toEpochMilli())
            putBoolean(KEY_SOUND, spec?.watchSound == true)
            putString(KEY_LABEL, spec?.label.orEmpty())
        }
    }

    /** Beim Schlummern: Nach einem Neustart weckt die Uhr zum Schlummer-Ende erneut. */
    fun saveRingAt(context: Context, plan: WakePlan, at: Instant) {
        // Uhr-Ton und Name bleiben vom letzten Neuplanen stehen.
        prefs(context).edit {
            putString(KEY_ID, plan.alarmId)
            putLong(KEY_WINDOW_START, plan.windowStart.toEpochMilli())
            putLong(KEY_TARGET, plan.target.toEpochMilli())
            putLong(KEY_AT, at.toEpochMilli())
        }
    }

    fun clear(context: Context) {
        prefs(context).edit {
            remove(KEY_ID)
            remove(KEY_WINDOW_START)
            remove(KEY_TARGET)
            remove(KEY_AT)
            remove(KEY_SOUND)
            remove(KEY_LABEL)
        }
    }

    fun load(context: Context): Saved? {
        val prefs = prefs(context)
        val id = prefs.getString(KEY_ID, null) ?: return null
        val target = prefs.getLong(KEY_TARGET, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli) ?: return null
        val windowStart = prefs.getLong(KEY_WINDOW_START, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli) ?: target
        val at = prefs.getLong(KEY_AT, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli) ?: target
        return Saved(WakePlan(id, windowStart, target), at, prefs.getBoolean(KEY_SOUND, false), prefs.getString(KEY_LABEL, null).orEmpty())
    }

    /**
     * Vor dem ersten Entsperren (Neustart, Zeitwechsel): den abgelegten Wecker stellen. Er löst den
     * Sicherungs-Alarm aus; der Weckdienst weckt dann ohne Messung.
     */
    fun restore(context: Context) {
        val saved = load(context) ?: return
        val now = Instant.now()
        if (saved.at.isBefore(now.minus(MISSED_DURING_BOOT))) return
        val at = maxOf(saved.at, now.plus(FORWARD_DELAY))
        AlarmScheduler.setDeadlineAt(context, saved.plan, at)
    }

    /** Vor dem Entsperren geweckt: Kennung und Zielzeit für [takeRang] merken (Nacht erledigt, Einmal-Wecker aus). */
    fun markRang(context: Context, alarmId: String, target: Instant) {
        prefs(context).edit {
            putString(KEY_RANG_ID, alarmId)
            putLong(KEY_RANG_TARGET, target.toEpochMilli())
        }
    }

    /** Gemerkten Weckvorgang einmal abholen. */
    fun takeRang(context: Context): Pair<String, Instant>? {
        val prefs = prefs(context)
        val id = prefs.getString(KEY_RANG_ID, null) ?: return null
        val target = Instant.ofEpochMilli(prefs.getLong(KEY_RANG_TARGET, 0L))
        prefs.edit {
            remove(KEY_RANG_ID)
            remove(KEY_RANG_TARGET)
        }
        return id to target
    }
}
