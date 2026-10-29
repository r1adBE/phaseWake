package com.phasewake.app.ring

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.PhoneAlert
import com.phasewake.app.core.Snooze
import com.phasewake.app.core.Sunrise
import com.phasewake.app.core.TestStep
import com.phasewake.app.core.Transient
import com.phasewake.app.phoneApp
import com.phasewake.app.shared.AlarmRepository
import com.phasewake.app.shared.RingMessage
import com.phasewake.app.ui.MainActivity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Zweitwecker auf dem Handy. Normalerweise meldet die Uhr den Weckmoment und das Handy klingelt mit.
 * Kommt bis kurz nach der Zielzeit nichts von der Uhr (Akku leer, nicht getragen, nicht verbunden),
 * klingelt das Handy selbst. Gilt nur für Wecker mit eingeschaltetem Zweitwecker.
 */
class BackupAlarm(
    internal val context: Context,
    internal val alarms: AlarmRepository,
    internal val store: DataStore<Preferences>,
    internal val sync: com.phasewake.app.shared.WearSync,
    internal val now: () -> Instant = Instant::now,
) {
    internal val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** Mehrere Stellen planen gleichzeitig neu (Editor, Data Layer, Receiver): nie verschränkt. */
    private val mutex = Mutex()

    /** Bei jedem Aufruf die aktuelle Zeitzone (Reisen): nie beim Erzeugen festhalten. */
    internal val zone: ZoneId get() = ZoneId.systemDefault()

    /**
     * Plant Zweitwecker und Sonnenaufgang neu, danach Bettzeit-Erinnerung und Wochenrückblick (hängen an denselben
     * Ereignissen: Wecker geändert, Neustart, Zeit oder Zeitzone). Ein Fehler dort stört den Zweitwecker nicht.
     */
    suspend fun reschedule() {
        rescheduleBackup()
        try {
            context.phoneApp.bedtime.reschedule()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Bettzeit-Erinnerung nicht planbar", e)
        }
        try {
            context.phoneApp.weekly.reschedule()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Wochenrückblick nicht planbar", e)
        }
    }

    private suspend fun rescheduleBackup() {
        mutex.withLock { planBackup() }
        com.phasewake.app.widget.NextAlarmWidget.update(context)
    }

    /**
     * Die Uhr meldet den Weckmoment: mitklingeln und den Zweitwecker dieser Nacht aussetzen.
     * Ein verspätet zugestelltes «klingeln» (älter als [RingMessage.MAX_AGE_MS]) klingelt nicht mehr.
     */
    suspend fun watchRang(message: RingMessage) {
        // Die Uhr weckt: Einschlafklänge enden immer, auch wenn das Handy selbst still bleibt (Weckart «Aus»).
        com.phasewake.app.sounds.SleepSoundService.stop(context)
        if (message.isStale(now().toEpochMilli())) {
            Log.i(TAG, "Veraltete Weckmeldung der Uhr verworfen")
        } else if (backupAlreadyRang(message)) {
            // Der Zweitwecker hat diese Nacht schon geweckt (z. B. während die Uhr einen anderen Wecker
            // schlummerte); holt die Uhr sie nach, klingelt das Handy nicht ein zweites Mal.
            Log.i(TAG, "Nacht von ${message.alarmId} hat der Zweitwecker schon geweckt")
            cancelSnoozeSafety(message.alarmId)
        } else {
            // Die Uhr klingelt wieder: Die Sicherung nach dem Schlummern ist nicht mehr nötig.
            cancelSnoozeSafety(message.alarmId)
            val spec = context.phoneApp.specFor(message.alarmId)
            // Weckart «Aus» ohne Licht auf dem Handy: das Handy bleibt ganz still (kein Vollbild, kein Bildschirm).
            if (spec == null || spec.phone != PhoneAlert.OFF || spec.phoneSunrise != Sunrise.OFF) forwardRing(message)
        }
        context.phoneApp.diag.log(DiagKind.RING, "watch rang${if (message.isStale(now().toEpochMilli())) " (stale)" else ""}")
        context.phoneApp.testRuns.record(message.alarmId, TestStep.WATCH_RANG, now())
        watchHandled(message.alarmId, message.target?.let(Instant::ofEpochMilli))
    }

    /**
     * Ob der Zweitwecker die Nacht dieser Weckmeldung schon geweckt hat ([backupRang]): gleiche Kennung,
     * Zielzeit der Uhr höchstens die längste Schlafdauer vor der des Zweitweckers (Einschlaf-Modus weckt
     * früher). Ohne Kennung oder Zielzeit (ältere Uhr) nie.
     */
    private suspend fun backupAlreadyRang(message: RingMessage): Boolean {
        val id = message.alarmId ?: return false
        val target = message.target?.let(Instant::ofEpochMilli) ?: return false
        val rang = decodeSuppressed(store.data.first()[BACKUP_RANG])[id] ?: return false
        return !target.isAfter(rang) && !target.isBefore(rang.minus(Duration.ofMinutes(AlarmSpec.MAX_SLEEP_MINUTES.toLong())))
    }

    /**
     * Die Meldung kommt im Hintergrund an; von dort darf ab Android 12 kein Vordergrund-Dienst
     * starten. Ein Wecker-Alarm in einer Sekunde darf es: über ihn zum [Receiver].
     */
    private fun forwardRing(message: RingMessage) {
        val intent = Intent(context, Receiver::class.java).setAction(ACTION_WATCH_RING)
            .putExtra(PhoneRingService.EXTRA_ALARM_ID, message.alarmId)
        message.target?.let { intent.putExtra(PhoneRingService.EXTRA_TARGET, it) }
        message.snoozesLeft?.let { intent.putExtra(PhoneRingService.EXTRA_SNOOZES_LEFT, it) }
        if (canScheduleExact(context)) {
            val operation = PendingIntent.getBroadcast(context, 4, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            try {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(now().plus(FORWARD_DELAY).toEpochMilli(), showIntent()), operation)
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "Exakter Alarm nicht erlaubt", e)
            }
        }
        // Ohne exakte Alarme: direkt versuchen (kann ab Android 12 im Hintergrund scheitern).
        PhoneRingService.ring(context, message.alarmId, fromWatch = true, target = message.target, snoozesLeft = message.snoozesLeft)
    }

    /**
     * Die Uhr meldet «stop». Mit Schlummer-Ende (auf der Uhr geschlummert) stellt das Handy eine eigene
     * Sicherung kurz danach, falls die Uhr dann nicht wieder klingelt (Akku leer, Verbindung weg);
     * ohne (Nacht beendet) fällt eine solche Sicherung weg. [ringingSnoozesLeft]: Restzahl des
     * Handy-Klingelns vor diesem «stop», falls es mitklingelte.
     */
    suspend fun watchStopped(message: RingMessage, ringingSnoozesLeft: Int?) {
        val safetyAt = message.snoozeSafetyAt(now().toEpochMilli())
        when {
            safetyAt != null -> {
                // Bisherige Runden inklusive dieser; ohne Mitklingeln die Zahl einer schon gestellten Sicherung.
                val done = ringingSnoozesLeft?.let { Snooze.MAX_COUNT - it + 1 }
                    ?: SnoozeSafety.load(context)?.takeIf { it.alarmId == message.alarmId }?.snoozesDone
                    ?: 0
                snoozeSafety(message.alarmId, Instant.ofEpochMilli(safetyAt), done)
            }
            // Ohne Schlummer-Ende ist die Nacht beendet; ein verspätetes Schlummer-«stop» ändert nichts.
            message.snoozeUntil == null -> cancelSnoozeSafety(message.alarmId)
        }
        context.phoneApp.diag.log(if (message.snoozeUntil != null) DiagKind.SNOOZE else DiagKind.STOP, "from watch")
        if (message.snoozeUntil == null) {
            context.phoneApp.testRuns.record(message.alarmId, TestStep.STOP_ARRIVED, now())
            // Probewecker oder Powernap ist beendet: nicht mehr anzeigen und nicht mehr sichern.
            if (Transient.kindOf(message.alarmId) != null) context.phoneApp.transient.clear(message.alarmId)
        }
        watchHandled(message.alarmId, message.target?.let(Instant::ofEpochMilli))
    }

    /** Hebt die Sicherung nach dem Schlummern auf; mit [alarmId] nur, wenn sie diesem Wecker gilt. */
    fun cancelSnoozeSafety(alarmId: String? = null) {
        val saved = SnoozeSafety.load(context)
        if (alarmId != null && saved?.alarmId != null && saved.alarmId != alarmId) return
        alarmManager.cancel(safetyPending(context, null, 0))
        SnoozeSafety.clear(context)
    }

    /**
     * Die Uhr hat diese Nacht übernommen (klingelt, gestoppt oder Nacht gemeldet): Zweitwecker dieses
     * Weckers bis kurz nach der Zielzeit dieser Nacht aussetzen – nicht länger, sonst fiele ein
     * zweiter Wecker am selben Morgen weg. Ohne Kennung (ältere Uhr): jeder Wecker, dessen Nacht läuft.
     */
    suspend fun watchHandled(alarmId: String?, target: Instant? = null) {
        val current = now()
        val specs = alarms.current().alarms
        val until: Map<String, Instant> = if (alarmId != null) {
            mapOf(alarmId to nightTarget(specs.firstOrNull { it.id == alarmId }, target, current).plus(GRACE))
        } else {
            val window = Duration.ofMinutes(AlarmSpec.MAX_WINDOW_MINUTES.toLong())
            specs.mapNotNull { spec ->
                AlarmSchedule.nextTarget(spec.copy(enabled = true), current.minus(LOOKBACK), zone)
                    ?.takeIf { !it.isAfter(current.plus(window)) }
                    ?.let { spec.id to it.plus(GRACE) }
            }.toMap()
        }
        suppress(until)
        if (until.isNotEmpty()) context.phoneApp.diag.log(DiagKind.SUPPRESSED, "phone backup paused")
        reschedule()
    }

    /**
     * Zielzeit, bis zu der der Zweitwecker dieser Nacht ausgesetzt wird. Im Einschlaf-Modus weckt die
     * Uhr früher; der Zweitwecker liegt aber auf der eingestellten Zeit – also mindestens bis dorthin.
     */
    private fun nightTarget(spec: AlarmSpec?, target: Instant?, current: Instant): Instant {
        // Auch ein soeben ausgeschalteter Einmal-Wecker zählt für diese Nacht noch.
        val on = spec?.copy(enabled = true)
        val configured = on?.let { AlarmSchedule.nextTarget(it, (target ?: current.minus(LOOKBACK)).minusMillis(1), zone) }
        if (target == null) return configured ?: current.plus(Duration.ofMinutes(AlarmSpec.MAX_WINDOW_MINUTES.toLong()))
        // Nur die Weckzeit derselben Nacht (höchstens die längste Schlafdauer später).
        val sameNight = configured?.takeIf { !it.isAfter(target.plus(Duration.ofMinutes(AlarmSpec.MAX_SLEEP_MINUTES.toLong()))) }
        return maxOf(target, sameNight ?: target)
    }

    /**
     * Der Zweitwecker hat geklingelt: diese Nacht nicht noch einmal planen, und einen Einmal-Wecker
     * ausschalten – mit Abgleich zur Uhr, sonst klingelte er ab jetzt jeden Tag.
     */
    internal suspend fun backupRang(alarmId: String?, target: Instant?) {
        if (alarmId == null) return
        if (target != null) {
            suppress(mapOf(alarmId to target.plus(GRACE)))
            // Für [watchRang]: Holt die Uhr diese Nacht später nach, nicht noch einmal klingeln.
            val keepAfter = now().minus(Duration.ofDays(1))
            store.edit { prefs ->
                val rang = decodeSuppressed(prefs[BACKUP_RANG]).toMutableMap()
                rang[alarmId] = maxOf(rang[alarmId] ?: Instant.EPOCH, target)
                prefs[BACKUP_RANG] = encodeSuppressed(rang.filterValues { it.isAfter(keepAfter) })
            }
        }
        disableOneShot(alarmId)
    }

    /** Schaltet einen noch eingeschalteten Einmal-Wecker aus (über die Uhr-Synchronisation). */
    suspend fun disableOneShot(alarmId: String?) {
        val spec = alarms.current().alarms.firstOrNull { it.id == alarmId } ?: return
        if (spec.isOneShot && spec.enabled) context.phoneApp.afterAlarmsChanged { alarms.setEnabled(spec.id, false) }
    }

    /**
     * Pro Wecker erledigt bis: Die Uhr hat die Nacht übernommen oder der Zweitwecker hat geweckt (Zielzeit + Nachfrist).
     * Das Gegenstück zu `doneUntil` der Uhr: Überspringen, Startseite und Widget suchen für diesen Wecker erst ab hier.
     */
    val doneUntilFlow: Flow<Map<String, Instant>> = store.data.map { decodeSuppressed(it[SUPPRESSED]) }.distinctUntilChanged()

    /** Aktueller Stand von [doneUntilFlow]. */
    suspend fun doneUntil(): Map<String, Instant> = doneUntilFlow.first()

    /** Pro Wecker: ausgesetzt bis; ein späterer Zeitpunkt gewinnt, abgelaufene fallen weg. */
    private suspend fun suppress(until: Map<String, Instant>) {
        if (until.isEmpty()) return
        val keepAfter = now().minus(Duration.ofDays(1))
        store.edit { prefs ->
            val merged = decodeSuppressed(prefs[SUPPRESSED]).toMutableMap()
            until.forEach { (id, at) -> merged[id] = maxOf(merged[id] ?: Instant.EPOCH, at) }
            prefs[SUPPRESSED] = encodeSuppressed(merged.filterValues { it.isAfter(keepAfter) })
            // Früher galt eine einzige Sperre für alle Wecker.
            prefs.remove(LEGACY_SUPPRESSED_UNTIL)
        }
    }

    internal fun showIntent(): PendingIntent = showPending(context)

    internal fun pending(alarmId: String? = null, target: Instant? = null): PendingIntent = backupPending(context, alarmId, target)

    /**
     * Exakt planen; mit [show] als Wecker (Sperrbildschirm zeigt ihn), sonst still. Ohne Recht für
     * exakte Alarme (Android 12/12L, entziehbar) ungenau statt gar nicht.
     */
    internal fun setAlarm(at: Instant, show: PendingIntent?, operation: PendingIntent) = setExact(context, at, show, operation)

    /** Löst den Zweitwecker aus und plant den nächsten. Nicht exportiert: nur eigene Alarme. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onBackupAlarm(context, intent)
    }

    /**
     * Neustart, App-Update, Zeit- oder Zeitzonenwechsel, Recht für exakte Alarme erteilt: Alarme
     * sind weg oder verschoben, neu planen. Vor dem ersten Entsperren nur den abgelegten Zweitwecker.
     */
    class SystemReceiver : BroadcastReceiver() {
        // Aktionsprüfung hier, damit Lint die erlaubte Liste sieht.
        override fun onReceive(context: Context, intent: Intent) { if (intent.action in SYSTEM_ACTIONS) onBackupSystemEvent(context, intent) }
    }

    companion object {
        internal const val TAG = "BackupAlarm"
        internal const val ACTION = "com.phasewake.app.action.BACKUP_RING"
        internal const val ACTION_SUNRISE = "com.phasewake.app.action.SUNRISE"
        internal const val ACTION_WATCH_RING = "com.phasewake.app.action.WATCH_RING"
        internal const val ACTION_SNOOZE_SAFETY = "com.phasewake.app.action.SNOOZE_SAFETY"

        /** Systemereignisse, auf die [SystemReceiver] hört (wie im Manifest); nur Text, auf jeder Version unbedenklich. */
        @android.annotation.SuppressLint("InlinedApi")
        internal val SYSTEM_ACTIONS = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
        internal val SUPPRESSED = stringPreferencesKey("backup_suppressed")

        /** Pro Wecker: Zielzeit der Nacht, die der Zweitwecker zuletzt geweckt hat (Format wie [SUPPRESSED]). */
        private val BACKUP_RANG = stringPreferencesKey("backup_rang")
        private val LEGACY_SUPPRESSED_UNTIL = longPreferencesKey("backup_suppressed_until")
        internal val PLANNED_ID = stringPreferencesKey("backup_planned_id")
        internal val PLANNED_TARGET = longPreferencesKey("backup_planned_target")
        internal val SUNRISE_SHOWN_UNTIL = longPreferencesKey("sunrise_shown_until")

        /** So lange nach der Zielzeit wartet das Handy auf die Uhr. */
        internal val GRACE: Duration = Duration.ofMinutes(2)

        /** Weiterleitung der Weckmeldung der Uhr über einen Wecker-Alarm. */
        internal val FORWARD_DELAY: Duration = Duration.ofSeconds(1)

        /** Ohne Zielzeit (ältere Uhr): so weit zurück kann die Nacht begonnen haben (Fenster, Schlummern, Klingeln). */
        private val LOOKBACK: Duration = Duration.ofMinutes(AlarmSpec.MAX_WINDOW_MINUTES + Snooze.MAX_COUNT * Snooze.MINUTES + 10L)

        internal val MISSED_DURING_BOOT: Duration = Duration.ofMinutes(AlarmSpec.MAX_WINDOW_MINUTES.toLong())

        /** Ob exakte Alarme erlaubt sind; entziehbar nur auf Android 12/12L (ab 13 gilt USE_EXACT_ALARM). */
        fun canScheduleExact(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

        /** Ob der Speicher entsperrt ist (nach einem Neustart erst nach dem ersten Entsperren). */
        fun isUnlocked(context: Context): Boolean = context.getSystemService(UserManager::class.java).isUserUnlocked

        /** Antippen der Weckzeit im System öffnet die App. */
        internal fun showPending(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        internal fun backupPending(context: Context, alarmId: String?, target: Instant?): PendingIntent {
            val intent = Intent(context, Receiver::class.java).setAction(ACTION)
            if (alarmId != null) intent.putExtra(PhoneRingService.EXTRA_ALARM_ID, alarmId)
            if (target != null) intent.putExtra(PhoneRingService.EXTRA_TARGET, target.toEpochMilli())
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Sicherung nach dem Schlummern: eigener Alarm neben dem Zweitwecker (eigener Request-Code, eigene Aktion). */
        internal fun safetyPending(context: Context, alarmId: String?, snoozesDone: Int): PendingIntent {
            val intent = Intent(context, Receiver::class.java).setAction(ACTION_SNOOZE_SAFETY)
                .putExtra(PhoneRingService.EXTRA_SNOOZES_DONE, snoozesDone)
            if (alarmId != null) intent.putExtra(PhoneRingService.EXTRA_ALARM_ID, alarmId)
            return PendingIntent.getBroadcast(context, 5, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        internal fun setExact(context: Context, at: Instant, show: PendingIntent?, operation: PendingIntent) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            if (canScheduleExact(context)) {
                try {
                    if (show != null) {
                        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), operation)
                    } else {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
                    }
                    return
                } catch (e: SecurityException) {
                    Log.w(TAG, "Exakter Alarm nicht erlaubt", e)
                }
            }
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
        }

        /** «kennung=millis» je Zeile. */
        internal fun encodeSuppressed(map: Map<String, Instant>): String =
            map.entries.joinToString("\n") { (id, at) -> "$id=${at.toEpochMilli()}" }

        internal fun decodeSuppressed(text: String?): Map<String, Instant> =
            text.orEmpty().lines().mapNotNull { line ->
                val millis = line.substringAfterLast('=', "").toLongOrNull() ?: return@mapNotNull null
                line.substringBeforeLast('=') to Instant.ofEpochMilli(millis)
            }.toMap()
    }
}
