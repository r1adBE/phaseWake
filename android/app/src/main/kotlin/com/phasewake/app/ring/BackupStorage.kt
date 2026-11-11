package com.phasewake.app.ring

import android.content.Context
import androidx.core.content.edit
import com.phasewake.app.core.Transient
import com.phasewake.app.ring.BackupAlarm.Companion.FORWARD_DELAY
import com.phasewake.app.ring.BackupAlarm.Companion.MISSED_DURING_BOOT
import com.phasewake.app.ring.BackupAlarm.Companion.backupPending
import com.phasewake.app.ring.BackupAlarm.Companion.safetyPending
import com.phasewake.app.ring.BackupAlarm.Companion.setExact
import com.phasewake.app.ring.BackupAlarm.Companion.showPending
import java.time.Instant

// Geräteverschlüsselter Speicher des Zweitweckers: auch vor dem ersten Entsperren lesbar.

/**
 * Die nächsten Zweitwecker im geräteverschlüsselten Speicher: Startet das Handy nachts neu, ist bis zum ersten
 * Entsperren nur dieser lesbar. Gestellt ist immer nur der früheste; klingelt er vor dem Entsperren, stellt [advance]
 * den nächsten (auch für eine zweite Nacht oder einen zweiten Wecker am selben Morgen). Beim Entsperren plant
 * [BackupAlarm.SystemReceiver] alles neu.
 */
internal object BackupDirectBoot {
    private const val FILE = "backup_direct_boot"
    private const val KEY_PLANS = "plans"
    private const val KEY_RANG = "rang"

    // Ältere Versionen: nur ein Eintrag bzw. ein Weckvorgang (beim Lesen noch berücksichtigt).
    private const val KEY_AT = "at"
    private const val KEY_ID = "alarmId"
    private const val KEY_TARGET = "target"
    private const val KEY_RANG_ID = "rangId"
    private const val KEY_RANG_TARGET = "rangTarget"

    /** Ein abgelegter Zweitwecker: klingelt um [at] für den Wecker [alarmId] mit der Zielzeit [target]. */
    data class Entry(val at: Instant, val alarmId: String, val target: Instant)

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Legt [entries] ab (früheste zuerst), Zeile «at|target|kennung». */
    fun save(context: Context, entries: List<Entry>) {
        val text = entries.sortedBy { it.at }.joinToString("\n") { "${it.at.toEpochMilli()}|${it.target.toEpochMilli()}|${it.alarmId}" }
        prefs(context).edit { putString(KEY_PLANS, text); remove(KEY_AT); remove(KEY_ID); remove(KEY_TARGET) }
    }

    /** Abgelegte Zweitwecker, früheste zuerst; unlesbare Zeilen fallen weg. */
    fun load(context: Context): List<Entry> {
        val prefs = prefs(context)
        val text = prefs.getString(KEY_PLANS, null)
        if (text == null) {
            val id = prefs.getString(KEY_ID, null) ?: return emptyList()
            return listOf(Entry(Instant.ofEpochMilli(prefs.getLong(KEY_AT, 0L)), id, Instant.ofEpochMilli(prefs.getLong(KEY_TARGET, 0L))))
        }
        return text.lines().mapNotNull { line ->
            val parts = line.split('|', limit = 3)
            val at = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val target = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            val id = parts.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Entry(Instant.ofEpochMilli(at), id, Instant.ofEpochMilli(target))
        }.sortedBy { it.at }
    }

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_PLANS); remove(KEY_AT); remove(KEY_ID); remove(KEY_TARGET) }
    }

    /** Vor dem Entsperren geklungen: Kennung und Zielzeit für [takeRang] merken (mehrere, falls es länger dauert). */
    fun markRang(context: Context, alarmId: String, target: Long) {
        val prefs = prefs(context)
        val rang = BackupAlarm.decodeSuppressed(prefs.getString(KEY_RANG, null)) + (alarmId to Instant.ofEpochMilli(target))
        prefs.edit { putString(KEY_RANG, BackupAlarm.encodeSuppressed(rang)) }
    }

    /** Gemerkte Weckvorgänge einmal abholen (je Wecker der letzte). */
    fun takeRang(context: Context): List<Pair<String, Instant>> {
        val prefs = prefs(context)
        val rang = BackupAlarm.decodeSuppressed(prefs.getString(KEY_RANG, null)).toList().toMutableList()
        prefs.getString(KEY_RANG_ID, null)?.let { rang += it to Instant.ofEpochMilli(prefs.getLong(KEY_RANG_TARGET, 0L)) }
        if (rang.isNotEmpty()) prefs.edit { remove(KEY_RANG); remove(KEY_RANG_ID); remove(KEY_RANG_TARGET) }
        return rang
    }

    /**
     * Der Zweitwecker [alarmId]/[target] hat vor dem Entsperren ausgelöst: diesen und alle früheren Einträge
     * entfernen und den nächsten stellen.
     */
    fun advance(context: Context, alarmId: String?, target: Long?, now: Instant = Instant.now()) {
        val fired = target?.let(Instant::ofEpochMilli)
        val rest = load(context).filterNot { entry ->
            (entry.alarmId == alarmId && entry.target == fired) || (fired != null && entry.target.isBefore(fired))
        }
        save(context, rest)
        restore(context, now)
    }

    /**
     * Abgelegter Zweitwecker, der während eines Neustarts fällig wurde und nachzuholen ist (Audit A-5): Zeitpunkt bis
     * [now] und noch in der Frist von [restore] ([Transient.catchesUpAfterBoot], 30 Min. bzw. 60 s). Der früheste;
     * `null`, wenn keiner.
     */
    fun missed(context: Context, now: Instant): Entry? =
        load(context).firstOrNull { !it.at.isAfter(now) && Transient.catchesUpAfterBoot(it.alarmId, it.at, now, MISSED_DURING_BOOT) }

    /** Entfernt [entry] aus der Liste (nachgeholt oder erledigt), damit er nicht zweimal klingelt. */
    fun remove(context: Context, entry: Entry) = save(context, load(context).filterNot { it == entry })

    /**
     * Stellt den frühesten abgelegten Zweitwecker. Während des Neustarts verpasst: noch nachholen, aber nicht Stunden
     * später (Probewecker und Powernap: nur 60 s); zu alte Einträge fallen weg. [now]: für Tests.
     */
    fun restore(context: Context, now: Instant = Instant.now()) {
        val entries = load(context)
        val due = entries.filter { Transient.catchesUpAfterBoot(it.alarmId, it.at, now, MISSED_DURING_BOOT) }
        if (due.size != entries.size) save(context, due)
        val next = due.firstOrNull() ?: return
        setExact(context, maxOf(next.at, now.plus(FORWARD_DELAY)), showPending(context), backupPending(context, next.alarmId, next.target))
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

    /** Neu planen (der Alarm fehlt nach einem Neustart); während des Neustarts verpasst: gleich nachholen. [now]: für Tests. */
    fun restore(context: Context, now: Instant = Instant.now()) {
        val saved = load(context) ?: return
        val current = now
        // Wie beim Zweitwecker: Probewecker und Powernap nur 60 s nach dem Schlummer-Ende, sonst 30 Min.
        if (!Transient.catchesUpAfterBoot(saved.alarmId.orEmpty(), saved.at, current, MISSED_DURING_BOOT)) {
            clear(context)
            return
        }
        setExact(context, maxOf(saved.at, current.plus(FORWARD_DELAY)), showPending(context), safetyPending(context, saved.alarmId, saved.snoozesDone))
    }
}

/**
 * Schlummern am Handy allein (Zweitwecker oder Probewecker ohne Uhr) im geräteverschlüsselten Speicher: Der Alarm
 * zum Schlummer-Ende ([BackupAlarm.snoozeUntil]) fehlt nach einem Neustart, die Nacht gilt aber schon seit dem ersten
 * Klingeln als geweckt. Wie [SnoozeSafety] neu gestellt in [BackupAlarm.planBackup] und bei (LOCKED_)BOOT_COMPLETED,
 * auch vor dem ersten Entsperren. Gelöscht, sobald es wieder klingelt oder am Handy beendet wird ([BackupAlarm.cancelSnooze]).
 * Derselbe Eintrag dient beim Klingeln des Handys als Wiederaufnahme nach einem Prozessende ([PhoneRingService]), auch
 * wenn die Uhr führt; ein «stop» oder die Nacht der Uhr hebt ihn dann auf ([BackupAlarm.cancelSnoozeOf]).
 */
internal object PhoneSnooze {
    private const val FILE = "backup_phone_snooze"
    private const val KEY_AT = "at"
    private const val KEY_ID = "alarmId"
    private const val KEY_DONE = "snoozesDone"
    private const val KEY_TARGET = "target"

    /**
     * Schlummer-Ende [at] des Weckers [alarmId] nach [snoozesDone] Runden; [target]: Zielzeit der Nacht (ms), damit
     * «stop» und Nacht der Uhr nur dieselbe Nacht aufheben (`null` bei älteren Einträgen und ohne Zielzeit).
     */
    data class Saved(val at: Instant, val alarmId: String?, val snoozesDone: Int, val target: Long? = null)

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(context: Context, saved: Saved) {
        prefs(context).edit {
            putLong(KEY_AT, saved.at.toEpochMilli())
            putString(KEY_ID, saved.alarmId)
            putInt(KEY_DONE, saved.snoozesDone)
            if (saved.target != null) putLong(KEY_TARGET, saved.target) else remove(KEY_TARGET)
        }
    }

    fun load(context: Context): Saved? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_AT)) return null
        val target = if (prefs.contains(KEY_TARGET)) prefs.getLong(KEY_TARGET, 0L) else null
        return Saved(Instant.ofEpochMilli(prefs.getLong(KEY_AT, 0L)), prefs.getString(KEY_ID, null), prefs.getInt(KEY_DONE, 0), target)
    }

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_AT); remove(KEY_ID); remove(KEY_DONE); remove(KEY_TARGET) }
    }

    /**
     * Neu planen (der Alarm fehlt nach einem Neustart); während des Neustarts verpasst: gleich nachholen, aber nicht
     * Stunden später (Probewecker und Powernap: nur 60 s, sonst [MISSED_DURING_BOOT]). [now]: für Tests.
     */
    fun restore(context: Context, now: Instant = Instant.now()) {
        val saved = load(context) ?: return
        if (!Transient.catchesUpAfterBoot(saved.alarmId.orEmpty(), saved.at, now, MISSED_DURING_BOOT)) {
            clear(context)
            return
        }
        setExact(context, maxOf(saved.at, now.plus(FORWARD_DELAY)), showPending(context), snoozePending(context, saved.alarmId, saved.snoozesDone))
    }
}
