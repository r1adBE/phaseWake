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
import com.phasewake.app.core.AlarmSchedule
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
        var spec: AlarmSpec?,
        var detector: WakeDetector,
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
    private var onset: OnsetMonitor? = null

    /** «Gute Nacht» ist angenommen, die Messung startet gleich: Dienst nicht beenden. */
    private var goodNightPending = false
    private val onsetTimeout = Runnable { stopGoodNight() }

    private val deadline = Runnable { onDeadline() }
    private val snoozeEnd = Runnable { ring() }
    private val ringTimeout = Runnable { onRingTimeout() }

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val plan = intent?.let(AlarmReceiver::planFrom)
        when (intent?.action) {
            ACTION_START -> plan?.let(::begin)
            ACTION_DEADLINE -> if (night == null) plan?.let { begin(it); onDeadline() } else onDeadline()
            ACTION_SNOOZE_END -> if (night == null) plan?.let { begin(it); ring() } else ring()
            ACTION_SNOOZE -> snooze(notifyPhone = true)
            ACTION_DISMISS -> dismiss(notifyPhone = true)
            ACTION_CANCEL -> cancelMeasuring()
            ACTION_GOOD_NIGHT -> intent.getStringExtra(EXTRA_ALARM_ID)?.let(::startGoodNight)
            ACTION_GOOD_NIGHT_CANCEL -> stopGoodNight()
        }
        // Jeder Start über startForegroundService verlangt eine Vordergrund-Meldung, auch wenn schon eine steht.
        if (plan != null || intent?.action == ACTION_GOOD_NIGHT) notification?.let { goForeground(it, measuring = night?.phase == Phase.MEASURING || onset != null || goodNightPending) }
        if (night == null && onset == null && !goodNightPending) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        measuringPlan = null
        handler.removeCallbacksAndMessages(null)
        onset?.stop()
        app.goodNight.value = null
        night?.sensors?.stop()
        escalation.stop()
        releaseWakeLock()
        app.ringState.publish(RingUi.Idle)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Einschlaf-Modus

    /**
     * «Gute Nacht»: misst stromsparend, bis das Einschlafen erkannt ist, und legt dann die Zielzeit
     * auf Einschlafzeit + Schlafdauer (höchstens die eingestellte Weckzeit).
     */
    private fun startGoodNight(alarmId: String) {
        if (night != null || onset != null || goodNightPending) return
        if (!goForeground(Notifications.goodNight(this), measuring = true)) {
            // Ohne Sensorrechte kein Einschlaf-Modus: dann gilt die eingestellte Weckzeit.
            stopSelf()
            return
        }
        goodNightPending = true
        app.goodNight.value = Instant.now()
        lifecycleScope.launch {
            val spec = app.alarms.current().alarms.firstOrNull { it.id == alarmId }
            val duration = spec?.sleepDurationMinutes
            val latest = spec?.let { AlarmSchedule.nextTarget(it, Instant.now(), ZoneId.systemDefault()) }
            if (spec == null || duration == null || latest == null) {
                goodNightPending = false
                stopGoodNight()
                return@launch
            }
            val threshold = Calibration.calibrate(NightHistory.motionsForCalibration(app.nights.load())).lightThreshold
            onset = OnsetMonitor(this@WakeService, threshold) { asleep ->
                val target = AlarmSchedule.sleepTarget(asleep, duration, latest)
                val plan = WakePlan(alarmId, AlarmSchedule.windowStart(target, spec.windowMinutes), target)
                Log.i(TAG, "Eingeschlafen $asleep, neue Zielzeit $target")
                onset?.stop()
                onset = null
                app.goodNight.value = null
                app.appScope.launch {
                    app.onsetOverride.set(plan)
                    app.scheduler.reschedule()
                }
                if (night == null) stopSelf()
            }.also { it.start() }
            goodNightPending = false
            // Spätestens 15 Minuten vor der eingestellten Weckzeit aufgeben: dann gilt sie.
            handler.postDelayed(onsetTimeout, Duration.between(Instant.now(), latest.minus(ONSET_GIVE_UP)).toMillis().coerceAtLeast(0))
        }
    }

    private fun stopGoodNight() {
        goodNightPending = false
        handler.removeCallbacks(onsetTimeout)
        onset?.stop()
        onset = null
        app.goodNight.value = null
        if (night == null) stopSelf()
    }

    // ---------------------------------------------------------------- Messen

    private fun begin(plan: WakePlan) {
        if (night?.plan == plan) return
        if (onset != null) {
            handler.removeCallbacks(onsetTimeout)
            onset?.stop()
            onset = null
            app.goodNight.value = null
        }
        if (night != null) finishNight() // neuer Plan ersetzt einen hängenden alten

        // Ohne Sensorrechte darf der Dienst nicht als «health» laufen: dann ohne Messung bis zur
        // Zielzeit warten und dort sicher wecken.
        val canMeasure = goForeground(Notifications.window(this, formatTime(plan.target)), measuring = true)
        holdWakeLock(Duration.between(Instant.now(), plan.target).toMillis().coerceAtLeast(0) + WAKE_LOCK_MARGIN_MS)

        val current = Night(plan, spec = null, detector = WakeDetector())
        night = current
        measuringPlan = plan
        app.scheduler.setDeadline(plan)
        handler.postDelayed(deadline, Duration.between(Instant.now(), plan.target).toMillis().coerceAtLeast(0))

        lifecycleScope.launch {
            // Wecker und kalibrierte Schwellen laden. Auch wenn schon geweckt wird (Neustart zur
            // Zielzeit), braucht der Weckvorgang den Wecker: Name, Uhr-Ton, einmalig.
            val spec = app.alarms.current().alarms.firstOrNull { it.id == plan.alarmId }
            val config = Calibration.calibrate(NightHistory.motionsForCalibration(app.nights.load()))
            if (night !== current) return@launch
            current.spec = spec
            when (current.phase) {
                Phase.MEASURING -> {
                    current.detector = WakeDetector(config)
                    if (canMeasure) startSensors(current)
                }
                Phase.RINGING -> {
                    publishRinging(current)
                    if (spec?.watchSound == true) escalation.start(withSound = true)
                }
                Phase.SNOOZED -> Unit
            }
        }
    }

    private fun startSensors(n: Night) {
        val elapsedNow = SystemClock.elapsedRealtimeNanos()
        val sinceStart = Duration.between(n.plan.windowStart, Instant.now()).toNanos()
        n.sensors = SensorSession(this, elapsedNow - sinceStart, SystemClock::elapsedRealtimeNanos) { epochs ->
            onEpochs(n, epochs)
        }.also { it.start() }
    }

    /** Am Wecker wurde etwas geändert (aus, gelöscht, verschoben): Messung ohne Aufzeichnung beenden. */
    private fun cancelMeasuring() {
        val n = night ?: return
        if (n.phase != Phase.MEASURING) return
        Log.i(TAG, "Messung abgebrochen: Wecker geändert")
        night = null
        measuringPlan = null
        handler.removeCallbacksAndMessages(null)
        n.sensors?.stop()
        app.scheduler.cancelDeadlineAndSnooze()
        NotificationManagerCompat.from(this).cancel(Notifications.ID_SERVICE)
        releaseWakeLock()
        app.appScope.launch { app.scheduler.reschedule() }
        stopSelf()
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
        measuringPlan = null
        val now = Instant.now()
        if (n.firstRing == null) n.firstRing = now
        val reason = n.reason ?: WakeReason.DEADLINE
        holdWakeLock(RING_TIMEOUT_MS + WAKE_LOCK_MARGIN_MS)

        publishRinging(n)
        goForeground(Notifications.ringing(this, reasonText(reason)), measuring = false)
        startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        escalation.start(withSound = n.spec?.watchSound == true)
        handler.postDelayed(ringTimeout, RING_TIMEOUT_MS)

        // Immer melden: Das Handy entscheidet selbst (Zweitwecker, Sonnenaufgang, Unterdrückung der Sicherung).
        lifecycleScope.launch {
            app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_RING, n.plan.alarmId.encodeToByteArray())
        }
    }

    private fun publishRinging(n: Night) {
        app.ringState.publish(
            RingUi.Ringing(n.spec?.label.orEmpty(), n.reason ?: WakeReason.DEADLINE, n.firstRing ?: Instant.now(), Snooze.MAX_COUNT - n.snoozes),
        )
    }

    /** [notifyPhone]: Hat der Nutzer geschlummert, schweigt auch das Handy; beim Auto-Schlummern klingelt es weiter. */
    private fun snooze(notifyPhone: Boolean) {
        val n = night ?: return
        if (n.phase != Phase.RINGING || !Snooze.canSnooze(n.snoozes)) return
        stopRinging()
        n.snoozes++
        n.phase = Phase.SNOOZED
        val until = Snooze.nextRing(Instant.now())
        app.ringState.publish(RingUi.Snoozed(n.spec?.label.orEmpty(), until, Snooze.MAX_COUNT - n.snoozes))
        goForeground(Notifications.snoozed(this, formatTime(until)), measuring = false)
        app.scheduler.setSnoozeEnd(n.plan, until)
        holdWakeLock(Duration.between(Instant.now(), until).toMillis() + RING_TIMEOUT_MS + WAKE_LOCK_MARGIN_MS)
        handler.postDelayed(snoozeEnd, Duration.between(Instant.now(), until).toMillis())
        if (notifyPhone) lifecycleScope.launch { app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_STOP) }
    }

    private fun dismiss(notifyPhone: Boolean) {
        if (night == null) return
        stopRinging()
        finishNight(notifyPhone)
        stopSelf()
    }

    /** Niemand reagiert: schlummern, solange es geht, sonst beenden (das Handy klingelt weiter). */
    private fun onRingTimeout() {
        val n = night ?: return
        if (n.phase != Phase.RINGING) return
        // Das Handy klingelt als Sicherung weiter: niemand hat reagiert.
        if (Snooze.canSnooze(n.snoozes)) snooze(notifyPhone = false) else dismiss(notifyPhone = false)
    }

    private fun stopRinging() {
        handler.removeCallbacks(ringTimeout)
        escalation.stop()
    }

    // ---------------------------------------------------------------- Abschluss

    private fun finishNight(notifyPhone: Boolean = true) {
        val n = night ?: return
        night = null
        measuringPlan = null
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
            app.onsetOverride.clear()
            if (record != null) {
                app.nights.add(record)
                app.sync.publishNight(record)
                app.scheduler.markDone(n.plan.target)
            }
            if (notifyPhone) app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_STOP)
            // Aus dem Speicher statt aus der Nacht: Nach einem Neustart des Dienstes fehlt n.spec.
            val spec = app.alarms.current().alarms.firstOrNull { it.id == n.plan.alarmId }
            if (spec?.isOneShot == true && spec.enabled) app.sync.publishAlarms(app.alarms.setEnabled(spec.id, false))
            app.scheduler.reschedule()
        }
    }

    /**
     * Vordergrund mit dem passenden Typ: «health» zum Messen (braucht Sensorrechte), sonst
     * «systemExempted» (Wecker-Apps). Schlägt «health» fehl, läuft der Dienst ohne Messung weiter.
     * @return `true`, wenn gemessen werden darf.
     */
    private fun goForeground(shown: android.app.Notification, measuring: Boolean): Boolean {
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

    private fun holdWakeLock(millis: Long) {
        releaseWakeLock()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phasewake:window")
            .apply { acquire(millis) }
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
        const val ACTION_CANCEL = "com.phasewake.app.wake.CANCEL"
        const val ACTION_GOOD_NIGHT = "com.phasewake.app.wake.GOOD_NIGHT"
        const val ACTION_GOOD_NIGHT_CANCEL = "com.phasewake.app.wake.GOOD_NIGHT_CANCEL"
        private const val EXTRA_ALARM_ID = "goodNightAlarmId"
        private val ONSET_GIVE_UP: Duration = Duration.ofMinutes(15)

        /** Höchstens so lange klingelt es ohne Reaktion am Stück. */
        private const val RING_TIMEOUT_MS = 10 * 60_000L
        private const val WAKE_LOCK_MARGIN_MS = 20 * 60_000L

        /** Läuft der Dienst gerade? Befehle ohne Plan gehen nur an einen laufenden Dienst. */
        @Volatile
        var running: Boolean = false
            private set

        /** Plan, für den gerade gemessen wird; `null` ausserhalb der Messung. */
        @Volatile
        var measuringPlan: WakePlan? = null
            private set

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

        /** Befehl an den Dienst; [plan] erlaubt einen Neustart, falls er inzwischen beendet wurde. */
        fun command(context: Context, action: String, plan: WakePlan? = null) {
            val intent = Intent(context, WakeService::class.java).setAction(action)
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
