package com.phasewake.app.wear.record

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.Learning
import com.phasewake.app.core.NoiseFloor
import com.phasewake.app.core.WakePlan
import com.phasewake.app.shared.LearningSnapshot
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.RecordEnd
import com.phasewake.app.shared.RecordSync
import com.phasewake.app.wear.PhaseWakeWear
import com.phasewake.app.wear.R
import com.phasewake.app.wear.schedule.DirectBoot
import com.phasewake.app.wear.system.StartLock
import com.phasewake.app.wear.ui.MainActivity
import com.phasewake.app.wear.ui.shortTime
import com.phasewake.app.wear.wake.Notifications
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * Steuerung von «Nacht aufzeichnen» ausserhalb des [RecordService]: Start und Stopp (Uhr, Handy, Lernphase), Prüf-Alarm
 * für das Wiederaufnehmen nach einem Prozessende, Mitteilung, Akkustand und die Zeile fürs Diagnose-Protokoll.
 */
object RecordControl {
    private const val REQUEST_CHECK = 11
    private const val REQUEST_PHONE_START = 12
    private const val REQUEST_STOP = 13
    private const val REQUEST_OPEN = 14
    private const val REQUEST_LEARN_START = 15
    internal const val ACTION_CHECK = "com.phasewake.app.action.RECORD_CHECK"
    internal const val ACTION_PHONE_START = "com.phasewake.app.action.RECORD_PHONE_START"
    internal const val ACTION_LEARN_START = "com.phasewake.app.action.RECORD_LEARN_START"

    /** So oft prüft ein Alarm, ob die Aufzeichnung noch läuft, und nimmt sie sonst wieder auf. */
    val CHECK_INTERVAL: Duration = Duration.ofMinutes(15)

    /**
     * Knopf auf der Uhr: startet die Aufzeichnung (sichtbar, also erlaubt). `false` unter [NightRecording.MIN_BATTERY]
     * Akku: Dann startet nichts, die Startseite zeigt einen Hinweis. Verweigert das System den Start, steht es im Protokoll.
     */
    fun startFromWatch(context: Context): Boolean {
        val battery = battery(context)
        if (battery != null && battery < NightRecording.MIN_BATTERY) {
            context.wearApp.diag.log(DiagKind.BATTERY, "record not started (watch): battery $battery%")
            return false
        }
        startService(context, RecordService.ACTION_START)
        return true
    }

    /** Stopp auf der Uhr (Knopf). */
    fun stopFromWatch(context: Context) = stop(context, RecordEnd.WATCH)

    /**
     * Befehl vom Handy ([RecordSync.Command]), aus dem [com.phasewake.app.wear.sync.WatchListenerService]; [continuous]:
     * Diagnose am Handy freigeschaltet ([RecordSync.continuousOf]), dann misst ein Start durchgehend.
     */
    fun fromPhone(context: Context, command: RecordSync.Command, continuous: Boolean = false) {
        if (!DirectBoot.isUnlocked(context)) return
        when (command) {
            // Aus dem Hintergrund darf der Messdienst nicht in den Vordergrund; ein exakter Alarm darf es (gleich jetzt).
            RecordSync.Command.START -> {
                val intent = Intent(context, RecordReceiver::class.java).setAction(ACTION_PHONE_START).putExtra(RecordService.EXTRA_CONTINUOUS, continuous)
                arm(context, Instant.now().plusSeconds(1), PendingIntent.getBroadcast(context, REQUEST_PHONE_START, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            RecordSync.Command.STOP -> stop(context, RecordEnd.PHONE)
        }
    }

    /**
     * Lernphase ([com.phasewake.app.core.Learning]): Die Einschlaf-Messung hat begonnen, die Aufzeichnung startet mit ihr
     * (über einen exakten Alarm gleich jetzt, wie vom Handy) und endet beim Wecken, ohne Klingeln spätestens zur
     * eingestellten Weckzeit [until]. Nicht unter [Learning.MIN_BATTERY] Akku (der Wecker hat Vorrang) und nicht, wenn schon
     * eine Aufzeichnung läuft (eine von Hand begonnene zählt ebenso). Grund jeweils im Diagnose-Protokoll.
     * @return ob der Start ausgelöst wurde.
     */
    fun startForLearning(context: Context, until: Instant): Boolean {
        val app = context.wearApp
        val battery = battery(context)
        val skip = learnSkipReason(RecordService.running, battery)
        if (skip != null) {
            app.diag.log(if (battery != null && battery < Learning.MIN_BATTERY) DiagKind.BATTERY else DiagKind.MEASURE_START, "learn record skipped: $skip")
            return false
        }
        val intent = Intent(context, RecordReceiver::class.java).setAction(ACTION_LEARN_START).putExtra(RecordService.EXTRA_UNTIL, until.toEpochMilli())
        arm(context, Instant.now().plusSeconds(1), PendingIntent.getBroadcast(context, REQUEST_LEARN_START, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        app.diag.log(DiagKind.MEASURE_START, "learn record start bat=${battery ?: "-"} mode=sample")
        return true
    }

    /** Warum die Lernphase heute Nacht nicht aufzeichnet ([startForLearning]), oder `null`. Unbekannter Akku hindert nicht. */
    internal fun learnSkipReason(recording: Boolean, battery: Int?): String? = when {
        recording -> "recording runs"
        battery != null && battery < Learning.MIN_BATTERY -> "battery $battery%"
        else -> null
    }

    /**
     * Lernphase ohne Klingeln: Die Aufzeichnung endet zur eingestellten Weckzeit [until], sobald keine Nacht mehr läuft
     * ([nightRunning]: messen, klingeln, schlummern; deren Ende beendet sie selbst, siehe [endLearning]).
     */
    internal fun endsAtTarget(now: Instant, until: Instant, nightRunning: Boolean): Boolean = !now.isBefore(until) && !nightRunning

    /** Längste Schlafdauer: So weit nach jetzt darf die Zielzeit eines neuen Plans liegen, um zur selben Nacht zu gehören. */
    private val MAX_SLEEP: Duration = Duration.ofMinutes(AlarmSpec.MAX_SLEEP_MINUTES.toLong())

    /**
     * Lernphase: Weckzeit der laufenden Aufzeichnung nach einem neuen Plan [plan] (Wecker verschoben, Entscheid r1AD
     * 13.11.2026): dessen Zielzeit, wenn sie nach [now] liegt und höchstens die längste Schlafdauer
     * ([AlarmSpec.MAX_SLEEP_MINUTES]) entfernt ist, also zur selben Nacht gehört. Sonst (Wecker aus, gelöscht,
     * übersprungen, der nächste erst in einer späteren Nacht) bleibt die bisherige [current].
     */
    internal fun learnUntilFor(current: Instant, plan: WakePlan?, now: Instant): Instant {
        val target = plan?.target ?: return current
        return if (target.isAfter(now) && Duration.between(now, target) <= MAX_SLEEP) target else current
    }

    /**
     * Neuer Plan ([com.phasewake.app.wear.schedule.AlarmScheduler.reschedule], [plan] = nächster echter Wecker): Die
     * Weckzeit einer Aufzeichnung der Lernphase folgt ihm ([learnUntilFor]) – gespeichert in der [LearnMark] (gilt
     * auch nach einem Prozessende) und an den laufenden Dienst. @return die nun geltende Weckzeit; `null` ohne Marke.
     */
    suspend fun followPlan(context: Context, plan: WakePlan?, now: Instant): Instant? {
        val app = context.wearApp
        val mark = app.learnMark.get() ?: return null
        val until = learnUntilFor(mark.until, plan, now)
        if (until == mark.until) return until
        app.learnMark.set(mark.copy(until = until))
        app.diag.log(DiagKind.SCHEDULED, "learn record until $until (was ${mark.until})")
        if (RecordService.running) {
            context.startService(
                Intent(context, RecordService::class.java).setAction(RecordService.ACTION_FOLLOW_PLAN)
                    .putExtra(RecordService.EXTRA_START, mark.start.toEpochMilli()).putExtra(RecordService.EXTRA_UNTIL, until.toEpochMilli()),
            )
        }
        return until
    }

    /**
     * Geweckt (Klingeln beendet, aus dem Weckdienst): Die laufende Aufzeichnung endet jetzt ([RecordEnd.WAKE]), auch eine
     * von Hand begonnene (spätestens beim Wecken, Audit F-01). Wurde die Lernphase am Handy ausgeschaltet
     * ([onLearningChanged], [reason] = [RecordEnd.PHONE]), endet nur die der Lernphase. Läuft der Dienst nicht
     * (Prozessende), wird die gespeicherte abgeschlossen.
     */
    fun endLearning(context: Context, reason: RecordEnd = RecordEnd.WAKE) {
        if (RecordService.running) {
            context.startService(
                Intent(context, RecordService::class.java).setAction(RecordService.ACTION_END_LEARN).putExtra(RecordService.EXTRA_REASON, reason.code),
            )
            return
        }
        val app = context.wearApp
        app.appScope.launch {
            val mark = app.learnMark.get()
            val saved = app.recordings.active()
            val ends = saved != null && (reason == RecordEnd.WAKE || saved.start == mark?.start)
            if (ends && finishOrphan(app, reason)) {
                cancelCheck(app)
                app.diag.log(DiagKind.MEASURE_END, "record end ${RecordService.reasonText(reason, null)} (not running)")
                app.recordSync.publishStatus(null)
            }
            if (mark != null) app.learnMark.clear()
        }
    }

    /**
     * Neuer Stand der Lernphase vom Handy ([learning]): Läuft sie nicht mehr (ausgeschaltet, Audit F-02, oder fertig),
     * endet eine laufende Aufzeichnung der Lernphase jetzt; eine von Hand begonnene läuft weiter (bis zum Wecken).
     */
    fun onLearningChanged(context: Context, learning: LearningSnapshot) {
        if (!learning.active) endLearning(context, RecordEnd.PHONE)
    }

    /**
     * Stopp mit [reason]: an den laufenden Dienst; läuft keiner (Prozessende), die gespeicherte Aufzeichnung direkt
     * abschliessen, damit sie nicht später wieder aufgenommen wird.
     */
    fun stop(context: Context, reason: RecordEnd) {
        if (RecordService.running) {
            context.startService(Intent(context, RecordService::class.java).setAction(RecordService.ACTION_STOP).putExtra(RecordService.EXTRA_REASON, reason.code))
            return
        }
        cancelCheck(context)
        val app = context.wearApp
        app.appScope.launch {
            if (finishOrphan(app, reason)) app.diag.log(DiagKind.MEASURE_END, "record end ${RecordService.reasonText(reason, battery(app))} (not running)")
            app.recordSync.publishStatus(null)
        }
    }

    /** Nach einem Prozessende (Prüf-Alarm): Dienst neu starten, er liest die gespeicherte Aufzeichnung. */
    fun resume(context: Context) = startService(context, RecordService.ACTION_RESUME)

    /**
     * Startet den Dienst im Vordergrund ([extras] ergänzt den Auftrag); `false`, wenn das System es verweigert (dann steht
     * es im Protokoll).
     */
    internal fun startService(context: Context, action: String, extras: (Intent) -> Unit = {}): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, RecordService::class.java).setAction(action).also(extras))
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (ab Android 12) ist eine IllegalStateException.
        Log.e(RecordService.TAG, "Start verweigert: $action", e)
        runCatching { context.wearApp.diag.log(DiagKind.ERROR, "record start refused ${action.substringAfterLast('.')} ${e.javaClass.simpleName}") }
        StartLock.release()
        false
    }

    /**
     * Schliesst eine liegengebliebene Aufzeichnung ab (Ende nach ihrer letzten gespeicherten Epoche, höchstens 12 Std.
     * nach Beginn) und schickt sie ans Handy. `true`, wenn es eine gab.
     */
    suspend fun finishOrphan(app: PhaseWakeWear, reason: RecordEnd): Boolean {
        val orphan = app.recordings.active() ?: return false
        val latest = orphan.start.plus(NightRecording.MAX_DURATION)
        val end = minOf(orphan.start.plus(orphan.duration), latest)
        app.recordings.finish(orphan.start, end, reason)
        app.recordings.get(orphan.start)?.let { app.recordSync.publishRecording(it) }
        app.learnMark.clear(orphan.start)
        return true
    }

    /**
     * Beim Start der App (nach dem Entsperren): Stand ans Handy. Liegt eine laufende Aufzeichnung ohne Dienst da (z. B.
     * nach einem Neustart der Uhr, der die Alarme löscht), wird sie gleich wieder aufgenommen.
     */
    suspend fun onAppStart(app: PhaseWakeWear) {
        val saved = app.recordings.active()
        if (saved != null && !RecordService.running) armCheck(app, Instant.now().plusSeconds(1))
        app.recordSync.publishStatus(saved?.start)
    }

    /** Nächste Prüfung; exakter Alarm (kein Wecker), erlaubt den Neustart des Messdienstes aus dem Hintergrund. */
    fun armCheck(context: Context, at: Instant = Instant.now().plus(CHECK_INTERVAL)) =
        arm(context, at, pending(context, ACTION_CHECK, REQUEST_CHECK))

    fun cancelCheck(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(pending(context, ACTION_CHECK, REQUEST_CHECK))

    private fun arm(context: Context, at: Instant, operation: PendingIntent) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
        } catch (e: SecurityException) {
            // Ohne exakte Alarme ungenau, aber auch im Ruhezustand der Uhr.
            Log.w(RecordService.TAG, "Kein exakter Alarm für die Aufzeichnung", e)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
        }
    }

    private fun pending(context: Context, action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode, Intent(context, RecordReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Akkustand in Prozent; `null`, wenn unbekannt. */
    fun battery(context: Context): Int? =
        context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    /**
     * Eine Zeile je Stunde fürs Diagnose-Protokoll: Epochen der Stunde ([period]), davon ohne Bewegung, mittlere und
     * grösste Bewegung, Grundrauschen aller bisherigen Epochen ([all], [NoiseFloor]), Epochen mit Puls, Akku.
     */
    fun statLine(period: List<Epoch>, all: List<Epoch>, battery: Int?): String {
        val motions = period.mapNotNull { it.motion }
        val f = NoiseFloor::format
        return "record stat epochs=${period.size} empty=${period.size - motions.size} " +
            "motion=${f(motions.takeIf { it.isNotEmpty() }?.average())}/${f(motions.maxOrNull())} " +
            "floor=${f(NoiseFloor.floor(all.map { it.motion }))} hr=${period.count { it.heartRate != null }} bat=${battery ?: "-"}"
    }

    /** Daueranzeige «Nacht wird aufgezeichnet · Seit 23:10» mit «Aufzeichnung stoppen» und laufender Aktivität. */
    fun notification(context: Context, since: Instant): Notification {
        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context, REQUEST_STOP,
            Intent(context, RecordService::class.java).setAction(RecordService.ACTION_STOP).putExtra(RecordService.EXTRA_REASON, RecordEnd.WATCH.code),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val time = shortTime(since)
        val text = context.getString(R.string.record_since, time)
        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_WINDOW)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(context.getString(R.string.notification_record_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_moon, context.getString(R.string.record_stop), stop)
        OngoingActivity.Builder(context, Notifications.ID_RECORD, builder)
            .setStaticIcon(R.drawable.ic_moon)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(text).build())
            .build()
            .apply(context)
        return builder.build()
    }
}

/**
 * Prüf-Alarm der Aufzeichnung, Start vom Handy (über einen exakten Alarm, siehe [RecordControl.fromPhone]) und Start der
 * Lernphase ([RecordControl.startForLearning]).
 */
class RecordReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Vor dem Entsperren ist die Aufzeichnung nicht lesbar.
        if (!DirectBoot.isUnlocked(context)) return
        if (intent.action == RecordControl.ACTION_CHECK && RecordService.running) return RecordControl.armCheck(context)
        // Start-Wakelock bis zum eigenen des Dienstes; sonst schläft die Uhr nach `onReceive` (Befund 13.11.2026).
        StartLock.acquire(context)
        when (intent.action) {
            RecordControl.ACTION_CHECK -> RecordControl.resume(context)
            RecordControl.ACTION_PHONE_START -> startFromPhone(context, intent)
            RecordControl.ACTION_LEARN_START -> RecordControl.startService(context, RecordService.ACTION_START_LEARN) {
                it.putExtra(RecordService.EXTRA_UNTIL, intent.getLongExtra(RecordService.EXTRA_UNTIL, 0L))
            }
            else -> StartLock.release()
        }
    }

    private fun startFromPhone(context: Context, intent: Intent) {
        RecordControl.startService(context, RecordService.ACTION_START_PHONE) {
            it.putExtra(RecordService.EXTRA_CONTINUOUS, intent.getBooleanExtra(RecordService.EXTRA_CONTINUOUS, false))
        }
    }
}
