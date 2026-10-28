package com.phasewake.app.wear.wake

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Calibration
import com.phasewake.app.core.Decision
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.NightHistory
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.Snooze
import com.phasewake.app.core.WakeDetector
import com.phasewake.app.core.WakePlan
import com.phasewake.app.core.WakeReason
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.wear.R
import com.phasewake.app.wear.schedule.AlarmReceiver
import com.phasewake.app.wear.ui.RingActivity
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
 */
class WakeService : LifecycleService() {

    private enum class Phase { MEASURING, RINGING, SNOOZED }

    private class Night(
        val plan: WakePlan,
        val spec: AlarmSpec?,
        val detector: WakeDetector,
        val epochs: MutableList<Epoch> = mutableListOf(),
        var phase: Phase = Phase.MEASURING,
        var reason: WakeReason? = null,
        var firstRing: Instant? = null,
        var snoozes: Int = 0,
        var sensors: SensorSession? = null,
    )

    private val app by lazy { wearApp }
    private val handler = Handler(Looper.getMainLooper())
    private val escalation by lazy { Escalation(this) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var night: Night? = null
    private var notification: android.app.Notification? = null

    private val deadline = Runnable { onDeadline() }
    private val snoozeEnd = Runnable { ring() }
    private val ringTimeout = Runnable { onRingTimeout() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val plan = intent?.let(AlarmReceiver::planFrom)
        when (intent?.action) {
            ACTION_START -> plan?.let(::begin)
            ACTION_DEADLINE -> if (night == null) plan?.let { begin(it); onDeadline() } else onDeadline()
            ACTION_SNOOZE_END -> if (night == null) plan?.let { begin(it); ring() } else ring()
            ACTION_SNOOZE -> snooze()
            ACTION_DISMISS -> dismiss()
        }
        // Jeder Start über startForegroundService verlangt eine Vordergrund-Meldung, auch wenn schon eine steht.
        if (plan != null) notification?.let(::goForeground)
        if (night == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        night?.sensors?.stop()
        escalation.stop()
        releaseWakeLock()
        app.ringState.publish(RingUi.Idle)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Messen

    private fun begin(plan: WakePlan) {
        if (night?.plan == plan) return
        if (night != null) finishNight() // neuer Plan ersetzt einen hängenden alten

        goForeground(Notifications.window(this, formatTime(plan.target)))
        val wakeMillis = Duration.between(Instant.now(), plan.target).toMillis().coerceAtLeast(0) + WAKE_LOCK_MARGIN_MS
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phasewake:window")
            .apply { acquire(wakeMillis) }

        val current = Night(plan, spec = null, detector = WakeDetector())
        night = current
        app.scheduler.setDeadline(plan)
        handler.postDelayed(deadline, Duration.between(Instant.now(), plan.target).toMillis().coerceAtLeast(0))

        lifecycleScope.launch {
            // Wecker und kalibrierte Schwellen laden, dann erst messen.
            val spec = app.alarms.current().alarms.firstOrNull { it.id == plan.alarmId }
            val config = Calibration.calibrate(NightHistory.motionsForCalibration(app.nights.load()))
            if (night !== current || current.phase != Phase.MEASURING) return@launch
            night = Night(plan, spec, WakeDetector(config), current.epochs).also(::startSensors)
        }
    }

    private fun startSensors(n: Night) {
        val elapsedNow = SystemClock.elapsedRealtimeNanos()
        val sinceStart = Duration.between(n.plan.windowStart, Instant.now()).toNanos()
        n.sensors = SensorSession(this, elapsedNow - sinceStart, SystemClock::elapsedRealtimeNanos) { epochs ->
            onEpochs(n, epochs)
        }.also { it.start() }
    }

    private fun onEpochs(n: Night, epochs: List<Epoch>) {
        if (night !== n || n.phase != Phase.MEASURING) return
        for (epoch in epochs) {
            n.epochs += epoch
            val decision = n.detector.onEpoch(epoch)
            if (decision is Decision.Wake) {
                n.reason = decision.reason
                ring()
                return
            }
        }
    }

    private fun onDeadline() {
        val n = night ?: return
        if (n.phase == Phase.MEASURING) {
            n.reason = n.detector.finish().reason
            ring()
        }
    }

    // ---------------------------------------------------------------- Wecken

    private fun ring() {
        val n = night ?: return
        if (n.phase == Phase.RINGING) return
        handler.removeCallbacks(deadline)
        handler.removeCallbacks(snoozeEnd)
        n.sensors?.stop()
        n.phase = Phase.RINGING
        val now = Instant.now()
        if (n.firstRing == null) n.firstRing = now
        val reason = n.reason ?: WakeReason.DEADLINE

        val label = n.spec?.label.orEmpty()
        app.ringState.publish(RingUi.Ringing(label, reason, now, Snooze.MAX_COUNT - n.snoozes))
        goForeground(Notifications.ringing(this, reasonText(reason)))
        startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        escalation.start()
        handler.postDelayed(ringTimeout, RING_TIMEOUT_MS)

        if (n.spec?.phoneBackup != false) {
            lifecycleScope.launch {
                app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_RING, n.plan.alarmId.encodeToByteArray())
            }
        }
    }

    private fun snooze() {
        val n = night ?: return
        if (n.phase != Phase.RINGING || !Snooze.canSnooze(n.snoozes)) return
        stopRinging()
        n.snoozes++
        n.phase = Phase.SNOOZED
        val until = Snooze.nextRing(Instant.now())
        app.ringState.publish(RingUi.Snoozed(n.spec?.label.orEmpty(), until, Snooze.MAX_COUNT - n.snoozes))
        goForeground(Notifications.window(this, formatTime(until)))
        app.scheduler.setSnoozeEnd(n.plan, until)
        handler.postDelayed(snoozeEnd, Duration.between(Instant.now(), until).toMillis())
        lifecycleScope.launch { app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_STOP) }
    }

    private fun dismiss() {
        if (night == null) return
        stopRinging()
        finishNight()
        stopSelf()
    }

    /** Niemand reagiert: schlummern, solange es geht, sonst beenden (das Handy klingelt weiter). */
    private fun onRingTimeout() {
        val n = night ?: return
        if (n.phase != Phase.RINGING) return
        if (Snooze.canSnooze(n.snoozes)) snooze() else dismiss()
    }

    private fun stopRinging() {
        handler.removeCallbacks(ringTimeout)
        escalation.stop()
    }

    // ---------------------------------------------------------------- Abschluss

    private fun finishNight() {
        val n = night ?: return
        night = null
        handler.removeCallbacksAndMessages(null)
        n.sensors?.stop()
        app.scheduler.cancelDeadlineAndSnooze()
        app.ringState.publish(RingUi.Idle)
        NotificationManagerCompat.from(this).cancel(Notifications.ID_SERVICE)
        releaseWakeLock()

        val record = n.firstRing?.let { wokeAt ->
            NightRecord(n.plan.alarmId, n.plan.windowStart, n.plan.target, wokeAt, n.reason ?: WakeReason.DEADLINE, n.snoozes, n.epochs.toList())
        }
        // Prozessweiter Bereich: Speichern und Neuplanen dürfen das Ende des Dienstes überdauern.
        app.appScope.launch {
            if (record != null) {
                app.nights.add(record)
                app.sync.publishNight(record)
            }
            app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_STOP)
            if (n.spec?.isOneShot == true) app.sync.publishAlarms(app.alarms.setEnabled(n.spec.id, false))
            app.scheduler.reschedule()
        }
    }

    private fun goForeground(shown: android.app.Notification) {
        notification = shown
        ServiceCompat.startForeground(this, Notifications.ID_SERVICE, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun reasonText(reason: WakeReason) = getString(
        when (reason) {
            WakeReason.LIGHT_SLEEP -> R.string.ring_reason_light
            WakeReason.AWAKE -> R.string.ring_reason_awake
            WakeReason.DEADLINE -> R.string.ring_reason_deadline
        },
    )

    private fun formatTime(instant: Instant): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(instant.atZone(ZoneId.systemDefault()))

    companion object {
        private const val TAG = "WakeService"
        const val ACTION_START = "com.phasewake.app.wake.START"
        const val ACTION_DEADLINE = "com.phasewake.app.wake.DEADLINE"
        const val ACTION_SNOOZE_END = "com.phasewake.app.wake.SNOOZE_END"
        const val ACTION_SNOOZE = "com.phasewake.app.wake.SNOOZE"
        const val ACTION_DISMISS = "com.phasewake.app.wake.DISMISS"

        /** Höchstens so lange klingelt es ohne Reaktion am Stück. */
        private const val RING_TIMEOUT_MS = 10 * 60_000L
        private const val WAKE_LOCK_MARGIN_MS = 20 * 60_000L

        /** Startet die Messung für [plan] als Vordergrunddienst. */
        fun start(context: Context, plan: WakePlan) {
            Log.i(TAG, "Fenster beginnt: $plan")
            ContextCompat.startForegroundService(
                context,
                AlarmReceiver.putPlan(Intent(context, WakeService::class.java).setAction(ACTION_START), plan),
            )
        }

        /** Befehl an den Dienst; [plan] erlaubt einen Neustart, falls er inzwischen beendet wurde. */
        fun command(context: Context, action: String, plan: WakePlan? = null) {
            val intent = Intent(context, WakeService::class.java).setAction(action)
            if (plan != null) {
                AlarmReceiver.putPlan(intent, plan)
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
