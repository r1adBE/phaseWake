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
 * gilt nur dieser Eintrag: Die Uhr weckt zur Zielzeit, ohne zu messen. Daneben liegt der Plan danach:
 * Endet eine Nacht vor dem Entsperren, gilt er ([promoteNext]), damit auch der nächste Morgen weckt.
 * Nach dem Entsperren plant [SystemEventReceiver] alles neu.
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
    private const val KEY_NEXT_ID = "nextAlarmId"
    private const val KEY_NEXT_WINDOW_START = "nextWindowStart"
    private const val KEY_NEXT_TARGET = "nextTarget"
    private const val KEY_NEXT_SOUND = "nextWatchSound"
    private const val KEY_NEXT_LABEL = "nextLabel"

    /** Während des Neustarts verpasst: noch nachholen, aber nicht Stunden später (wie am Handy). */
    val MISSED_DURING_BOOT: Duration = Duration.ofMinutes(AlarmSpec.MAX_WINDOW_MINUTES.toLong())
    private val FORWARD_DELAY: Duration = Duration.ofSeconds(1)

    /** Ablage für vor dem Entsperren: Plan samt Uhr-Ton und Name (der Wecker selbst ist nicht lesbar). */
    data class Saved(val plan: WakePlan, val at: Instant, val watchSound: Boolean, val label: String)

    fun isUnlocked(context: Context): Boolean = context.getSystemService(UserManager::class.java).isUserUnlocked

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Bei jedem Neuplanen: der geplante Plan (geweckt wird zur Zielzeit) und [next], der Plan danach. */
    fun save(context: Context, plan: WakePlan, spec: AlarmSpec?, next: WakePlan?, nextSpec: AlarmSpec?) {
        prefs(context).edit {
            putString(KEY_ID, plan.alarmId)
            putLong(KEY_WINDOW_START, plan.windowStart.toEpochMilli())
            putLong(KEY_TARGET, plan.target.toEpochMilli())
            putLong(KEY_AT, plan.target.toEpochMilli())
            putBoolean(KEY_SOUND, spec?.watchSound == true)
            putString(KEY_LABEL, spec?.label.orEmpty())
        }
        saveNext(context, next, nextSpec)
    }

    /** Nur den Plan danach ablegen; der Eintrag selbst (z. B. ein ausstehendes Schlummer-Ende) bleibt. */
    fun saveNext(context: Context, next: WakePlan?, nextSpec: AlarmSpec?) {
        prefs(context).edit {
            if (next == null) {
                removeNext()
            } else {
                putString(KEY_NEXT_ID, next.alarmId)
                putLong(KEY_NEXT_WINDOW_START, next.windowStart.toEpochMilli())
                putLong(KEY_NEXT_TARGET, next.target.toEpochMilli())
                putBoolean(KEY_NEXT_SOUND, nextSpec?.watchSound == true)
                putString(KEY_NEXT_LABEL, nextSpec?.label.orEmpty())
            }
        }
    }

    private fun android.content.SharedPreferences.Editor.removeNext() {
        remove(KEY_NEXT_ID)
        remove(KEY_NEXT_WINDOW_START)
        remove(KEY_NEXT_TARGET)
        remove(KEY_NEXT_SOUND)
        remove(KEY_NEXT_LABEL)
    }

    /**
     * Vor dem Entsperren ist eine Nacht zu Ende: Der Plan danach wird zum Eintrag.
     * @return der neue Eintrag oder `null`, wenn keiner abgelegt war (dann ist alles gelöscht).
     */
    fun promoteNext(context: Context): Saved? {
        val prefs = prefs(context)
        val id = prefs.getString(KEY_NEXT_ID, null)
        val target = prefs.getLong(KEY_NEXT_TARGET, 0L).takeIf { it > 0L }
        if (id == null || target == null) {
            clear(context)
            return null
        }
        val windowStart = prefs.getLong(KEY_NEXT_WINDOW_START, 0L).takeIf { it > 0L } ?: target
        val sound = prefs.getBoolean(KEY_NEXT_SOUND, false)
        val label = prefs.getString(KEY_NEXT_LABEL, null).orEmpty()
        prefs.edit {
            putString(KEY_ID, id)
            putLong(KEY_WINDOW_START, windowStart)
            putLong(KEY_TARGET, target)
            putLong(KEY_AT, target)
            putBoolean(KEY_SOUND, sound)
            putString(KEY_LABEL, label)
            removeNext()
        }
        return load(context)
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
            removeNext()
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
        var saved = load(context) ?: return
        val now = Instant.now()
        // Längst vorbei: dann gilt der Plan danach, falls einer abgelegt ist.
        if (saved.at.isBefore(now.minus(MISSED_DURING_BOOT))) saved = promoteNext(context) ?: return
        if (saved.at.isBefore(now.minus(MISSED_DURING_BOOT))) return
        setDeadlineAt(context, saved)
    }

    /** Sicherungs-Alarm für den Eintrag [saved]; ein schon vergangener Zeitpunkt gleich danach. */
    fun setDeadlineAt(context: Context, saved: Saved) {
        AlarmScheduler.setDeadlineAt(context, saved.plan, maxOf(saved.at, Instant.now().plus(FORWARD_DELAY)))
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
