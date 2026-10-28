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
import com.phasewake.app.core.PhoneAlert
import com.phasewake.app.core.WakeTask
import com.phasewake.app.core.Snooze
import com.phasewake.app.core.WakeDetector
import com.phasewake.app.core.WakePlan
import com.phasewake.app.core.WakeReason
import com.phasewake.app.shared.AlarmSnapshot
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.RingMessage
import com.phasewake.app.wear.R
import com.phasewake.app.wear.schedule.AlarmReceiver
import com.phasewake.app.wear.schedule.AlarmScheduler
import com.phasewake.app.wear.schedule.DirectBoot
import com.phasewake.app.wear.ui.RingActivity
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        /** Vor dem ersten Entsperren begonnen: kein Messen, kein Speicher. */
        val locked: Boolean = false,
        /** Uhr-Ton und Name aus [DirectBoot], solange der Wecker selbst nicht lesbar ist. */
        var lockedSound: Boolean = false,
        var lockedLabel: String = "",
        /** Beenden nur über die Weckaufgabe am Handy (siehe [checkTaskOnPhone]). */
        var taskOnPhone: Boolean = false,
    )

    private val app by lazy { wearApp }
    private var taskCheck: kotlinx.coroutines.Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val escalation by lazy { Escalation(this) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var night: Night? = null
    private var notification: android.app.Notification? = null
    private var onset: OnsetMonitor? = null

    /** «Gute Nacht» ist angenommen, die Messung startet gleich: Dienst nicht beenden. */
    private var goodNightPending = false

    /** Zählt die Starts von «Gute Nacht»; ein überholter Start bricht ab. */
    private var goodNightRun = 0
    private val onsetTimeout = Runnable { stopGoodNight() }

    private val deadline = Runnable { onDeadline() }
    private val snoozeEnd = Runnable { ring() }
    private val ringTimeout = Runnable { onRingTimeout() }
    private val hardLimit = Runnable { onHardLimit() }

    /** Laufende Abschlüsse ([finishNight]); bis sie gespeichert und neu geplant haben, bleibt der Dienst. */
    private var finishing = 0

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val plan = intent?.let(AlarmReceiver::planFrom)
        when (intent?.action) {
            ACTION_START -> plan?.let(::adopt)
            ACTION_DEADLINE -> if (plan == null) onDeadline() else if (adopt(plan)) onDeadline()
            ACTION_SNOOZE_END -> if (plan == null) ring() else if (adopt(plan)) ring()
            ACTION_SNOOZE -> snooze(notifyPhone = true)
            ACTION_PHONE_SNOOZE -> if (isForThisNight(intent)) snooze(notifyPhone = true, fromPhone = true)
            ACTION_DISMISS -> if (isForThisNight(intent)) dismiss(notifyPhone = true)
            ACTION_CANCEL -> cancelMeasuring()
            ACTION_GOOD_NIGHT -> intent.getStringExtra(EXTRA_ALARM_ID)?.let(::startGoodNight)
            ACTION_GOOD_NIGHT_RESUME -> startGoodNight(null)
            ACTION_GOOD_NIGHT_CANCEL -> stopGoodNight()
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

    private fun isIdle() = night == null && onset == null && !goodNightPending && finishing == 0

    private fun stopIfIdle() {
        if (isIdle()) stopSelf()
    }

    /** Befehl vom Handy: Nennt er einen anderen Wecker als den laufenden, gilt er nicht dieser Nacht. */
    private fun isForThisNight(intent: Intent): Boolean {
        val remote = intent.getStringExtra(EXTRA_REMOTE_ALARM_ID) ?: return true
        val current = night?.plan?.alarmId ?: return true
        return remote == current
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
        app.ringState.publish(RingUi.Idle)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Einschlaf-Modus

    /**
     * «Gute Nacht»: misst stromsparend, bis das Einschlafen erkannt ist, und legt dann die Zielzeit
     * auf Einschlafzeit + Schlafdauer (höchstens die eingestellte Weckzeit).
     *
     * Startzeit und Wecker liegen in [GoodNightState]. Endet der Prozess, nimmt der Prüf-Alarm
     * (alle 15 Minuten) den Modus mit [requestedAlarmId] `null` wieder auf. Die Einschlaf-Erkennung
     * beginnt dann von vorn; die Zielzeit liegt so höchstens etwas später, nie nach der eingestellten Weckzeit.
     */
    private fun startGoodNight(requestedAlarmId: String?) {
        if (night != null || onset != null || goodNightPending) return
        // Vor dem Entsperren nicht lesbar. Ohne Sensorrechte kein Einschlaf-Modus: dann gilt die eingestellte Weckzeit.
        if (!DirectBoot.isUnlocked(this) || !goForeground(Notifications.goodNight(this), measuring = true)) {
            forgetGoodNight()
            stopIfIdle()
            return
        }
        goodNightPending = true
        goodNightActive = true
        val run = ++goodNightRun
        fun stillWanted() = goodNightPending && run == goodNightRun
        lifecycleScope.launch {
            val resumed = if (requestedAlarmId == null) app.goodNight.load() else null
            val alarmId = requestedAlarmId ?: resumed?.alarmId
            if (alarmId == null) {
                // Nichts wieder aufzunehmen.
                if (stillWanted()) stopGoodNight()
                return@launch
            }
            val since = resumed?.since ?: Instant.now()
            if (resumed == null) app.goodNight.set(alarmId, since)
            // Inzwischen abgebrochen (oder ein Fenster hat begonnen): den eben geschriebenen Stand wieder löschen.
            if (!stillWanted()) {
                // Ein neuerer Start schreibt seinen eigenen Stand; dann nichts löschen.
                if (resumed == null && run == goodNightRun) app.goodNight.clear()
                return@launch
            }
            AlarmScheduler.armGoodNightCheck(this@WakeService)
            val spec = app.alarms.current().alarms.firstOrNull { it.id == alarmId }
            val duration = spec?.sleepDurationMinutes
            // Weckzeit der Nacht, in der «Gute Nacht» begann (auch beim Wiederaufnehmen).
            val latest = spec?.let { AlarmSchedule.nextTarget(it, since, ZoneId.systemDefault()) }
            val giveUpAt = latest?.minus(ONSET_GIVE_UP)
            // Nur für die kommende Nacht: liegt die Weckzeit weiter weg, zeigt die Startseite einen Hinweis.
            if (spec == null || duration == null || latest == null || giveUpAt == null ||
                Duration.between(since, latest) > GOOD_NIGHT_MAX_AHEAD || !Instant.now().isBefore(giveUpAt)
            ) {
                if (stillWanted()) stopGoodNight()
                return@launch
            }
            val threshold = Calibration.calibrate(NightHistory.motionsForCalibration(app.nights.load())).lightThreshold
            if (!stillWanted()) return@launch
            // giveUpAt: hartes Zeitlimit nach Uhrzeit, auch wenn der Zeitgeber im Tiefschlaf stehen bleibt.
            onset = OnsetMonitor(this@WakeService, threshold, giveUpAt, onGiveUp = ::stopGoodNight) { asleep ->
                val target = AlarmSchedule.sleepTarget(asleep, duration, latest)
                val plan = WakePlan(alarmId, AlarmSchedule.windowStart(target, spec.windowMinutes), target)
                Log.i(TAG, "Eingeschlafen $asleep, neue Zielzeit $target")
                onset?.stop()
                onset = null
                goodNightActive = false
                forgetGoodNight()
                app.appScope.launch {
                    app.onsetOverride.set(plan)
                    app.scheduler.reschedule()
                }
                stopIfIdle()
            }.also { it.start() }
            goodNightPending = false
            // Spätestens 15 Minuten vor der eingestellten Weckzeit aufgeben: dann gilt sie.
            handler.postDelayed(onsetTimeout, Duration.between(Instant.now(), giveUpAt).toMillis().coerceAtLeast(0))
        }
    }

    private fun stopGoodNight() {
        goodNightPending = false
        handler.removeCallbacks(onsetTimeout)
        onset?.stop()
        onset = null
        goodNightActive = false
        forgetGoodNight()
        stopIfIdle()
    }

    /** «Gute Nacht» ist vorbei: Stand löschen und keine Prüfung mehr. */
    private fun forgetGoodNight() {
        AlarmScheduler.cancelGoodNightCheck(this)
        if (DirectBoot.isUnlocked(this)) app.appScope.launch { app.goodNight.clear() }
    }

    // ---------------------------------------------------------------- Messen

    /**
     * Macht [plan] zur laufenden Nacht. Klingelt oder schlummert schon eine andere, bleibt diese: [plan]
     * wird nach ihrem Ende nachgeholt ([AlarmScheduler.noteNightEnded]). Eine Messung für einen anderen
     * Plan weicht dagegen (der Alarm, der jetzt kommt, ist der frühere oder der gültige).
     * @return `true`, wenn [plan] jetzt die laufende Nacht ist.
     */
    private fun adopt(plan: WakePlan): Boolean {
        val current = night
        if (current != null && current.plan != plan && current.phase != Phase.MEASURING) {
            Log.i(TAG, "Wecker ${plan.alarmId} wartet, bis Wecker ${current.plan.alarmId} beendet ist")
            return false
        }
        begin(plan)
        return true
    }

    private fun begin(plan: WakePlan) {
        if (night?.plan == plan) return
        // Das Fenster beginnt: «Gute Nacht» ist vorbei, auch ein nach einem Prozessende nur gespeicherter Stand.
        goodNightPending = false
        handler.removeCallbacks(onsetTimeout)
        onset?.stop()
        onset = null
        goodNightActive = false
        forgetGoodNight()
        night?.let { old ->
            // Eine Messung für einen anderen Plan still verwerfen (kein «stop» ans Handy, nichts erledigt):
            // Ihr Wecker wird danach neu geplant. Ein hängender alter Weckvorgang wird beendet.
            if (old.phase == Phase.MEASURING) abandonMeasuring(old) else finishNight()
        }

        // Vor dem ersten Entsperren sind Wecker, Nächte und Stand nicht lesbar: ohne Messung bis zur
        // Zielzeit warten und dort sicher wecken. Ebenso ohne Sensorrechte (dann nicht als «health»).
        val locked = !DirectBoot.isUnlocked(this)
        val canMeasure = goForeground(Notifications.window(this, formatTime(plan.target)), measuring = !locked)
        holdWakeLock(Duration.between(Instant.now(), plan.target).toMillis().coerceAtLeast(0) + WAKE_LOCK_MARGIN_MS)

        val current = Night(plan, spec = null, detector = WakeDetector(), locked = locked)
        if (locked) {
            DirectBoot.load(this)?.takeIf { it.plan.alarmId == plan.alarmId }?.let {
                current.lockedSound = it.watchSound
                current.lockedLabel = it.label
            }
        }
        night = current
        measuringPlan = plan
        activePlan = plan
        // Nach einem Neustart zur Zielzeit oder beim Schlummer-Ende liegt sie schon zurück: keine Sicherung mehr.
        if (plan.target.isAfter(Instant.now())) AlarmScheduler.setDeadline(this, plan)
        handler.postDelayed(deadline, Duration.between(Instant.now(), plan.target).toMillis().coerceAtLeast(0))
        // Hartes Zeitlimit: Klingeln und Schlummern sind lange vorher zu Ende.
        handler.postDelayed(hardLimit, Duration.between(Instant.now(), plan.target.plus(NIGHT_HARD_LIMIT)).toMillis().coerceAtLeast(0))
        if (locked) return

        lifecycleScope.launch {
            // Wecker und kalibrierte Schwellen laden. Auch wenn schon geweckt wird (Neustart zur
            // Zielzeit), braucht der Weckvorgang den Wecker: Name, Uhr-Ton, einmalig.
            val spec = app.alarms.current().alarms.firstOrNull { it.id == plan.alarmId }
            val config = Calibration.calibrate(NightHistory.motionsForCalibration(app.nights.load()))
            // Stand vor einem Neustart des Dienstes: erstes Klingeln, Grund und Schlummer-Runden.
            val saved = app.nightProgress.load(plan)
            if (night !== current) return@launch
            current.spec = spec
            if (saved != null) {
                saved.firstRing?.let { first -> if (current.firstRing?.isAfter(first) != false) current.firstRing = first }
                saved.reason?.let { current.reason = it }
                current.snoozes = maxOf(current.snoozes, saved.snoozes)
            }
            when (current.phase) {
                Phase.MEASURING -> {
                    current.detector = WakeDetector(config)
                    if (canMeasure) startSensors(current)
                }
                Phase.RINGING -> {
                    checkTaskOnPhone(current)
                    publishRinging(current)
                    if (saved != null) goForeground(ringingNotification(current), measuring = false)
                    // Nach einem Neustart ging «klingeln» mit 0 Runden ans Handy: korrigierte Restzahl nachschicken.
                    if (saved != null && saved.snoozes > 0) sendRing(current)
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

    /** Messung für einen anderen Plan aufgeben, ohne Aufzeichnung und ohne Neuplanen (das folgt mit dem neuen Plan). */
    private fun abandonMeasuring(n: Night) {
        Log.i(TAG, "Messung für ${n.plan.alarmId} verworfen, anderer Plan beginnt")
        night = null
        measuringPlan = null
        activePlan = null
        handler.removeCallbacksAndMessages(null)
        n.sensors?.stop()
    }

    /** Am Wecker wurde etwas geändert (aus, gelöscht, verschoben): Messung ohne Aufzeichnung beenden. */
    private fun cancelMeasuring() {
        val n = night ?: return
        if (n.phase != Phase.MEASURING) return
        Log.i(TAG, "Messung abgebrochen: Wecker geändert")
        night = null
        measuringPlan = null
        activePlan = null
        handler.removeCallbacksAndMessages(null)
        n.sensors?.stop()
        AlarmScheduler.cancelDeadlineAndSnooze(this)
        NotificationManagerCompat.from(this).cancel(Notifications.ID_SERVICE)
        releaseWakeLock()
        app.appScope.launch { app.scheduler.reschedule() }
        stopIfIdle()
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

        checkTaskOnPhone(n)
        publishRinging(n)
        goForeground(ringingNotification(n), measuring = false)
        // Aus dem Dienst nur mit neuer Aufgabe möglich (Lint «WearRecents» trifft hier nicht zu).
        @Suppress("WearRecents")
        startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        escalation.start(withSound = watchSound(n))
        handler.postDelayed(ringTimeout, RING_TIMEOUT_MS)
        saveProgress(n)

        // Immer melden: Das Handy entscheidet selbst (Zweitwecker, Sonnenaufgang, Unterdrückung der Sicherung).
        sendRing(n)
    }

    private fun ringingNotification(n: Night) =
        Notifications.ringing(this, reasonText(n.reason ?: WakeReason.DEADLINE), Snooze.MAX_COUNT - n.snoozes)

    /** Meldet dem Handy «klingeln» mit Zielzeit, Sendezeit und verbleibenden Schlummer-Runden. */
    private fun sendRing(n: Night) {
        val message = RingMessage(n.plan.alarmId, n.plan.target.toEpochMilli(), System.currentTimeMillis(), Snooze.MAX_COUNT - n.snoozes)
        lifecycleScope.launch { app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_RING, message.encode()) }
    }

    /**
     * «stop» ans Handy. Beim Schlummern mit [snoozeUntil]: Das Handy stellt dann eine eigene Sicherung
     * kurz nach dem Schlummer-Ende, falls die Uhr nicht wieder klingelt. Ohne das Feld ist die Nacht beendet.
     */
    private fun stopMessage(plan: WakePlan, snoozeUntil: Instant? = null) = RingMessage(
        alarmId = plan.alarmId,
        target = plan.target.toEpochMilli(),
        sentAt = System.currentTimeMillis(),
        snoozeUntil = snoozeUntil?.toEpochMilli(),
    ).encode()

    private fun watchSound(n: Night) = n.spec?.watchSound ?: n.lockedSound

    private fun label(n: Night) = n.spec?.label ?: n.lockedLabel

    /** Hält den Stand der Nacht fest, damit ein Neustart des Dienstes ihn nicht verliert (vor dem Entsperren nicht möglich). */
    private fun saveProgress(n: Night) {
        if (!DirectBoot.isUnlocked(this)) return
        val firstRing = n.firstRing
        val reason = n.reason ?: WakeReason.DEADLINE
        val snoozes = n.snoozes
        app.appScope.launch { app.nightProgress.save(n.plan, firstRing, reason, snoozes) }
    }

    private fun publishRinging(n: Night) {
        app.ringState.publish(
            RingUi.Ringing(label(n), n.reason ?: WakeReason.DEADLINE, n.firstRing ?: Instant.now(), Snooze.MAX_COUNT - n.snoozes, n.taskOnPhone),
        )
    }

    /**
     * Weckaufgabe ([WakeTask.MATH]): Die Uhr zeigt «Aufgabe am Handy lösen» statt Halten. Sicherheit:
     * Nur wenn das Handy für diesen Wecker mitweckt und erreichbar ist; sonst – und sobald es nicht mehr
     * erreichbar ist (alle [TASK_CHECK_MS] geprüft) – wieder Halten. Nie ein Wecker, der sich nicht beenden lässt.
     */
    private fun checkTaskOnPhone(n: Night) {
        val spec = n.spec
        val wanted = !n.locked && spec != null && spec.wakeTask == WakeTask.MATH && spec.phone != PhoneAlert.OFF
        n.taskOnPhone = wanted
        taskCheck?.cancel()
        if (!wanted) return
        taskCheck = lifecycleScope.launch {
            while (night === n && n.taskOnPhone && n.phase != Phase.MEASURING) {
                val reachable = withTimeoutOrNull(TASK_REACH_TIMEOUT_MS) { app.sync.isReachable(DataLayer.CAPABILITY_PHONE) } ?: false
                if (night !== n) return@launch
                if (!reachable) {
                    Log.i(TAG, "Handy nicht erreichbar: Beenden durch Halten")
                    n.taskOnPhone = false
                    when (n.phase) {
                        Phase.RINGING -> publishRinging(n)
                        Phase.SNOOZED -> (app.ringState.ui.value as? RingUi.Snoozed)?.let { app.ringState.publish(it.copy(taskOnPhone = false)) }
                        Phase.MEASURING -> Unit
                    }
                    return@launch
                }
                delay(TASK_CHECK_MS)
            }
        }
    }

    /**
     * [notifyPhone]: Hat der Nutzer geschlummert, schweigt auch das Handy; beim Auto-Schlummern klingelt es weiter.
     * [fromPhone]: Am Handy geschlummert. Hat die Uhr keine Runde mehr, klingelt sie weiter und bittet
     * das Handy mit «klingeln», ebenfalls weiterzuklingeln.
     */
    private fun snooze(notifyPhone: Boolean, fromPhone: Boolean = false) {
        val n = night ?: return
        if (n.phase != Phase.RINGING) return
        if (!Snooze.canSnooze(n.snoozes)) {
            if (fromPhone) sendRing(n)
            return
        }
        stopRinging()
        n.snoozes++
        n.phase = Phase.SNOOZED
        val until = Snooze.nextRing(Instant.now())
        app.ringState.publish(RingUi.Snoozed(label(n), until, Snooze.MAX_COUNT - n.snoozes, n.taskOnPhone))
        goForeground(Notifications.snoozed(this, formatTime(until)), measuring = false)
        AlarmScheduler.setSnoozeEnd(this, n.plan, until)
        // Startet die Uhr während des Schlummerns neu, weckt sie zum Schlummer-Ende (auch vor dem Entsperren).
        DirectBoot.saveRingAt(this, n.plan, until)
        holdWakeLock(Duration.between(Instant.now(), until).toMillis() + RING_TIMEOUT_MS + WAKE_LOCK_MARGIN_MS)
        handler.postDelayed(snoozeEnd, Duration.between(Instant.now(), until).toMillis())
        saveProgress(n)
        if (notifyPhone) lifecycleScope.launch { app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_STOP, stopMessage(n.plan, snoozeUntil = until)) }
    }

    /** Beendet die Nacht; der Dienst endet, sobald [finishNight] gespeichert und neu geplant hat. */
    private fun dismiss(notifyPhone: Boolean) {
        if (night == null) return
        stopRinging()
        finishNight(notifyPhone)
    }

    /** Niemand reagiert: schlummern, solange es geht, sonst beenden (das Handy klingelt weiter). */
    private fun onRingTimeout() {
        val n = night ?: return
        if (n.phase != Phase.RINGING) return
        // Das Handy klingelt als Sicherung weiter: niemand hat reagiert.
        if (Snooze.canSnooze(n.snoozes)) snooze(notifyPhone = false) else dismiss(notifyPhone = false)
    }

    /** Sicherheitsnetz: Die Nacht läuft weit über die Zielzeit hinaus. Beenden statt endlos messen oder klingeln. */
    private fun onHardLimit() {
        if (night == null) return
        Log.w(TAG, "Zeitlimit der Nacht erreicht, beende")
        dismiss(notifyPhone = false)
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
        activePlan = null
        handler.removeCallbacksAndMessages(null)
        n.sensors?.stop()
        AlarmScheduler.cancelDeadlineAndSnooze(this)
        app.ringState.publish(RingUi.Idle)
        NotificationManagerCompat.from(this).cancel(Notifications.ID_SERVICE)
        releaseWakeLock()

        val record = n.firstRing?.let { wokeAt ->
            NightRecord(n.plan.alarmId, n.plan.windowStart, n.plan.target, wokeAt, n.reason ?: WakeReason.DEADLINE, n.snoozes, n.epochs.toList())
        }
        val endedAt = Instant.now()
        // Vor dem ersten Entsperren nichts speichern (nicht lesbar). Nach dem Entsperren holt
        // SystemEventReceiver nach: Nacht erledigt, Einmal-Wecker aus, neu planen.
        if (!DirectBoot.isUnlocked(this)) {
            // Der abgelegte Plan danach wird zum Eintrag: Bleibt die Uhr gesperrt, weckt sie auch am nächsten Morgen.
            DirectBoot.promoteNext(this)?.let { DirectBoot.setDeadlineAt(this, it) }
            if (record != null) DirectBoot.markRang(this, n.plan.alarmId, n.plan.target)
            if (notifyPhone) app.appScope.launch { app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_STOP, stopMessage(n.plan)) }
            handler.post { stopIfIdle() }
            return
        }
        // Als Erstes und sofort: Die Nacht gilt als erledigt, auch für ein gleichzeitiges Neuplanen
        // (z. B. die Startseite, die nach dem Weckbildschirm wieder erscheint).
        if (record != null) app.scheduler.markDone(n.plan.alarmId, doneUntil(n.plan, n.spec))
        if (notifyPhone) app.appScope.launch { app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_STOP, stopMessage(n.plan)) }

        // Prozessweiter Bereich. Der Dienst bleibt (Vordergrund), bis gespeichert und neu geplant ist:
        // Sonst könnte das System den Prozess vorher beenden, und für morgen stünde kein Wecker.
        // Jeder Schritt für sich abgesichert, damit ein Fehler die folgenden nicht überspringt.
        finishing++
        app.appScope.launch {
            var disabled: AlarmSnapshot? = null
            try {
                // Aus dem Speicher statt aus der Nacht: Nach einem Neustart des Dienstes fehlt n.spec.
                val spec = runStep("Wecker laden") { app.alarms.current().alarms.firstOrNull { it.id == n.plan.alarmId } }
                if (record != null) {
                    if (n.spec == null) app.scheduler.markDone(n.plan.alarmId, doneUntil(n.plan, spec))
                    runStep("Erledigt speichern") { app.scheduler.saveDone() }
                    // Zielzeiten anderer Wecker, die während dieser Nacht fällig wurden, nachholen.
                    runStep("Nacht-Zeitraum merken") { app.scheduler.noteNightEnded(n.plan.windowStart, endedAt) }
                }
                runStep("Einschlaf-Ausnahme löschen") { app.onsetOverride.clear() }
                runStep("Stand löschen") { app.nightProgress.clear(n.plan) }
                if (record != null) runStep("Nacht speichern") { app.nights.add(record) }
                if (spec?.isOneShot == true && spec.enabled) disabled = runStep("Einmal-Wecker ausschalten") { app.alarms.setEnabled(spec.id, false) }
                runStep("Neu planen") { app.scheduler.reschedule() }
            } finally {
                withContext(Dispatchers.Main) {
                    finishing--
                    stopIfIdle()
                }
            }
            // Senden darf das Ende des Dienstes überdauern; die Daten liegen schon lokal.
            if (record != null) app.sync.publishNight(record)
            disabled?.let { app.sync.publishAlarms(it) }
        }
    }

    /**
     * Bis wann die Nacht erledigt ist: die eingestellte Weckzeit, auch wenn «Gute Nacht» früher geweckt
     * hat. Sonst begänne danach dieselbe Nacht noch einmal (Fenster vor der eingestellten Zeit).
     */
    private fun doneUntil(plan: WakePlan, spec: AlarmSpec?): Instant {
        val nominal = spec?.let { AlarmSchedule.nextTarget(it, plan.target.minusMillis(1), ZoneId.systemDefault()) }
            ?: return plan.target
        return if (nominal.isAfter(plan.target) && Duration.between(plan.target, nominal) <= GOOD_NIGHT_MAX_AHEAD) nominal else plan.target
    }

    /** Führt einen Schritt des Abschlusses aus; ein Fehler wird geloggt und ergibt `null`. */
    private suspend fun <T> runStep(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "$what fehlgeschlagen", e)
        null
    }

    /**
     * Vordergrund mit dem passenden Typ: «health» zum Messen (braucht Sensorrechte), sonst
     * «systemExempted» (Wecker-Apps). Schlägt «health» fehl, läuft der Dienst ohne Messung weiter.
     * @return `true`, wenn gemessen werden darf.
     */
    // Die Diensttypen (API 34) setzt ServiceCompat auf älteren Versionen nicht; das Einbetten ist unbedenklich.
    @android.annotation.SuppressLint("InlinedApi")
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

        /** Weckaufgabe: so oft prüfen, ob das Handy noch erreichbar ist, und so lange auf die Antwort warten. */
        private const val TASK_CHECK_MS = 30_000L
        private const val TASK_REACH_TIMEOUT_MS = 5_000L
        const val ACTION_START = "com.phasewake.app.wake.START"
        const val ACTION_DEADLINE = "com.phasewake.app.wake.DEADLINE"
        const val ACTION_SNOOZE_END = "com.phasewake.app.wake.SNOOZE_END"
        const val ACTION_SNOOZE = "com.phasewake.app.wake.SNOOZE"
        const val ACTION_DISMISS = "com.phasewake.app.wake.DISMISS"
        const val ACTION_PHONE_SNOOZE = "com.phasewake.app.wake.PHONE_SNOOZE"
        const val ACTION_CANCEL = "com.phasewake.app.wake.CANCEL"
        const val ACTION_GOOD_NIGHT = "com.phasewake.app.wake.GOOD_NIGHT"
        const val ACTION_GOOD_NIGHT_CANCEL = "com.phasewake.app.wake.GOOD_NIGHT_CANCEL"
        private const val ACTION_GOOD_NIGHT_RESUME = "com.phasewake.app.wake.GOOD_NIGHT_RESUME"
        private const val EXTRA_ALARM_ID = "goodNightAlarmId"
        private const val EXTRA_REMOTE_ALARM_ID = "remoteAlarmId"
        private val ONSET_GIVE_UP: Duration = Duration.ofMinutes(15)

        /** «Gute Nacht» nur, wenn die eingestellte Weckzeit höchstens so weit entfernt ist. */
        val GOOD_NIGHT_MAX_AHEAD: Duration = Duration.ofHours(16)

        /** Spätestens so lange nach der Zielzeit endet eine Nacht (4 × 10 Min Klingeln + 3 × 5 Min Schlummern passen gut hinein). */
        private val NIGHT_HARD_LIMIT: Duration = Duration.ofHours(2)

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

        /** Plan der laufenden Nacht (Messen, Klingeln oder Schlummern); `null`, wenn keine läuft. */
        @Volatile
        var activePlan: WakePlan? = null
            private set

        /** Wartet «Gute Nacht» gerade in diesem Prozess auf das Einschlafen? */
        @Volatile
        var goodNightActive: Boolean = false
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
         */
        fun command(context: Context, action: String, plan: WakePlan? = null, remoteAlarmId: String? = null) {
            val intent = Intent(context, WakeService::class.java).setAction(action)
            if (remoteAlarmId != null) intent.putExtra(EXTRA_REMOTE_ALARM_ID, remoteAlarmId)
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
