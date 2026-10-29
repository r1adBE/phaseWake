package com.phasewake.app.wear.wake

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.PhoneAlert
import com.phasewake.app.core.Snooze
import com.phasewake.app.core.Sunrise
import com.phasewake.app.core.Transient
import com.phasewake.app.core.WakePlan
import com.phasewake.app.core.WakeReason
import com.phasewake.app.core.WakeTask
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.RingMessage
import com.phasewake.app.wear.schedule.AlarmScheduler
import com.phasewake.app.wear.schedule.DirectBoot
import com.phasewake.app.wear.ui.RingActivity
import com.phasewake.app.wear.wake.WakeService.Companion.RING_TIMEOUT_MS
import com.phasewake.app.wear.wake.WakeService.Companion.TAG
import com.phasewake.app.wear.wake.WakeService.Companion.WAKE_LOCK_MARGIN_MS
import com.phasewake.app.wear.wake.WakeService.Companion.measuringPlan
import com.phasewake.app.wear.wake.WakeService.Night
import com.phasewake.app.wear.wake.WakeService.Phase
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.launch

// Wecken: Klingeln, Weckaufgabe am Handy, Schlummern, Beenden.

internal fun WakeService.ring() {
    val n = night ?: return
    if (n.phase == Phase.RINGING) return
    handler.removeCallbacks(deadline)
    handler.removeCallbacks(snoozeEnd)
    n.sensors?.stop()
    n.phase = Phase.RINGING
    measuringPlan = null
    // Der Weckbildschirm übernimmt das Licht.
    WatchSunrise.stop(this)
    val now = Instant.now()
    if (n.firstRing == null) n.firstRing = now
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

    app.diag.log(DiagKind.RING, "watch ${n.reason ?: WakeReason.DEADLINE}")
    // Immer melden: Das Handy entscheidet selbst (Zweitwecker, Sonnenaufgang, Unterdrückung der Sicherung).
    sendRing(n)
}

internal fun WakeService.ringingNotification(n: Night) =
    Notifications.ringing(this, reasonText(n.reason ?: WakeReason.DEADLINE), snoozesLeft(n))

/** Verbleibende Schlummer-Runden; beim Probewecker keine (er prüft genau einen Weckvorgang). */
internal fun snoozesLeft(n: Night): Int = if (Transient.allowsSnooze(n.plan.alarmId)) Snooze.MAX_COUNT - n.snoozes else 0

/** Meldet dem Handy «klingeln» mit Zielzeit, Sendezeit und verbleibenden Schlummer-Runden. */
internal fun WakeService.sendRing(n: Night) {
    val message = RingMessage(n.plan.alarmId, n.plan.target.toEpochMilli(), System.currentTimeMillis(), snoozesLeft(n))
    lifecycleScope.launch { app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_RING, message.encode()) }
}

/**
 * «stop» ans Handy. Beim Schlummern mit [snoozeUntil]: Das Handy stellt dann eine eigene Sicherung
 * kurz nach dem Schlummer-Ende, falls die Uhr nicht wieder klingelt. Ohne das Feld ist die Nacht beendet.
 */
internal fun WakeService.stopMessage(plan: WakePlan, snoozeUntil: Instant? = null) = RingMessage(
    alarmId = plan.alarmId,
    target = plan.target.toEpochMilli(),
    sentAt = System.currentTimeMillis(),
    snoozeUntil = snoozeUntil?.toEpochMilli(),
).encode()

internal fun WakeService.watchSound(n: Night) = n.spec?.watchSound ?: n.lockedSound

internal fun WakeService.label(n: Night) = n.spec?.label ?: n.lockedLabel

/** Hält den Stand der Nacht fest, damit ein Neustart des Dienstes ihn nicht verliert (vor dem Entsperren nicht möglich). */
private fun WakeService.saveProgress(n: Night) {
    if (!DirectBoot.isUnlocked(this)) return
    val firstRing = n.firstRing
    val reason = n.reason ?: WakeReason.DEADLINE
    val snoozes = n.snoozes
    app.appScope.launch { app.nightProgress.save(n.plan, firstRing, reason, snoozes) }
}

internal fun WakeService.publishRinging(n: Night) {
    val since = n.firstRing ?: Instant.now()
    // Vor dem Entsperren (kein Wecker lesbar) ohne Licht.
    val sunrise = n.spec?.watchSunrise ?: Sunrise.OFF
    // Ab Fenster: weiter, wo das Licht beim ersten Klingeln stand. Nach dem Schlummern gleich voll hell (nie zurück).
    val lightFrom = when {
        sunrise == Sunrise.OFF -> 0f
        n.snoozes > 0 -> 1f
        sunrise == Sunrise.WINDOW -> Sunrise.progress(n.plan.windowStart, n.plan.target, since)
        else -> 0f
    }
    app.ringState.publish(
        RingUi.Ringing(label(n), n.reason ?: WakeReason.DEADLINE, since, snoozesLeft(n), n.taskOnPhone, sunrise, lightFrom),
    )
}

/**
 * Weckaufgabe ([WakeTask.MATH]) zu Beginn jeder Klingel-Runde: Zuerst gilt Halten. Erst wenn das Handy
 * innert [DataLayer.TASK_CONFIRM_MS] bestätigt, dass es die Aufgabe zeigt ([onTaskShown]), zeigt die Uhr
 * «Aufgabe am Handy lösen». Bleibt die Bestätigung danach länger als [DataLayer.TASK_SHOWN_VALID_MS] aus
 * oder meldet das Handy das Ende ([DataLayer.PATH_TASK_GONE]), gilt wieder Halten. Nie ein Wecker, der
 * sich nicht beenden lässt; als Notausgang beendet langes Halten (5 s) immer.
 */
internal fun WakeService.checkTaskOnPhone(n: Night) {
    val spec = n.spec
    n.taskWanted = !n.locked && spec != null && spec.wakeTask == WakeTask.MATH && spec.phone != PhoneAlert.OFF
    n.taskOnPhone = false
    n.taskAcceptUntil = if (n.taskWanted) SystemClock.elapsedRealtime() + DataLayer.TASK_CONFIRM_MS else 0L
    handler.removeCallbacks(taskExpired)
}

/** Bestätigung des Handys: Aufgabe ist zu sehen. Eine verspätete erste Bestätigung ändert nichts mehr. */
internal fun WakeService.onTaskShown(target: Long?) {
    val n = night ?: return
    if (n.phase != Phase.RINGING || !n.taskWanted || !isSameTarget(target)) return
    if (!n.taskOnPhone) {
        if (SystemClock.elapsedRealtime() > n.taskAcceptUntil) return
        Log.i(TAG, "Handy zeigt die Weckaufgabe")
        n.taskOnPhone = true
        publishRinging(n)
    }
    handler.removeCallbacks(taskExpired)
    handler.postDelayed(taskExpired, DataLayer.TASK_SHOWN_VALID_MS)
}

/** Zurück zu Halten, für den Rest dieser Klingel-Runde. */
internal fun WakeService.taskFallback(why: String) {
    val n = night ?: return
    handler.removeCallbacks(taskExpired)
    if (!n.taskWanted && !n.taskOnPhone) return
    Log.i(TAG, "$why: Beenden durch Halten")
    n.taskWanted = false
    val wasShown = n.taskOnPhone
    n.taskOnPhone = false
    if (wasShown && n.phase == Phase.RINGING) publishRinging(n)
}

/**
 * [notifyPhone]: Hat der Nutzer geschlummert, schweigt auch das Handy; beim Auto-Schlummern klingelt es weiter.
 * [fromPhone]: Am Handy geschlummert. Hat die Uhr keine Runde mehr, klingelt sie weiter und bittet
 * das Handy mit «klingeln», ebenfalls weiterzuklingeln.
 */
internal fun WakeService.snooze(notifyPhone: Boolean, fromPhone: Boolean = false) {
    val n = night ?: return
    if (n.phase != Phase.RINGING) return
    if (snoozesLeft(n) <= 0) {
        if (fromPhone) sendRing(n)
        return
    }
    stopRinging()
    app.diag.log(DiagKind.SNOOZE, if (fromPhone) "from phone" else if (notifyPhone) "on watch" else "timeout")
    n.snoozes++
    n.phase = Phase.SNOOZED
    // Beim Schlummern gilt auf der Uhr immer Halten: Das Handy-Klingeln mit der Aufgabe ist dann vorbei.
    // Beim nächsten Klingeln beginnt die Bestätigung von vorn ([checkTaskOnPhone]).
    handler.removeCallbacks(taskExpired)
    n.taskOnPhone = false
    val until = Snooze.nextRing(Instant.now())
    app.ringState.publish(RingUi.Snoozed(label(n), until, Snooze.MAX_COUNT - n.snoozes))
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
internal fun WakeService.dismiss(notifyPhone: Boolean) {
    if (night == null) return
    stopRinging()
    finishNight(notifyPhone)
}

/** Niemand reagiert: schlummern, solange es geht, sonst beenden (das Handy klingelt weiter). */
internal fun WakeService.onRingTimeout() {
    val n = night ?: return
    if (n.phase != Phase.RINGING) return
    // Das Handy klingelt als Sicherung weiter: niemand hat reagiert.
    if (snoozesLeft(n) > 0) snooze(notifyPhone = false) else dismiss(notifyPhone = false)
}

/** Sicherheitsnetz: Die Nacht läuft weit über die Zielzeit hinaus. Beenden statt endlos messen oder klingeln. */
internal fun WakeService.onHardLimit() {
    if (night == null) return
    Log.w(TAG, "Zeitlimit der Nacht erreicht, beende")
    dismiss(notifyPhone = false)
}

private fun WakeService.stopRinging() {
    handler.removeCallbacks(ringTimeout)
    escalation.stop()
}
