package com.phasewake.app.ring

import android.content.Context
import androidx.core.content.edit
import com.phasewake.app.ring.BackupAlarm.Companion.FORWARD_DELAY
import com.phasewake.app.ring.BackupAlarm.Companion.MISSED_DURING_BOOT
import com.phasewake.app.ring.BackupAlarm.Companion.backupPending
import com.phasewake.app.ring.BackupAlarm.Companion.safetyPending
import com.phasewake.app.ring.BackupAlarm.Companion.setExact
import com.phasewake.app.ring.BackupAlarm.Companion.showPending
import java.time.Instant

// Geräteverschlüsselter Speicher des Zweitweckers: auch vor dem ersten Entsperren lesbar.

/**
 * Nächster Zweitwecker im geräteverschlüsselten Speicher: Startet das Handy nachts neu, ist bis
 * zum ersten Entsperren nur dieser lesbar. Beim Entsperren plant [BackupAlarm.SystemReceiver] alles neu.
 */
internal object BackupDirectBoot {
    private const val FILE = "backup_direct_boot"
    private const val KEY_AT = "at"
    private const val KEY_ID = "alarmId"
    private const val KEY_TARGET = "target"
    private const val KEY_RANG_ID = "rangId"
    private const val KEY_RANG_TARGET = "rangTarget"

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(context: Context, at: Instant, alarmId: String, target: Instant) {
        prefs(context).edit { putLong(KEY_AT, at.toEpochMilli()); putString(KEY_ID, alarmId); putLong(KEY_TARGET, target.toEpochMilli()) }
    }

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_AT); remove(KEY_ID); remove(KEY_TARGET) }
    }

    /** Vor dem Entsperren geklungen: Kennung und Zielzeit für [takeRang] merken. */
    fun markRang(context: Context, alarmId: String, target: Long) {
        prefs(context).edit { putString(KEY_RANG_ID, alarmId); putLong(KEY_RANG_TARGET, target) }
    }

    /** Gemerkten Weckvorgang einmal abholen. */
    fun takeRang(context: Context): Pair<String, Instant>? {
        val prefs = prefs(context)
        val id = prefs.getString(KEY_RANG_ID, null) ?: return null
        val target = Instant.ofEpochMilli(prefs.getLong(KEY_RANG_TARGET, 0L))
        prefs.edit { remove(KEY_RANG_ID); remove(KEY_RANG_TARGET) }
        return id to target
    }

    fun restore(context: Context) {
        val prefs = prefs(context)
        val at = Instant.ofEpochMilli(prefs.getLong(KEY_AT, 0L))
        val current = Instant.now()
        // Während des Neustarts verpasst: noch nachholen, aber nicht Stunden später.
        if (at.isBefore(current.minus(MISSED_DURING_BOOT))) return
        val target = prefs.getLong(KEY_TARGET, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)
        setExact(context, maxOf(at, current.plus(FORWARD_DELAY)), showPending(context), backupPending(context, prefs.getString(KEY_ID, null), target))
    }
}

/**
 * Sicherung nach dem Schlummern im geräteverschlüsselten Speicher: übersteht Prozessende und
 * Neustart und ist auch vor dem ersten Entsperren lesbar.
 */
internal object SnoozeSafety {
    private const val FILE = "backup_snooze_safety"
    private const val KEY_AT = "at"
    private const val KEY_ID = "alarmId"
    private const val KEY_DONE = "snoozesDone"

    data class Saved(val at: Instant, val alarmId: String?, val snoozesDone: Int)

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(context: Context, saved: Saved) {
        prefs(context).edit { putLong(KEY_AT, saved.at.toEpochMilli()); putString(KEY_ID, saved.alarmId); putInt(KEY_DONE, saved.snoozesDone) }
    }

    fun load(context: Context): Saved? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_AT)) return null
        return Saved(Instant.ofEpochMilli(prefs.getLong(KEY_AT, 0L)), prefs.getString(KEY_ID, null), prefs.getInt(KEY_DONE, 0))
    }

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_AT); remove(KEY_ID); remove(KEY_DONE) }
    }

    /** Neu planen (der Alarm fehlt nach einem Neustart); während des Neustarts verpasst: gleich nachholen. */
    fun restore(context: Context) {
        val saved = load(context) ?: return
        val current = Instant.now()
        if (saved.at.isBefore(current.minus(MISSED_DURING_BOOT))) {
            clear(context)
            return
        }
        setExact(context, maxOf(saved.at, current.plus(FORWARD_DELAY)), showPending(context), safetyPending(context, saved.alarmId, saved.snoozesDone))
    }
}
