package com.phasewake.app.wear.wake

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.WakeDetector
import com.phasewake.app.core.WakePlan
import com.phasewake.app.core.WakeReason
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.RingMessage
import com.phasewake.app.wear.R
import com.phasewake.app.wear.schedule.AlarmReceiver
import com.phasewake.app.wear.schedule.AlarmScheduler
import com.phasewake.app.wear.schedule.DirectBoot
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/**
 * Führt eine Weck-Nacht durch: misst im Fenster, weckt im richtigen Moment, verwaltet Schlummern
 * und speichert die Nacht.
 *
 * Zustände: Messen → Klingeln ⇄ Schlummern → Ende. Alle Übergänge laufen auf dem Hauptthread.
 * Fällt der Dienst unterwegs aus, sorgt der Sicherungs-Alarm zur Zielzeit trotzdem fürs Wecken.
 *
 * Immer nur eine Nacht: Klingelt oder schlummert eine, wartet ein zweiter Wecker desselben Morgens,
 * bis sie beendet ist, und klingelt dann gleich (siehe [AlarmScheduler]). Vor dem ersten Entsperren
 * nach einem Neustart (Direct Boot) weckt der Dienst zur Zielzeit ohne Messung und ohne Speicher.
 */
class WakeService : LifecycleService() {

    internal enum class Phase { MEASURING, RINGING, SNOOZED }

    internal class Night(
        val plan: WakePlan,
        var spec: AlarmSpec?,
        var detector: WakeDetector,
        val epochs: MutableList<Epoch> = mutableListOf(),
        var phase: Phase = Phase.MEASURING,
        var reason: WakeReason? = null,
        var firstRing: Instant? = null,
        var snoozes: Int = 0,
        var sensors: SensorSession? = null,
        /** Vor dem ersten Entsperren begonnen: kein Messen, kein Speicher. */
        val locked: Boolean = false,
        /** Uhr-Ton und Name aus [DirectBoot], solange der Wecker selbst nicht lesbar ist. */
        var lockedSound: Boolean = false,
        var lockedLabel: String = "",
        /** Der Wecker will die Weckaufgabe am Handy (siehe [checkTaskOnPhone]); bis zur Bestätigung gilt Halten. */
        var taskWanted: Boolean = false,
        /** Das Handy hat bestätigt, dass es die Aufgabe zeigt: Beenden hier nur noch im Notfall (langes Halten). */
        var taskOnPhone: Boolean = false,
        /** Bis hierhin (elapsedRealtime) schaltet eine erste Bestätigung auf «Aufgabe am Handy». */
        var taskAcceptUntil: Long = 0L,
    )

    internal val app by lazy { wearApp }
    internal val handler = Handler(Looper.getMainLooper())
    internal val escalation by lazy { Escalation(this) }
    internal var wakeLock: PowerManager.WakeLock? = null
    internal var night: Night? = null
    internal var notification: android.app.Notification? = null
    internal var onset: OnsetMonitor? = null

    /** «Gute Nacht» ist angenommen, die Messung startet gleich: Dienst nicht beenden. */
    internal var goodNightPending = false

    /** Zählt die Starts von «Gute Nacht»; ein überholter Start bricht ab. */
    internal var goodNightRun = 0
    internal val onsetTimeout = Runnable { stopGoodNight() }

    internal val deadline = Runnable { onDeadline() }
    internal val snoozeEnd = Runnable { ring() }
    internal val ringTimeout = Runnable { onRingTimeout() }
    internal val hardLimit = Runnable { onHardLimit() }
    internal val taskExpired = Runnable { taskFallback("Bestätigung des Handys abgelaufen") }

    /** Laufende Abschlüsse ([finishNight]); bis sie gespeichert und neu geplant haben, bleibt der Dienst. */
    internal var finishing = 0

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val plan = intent?.let(AlarmReceiver::planFrom)
        when (intent?.action) {
            ACTION_START -> plan?.let { adopt(it) }
            ACTION_DEADLINE -> if (plan == null) onDeadline() else if (adopt(plan)) onDeadline()
            ACTION_SNOOZE_END -> if (plan == null) ring() else if (adopt(plan)) ring()
            ACTION_SNOOZE -> snooze(notifyPhone = true)
            ACTION_PHONE_SNOOZE -> if (isForThisNight(intent)) snooze(notifyPhone = true, fromPhone = true)
            ACTION_DISMISS -> if (isForThisNight(intent)) dismiss(notifyPhone = true)
            ACTION_CANCEL -> cancelMeasuring()
            ACTION_GOOD_NIGHT -> intent.getStringExtra(EXTRA_ALARM_ID)?.let { startGoodNight(it) }
            ACTION_GOOD_NIGHT_RESUME -> startGoodNight(null)
            ACTION_GOOD_NIGHT_CANCEL -> stopGoodNight()
            ACTION_TASK_SHOWN -> if (isForThisNight(intent)) onTaskShown(remoteTarget(intent))
            ACTION_TASK_GONE -> if (isForThisNight(intent) && isSameTarget(remoteTarget(intent))) taskFallback("Aufgabe am Handy beendet")
            // Vom System neu gestartet (START_STICKY), ohne Auftrag: «Gute Nacht» über den Prüf-Alarm
            // wieder aufnehmen; nur dieser darf den Messdienst aus dem Hintergrund in den Vordergrund holen.
            null -> if (intent == null && DirectBoot.isUnlocked(this)) AlarmScheduler.armGoodNightCheck(this, Instant.now().plusSeconds(1))
        }
        // Jeder Start über startForegroundService verlangt eine Vordergrund-Meldung, auch wenn schon eine steht.
        if (plan != null || intent?.action == ACTION_GOOD_NIGHT || intent?.action == ACTION_GOOD_NIGHT_RESUME) {
            val measuring = night?.let { it.phase == Phase.MEASURING && !it.locked } ?: false
            notification?.let { goForeground(it, measuring = measuring || onset != null || goodNightPending) }
        }
        if (isIdle()) stopSelf(startId)
        // Wartet «Gute Nacht» auf das Einschlafen, startet das System den Dienst nach seinem Ende neu.
        return if (onset != null || goodNightPending) START_STICKY else START_NOT_STICKY
    }

    internal fun isIdle() = night == null && onset == null && !goodNightPending && finishing == 0

    internal fun stopIfIdle() {
        if (isIdle()) stopSelf()
    }

    /** Befehl vom Handy: Nennt er einen anderen Wecker als den laufenden, gilt er nicht dieser Nacht. */
    internal fun isForThisNight(intent: Intent): Boolean {
        val remote = intent.getStringExtra(EXTRA_REMOTE_ALARM_ID) ?: return true
        val current = night?.plan?.alarmId ?: return true
        return remote == current
    }

    internal fun remoteTarget(intent: Intent): Long? =
        if (intent.hasExtra(EXTRA_REMOTE_TARGET)) intent.getLongExtra(EXTRA_REMOTE_TARGET, 0L) else null

    /** Nennt das Handy eine Zielzeit, muss sie die der laufenden Nacht sein (ohne Angabe: kein Widerspruch). */
    internal fun isSameTarget(target: Long?): Boolean {
        val current = night?.plan?.target?.toEpochMilli() ?: return true
        return target == null || target == current
    }

    override fun onDestroy() {
        running = false
        measuringPlan = null
        activePlan = null
        goodNightActive = false
        handler.removeCallbacksAndMessages(null)
        // Den gespeicherten Stand von «Gute Nacht» bewusst behalten: Der Prüf-Alarm nimmt den Modus wieder auf.
        onset?.stop()
        night?.sensors?.stop()
        escalation.stop()
        releaseWakeLock()
        WatchSunrise.stop(this)
        app.ringState.publish(RingUi.Idle)
        super.onDestroy()
    }

    /**
     * Vordergrund mit dem passenden Typ: «health» zum Messen (braucht Sensorrechte), sonst
     * «systemExempted» (Wecker-Apps). Schlägt «health» fehl, läuft der Dienst ohne Messung weiter.
     * @return `true`, wenn gemessen werden darf.
     */
    // Die Diensttypen (API 34) setzt ServiceCompat auf älteren Versionen nicht; das Einbetten ist unbedenklich.
    @android.annotation.SuppressLint("InlinedApi")
    internal fun goForeground(shown: android.app.Notification, measuring: Boolean): Boolean {
        notification = shown
        if (measuring) {
            try {
                ServiceCompat.startForeground(this, Notifications.ID_SERVICE, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
                return true
            } catch (e: RuntimeException) {
                // SecurityException (Rechte fehlen) oder ForegroundServiceStartNotAllowedException.
                Log.w(TAG, "Messen nicht erlaubt, nur Wecken zur Zielzeit", e)
            }
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_SERVICE, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Vordergrund nicht möglich", e)
        }
        return false
    }

    internal fun holdWakeLock(millis: Long) {
        releaseWakeLock()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phasewake:window")
            .apply { acquire(millis) }
    }

    internal fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    internal fun reasonText(reason: WakeReason) = getString(
        when (reason) {
            WakeReason.LIGHT_SLEEP -> R.string.ring_reason_light
            WakeReason.AWAKE -> R.string.ring_reason_awake
            WakeReason.DEADLINE -> R.string.ring_reason_deadline
        },
    )

    internal fun formatTime(instant: Instant): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(instant.atZone(ZoneId.systemDefault()))

    companion object {
        internal const val TAG = "WakeService"

        const val ACTION_START = "com.phasewake.app.wake.START"
        const val ACTION_DEADLINE = "com.phasewake.app.wake.DEADLINE"
        const val ACTION_SNOOZE_END = "com.phasewake.app.wake.SNOOZE_END"
        const val ACTION_SNOOZE = "com.phasewake.app.wake.SNOOZE"
        const val ACTION_DISMISS = "com.phasewake.app.wake.DISMISS"
        const val ACTION_PHONE_SNOOZE = "com.phasewake.app.wake.PHONE_SNOOZE"
        const val ACTION_CANCEL = "com.phasewake.app.wake.CANCEL"
        const val ACTION_GOOD_NIGHT = "com.phasewake.app.wake.GOOD_NIGHT"
        const val ACTION_GOOD_NIGHT_CANCEL = "com.phasewake.app.wake.GOOD_NIGHT_CANCEL"

        /** Vom Handy: Weckaufgabe ist zu sehen ([DataLayer.PATH_TASK_SHOWN]) bzw. ungelöst beendet ([DataLayer.PATH_TASK_GONE]). */
        const val ACTION_TASK_SHOWN = "com.phasewake.app.wake.TASK_SHOWN"
        const val ACTION_TASK_GONE = "com.phasewake.app.wake.TASK_GONE"
        private const val ACTION_GOOD_NIGHT_RESUME = "com.phasewake.app.wake.GOOD_NIGHT_RESUME"
        private const val EXTRA_ALARM_ID = "goodNightAlarmId"
        private const val EXTRA_REMOTE_ALARM_ID = "remoteAlarmId"
        private const val EXTRA_REMOTE_TARGET = "remoteTarget"
        internal val ONSET_GIVE_UP: Duration = Duration.ofMinutes(15)

        /** «Gute Nacht» nur, wenn die eingestellte Weckzeit höchstens so weit entfernt ist. */
        val GOOD_NIGHT_MAX_AHEAD: Duration = Duration.ofHours(16)

        /** Spätestens so lange nach der Zielzeit endet eine Nacht (4 × 10 Min Klingeln + 3 × 5 Min Schlummern passen gut hinein). */
        internal val NIGHT_HARD_LIMIT: Duration = Duration.ofHours(2)

        /** Höchstens so lange klingelt es ohne Reaktion am Stück. */
        internal const val RING_TIMEOUT_MS = 10 * 60_000L
        internal const val WAKE_LOCK_MARGIN_MS = 20 * 60_000L

        /** Läuft der Dienst gerade? Befehle ohne Plan gehen nur an einen laufenden Dienst. */
        @Volatile
        var running: Boolean = false
            internal set

        /** Plan, für den gerade gemessen wird; `null` ausserhalb der Messung. */
        @Volatile
        var measuringPlan: WakePlan? = null
            internal set

        /** Plan der laufenden Nacht (Messen, Klingeln oder Schlummern); `null`, wenn keine läuft. */
        @Volatile
        var activePlan: WakePlan? = null
            internal set

        /** Wartet «Gute Nacht» gerade in diesem Prozess auf das Einschlafen? */
        @Volatile
        var goodNightActive: Boolean = false
            internal set

        /** Startet die Messung für [plan] als Vordergrunddienst. */
        fun start(context: Context, plan: WakePlan) {
            Log.i(TAG, "Fenster beginnt: $plan")
            ContextCompat.startForegroundService(
                context,
                AlarmReceiver.putPlan(Intent(context, WakeService::class.java).setAction(ACTION_START), plan),
            )
        }

        /** Startet «Gute Nacht» für den Schlafdauer-Wecker [alarmId]. */
        fun goodNight(context: Context, alarmId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeService::class.java).setAction(ACTION_GOOD_NIGHT).putExtra(EXTRA_ALARM_ID, alarmId),
            )
        }

        /** Nimmt «Gute Nacht» nach einem Ende des Prozesses wieder auf (aus dem Prüf-Alarm). */
        fun resumeGoodNight(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WakeService::class.java).setAction(ACTION_GOOD_NIGHT_RESUME))
        }

        /** Beendet «Gute Nacht»; auch wenn der Dienst nicht mehr läuft, gilt der gespeicherte Stand nicht mehr. */
        fun cancelGoodNight(context: Context) {
            command(context, ACTION_GOOD_NIGHT_CANCEL)
            AlarmScheduler.cancelGoodNightCheck(context)
            val app = context.wearApp
            app.appScope.launch { app.goodNight.clear() }
        }

        /**
         * Befehl an den Dienst; [plan] erlaubt einen Neustart, falls er inzwischen beendet wurde.
         * [remoteAlarmId]: Wecker, den das Handy meint (aus [RingMessage]); `null` gilt für jede Nacht.
         * [remoteTarget]: Zielzeit, die das Handy meint (nur für die Weckaufgabe geprüft).
         */
        fun command(context: Context, action: String, plan: WakePlan? = null, remoteAlarmId: String? = null, remoteTarget: Long? = null) {
            val intent = Intent(context, WakeService::class.java).setAction(action)
            if (remoteAlarmId != null) intent.putExtra(EXTRA_REMOTE_ALARM_ID, remoteAlarmId)
            if (remoteTarget != null) intent.putExtra(EXTRA_REMOTE_TARGET, remoteTarget)
            if (plan != null) {
                AlarmReceiver.putPlan(intent, plan)
                ContextCompat.startForegroundService(context, intent)
            } else if (running) {
                // Nur an einen laufenden Dienst: Ein Start aus dem Hintergrund wäre verboten und unnötig.
                context.startService(intent)
            }
        }
    }
}
