package com.phasewake.app.wear.sync

import android.util.Log
import com.phasewake.app.core.CodecException
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Transient
import com.phasewake.app.core.TransientConflict
import com.phasewake.app.core.TransientKind
import com.phasewake.app.core.TransientPlan
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.wear.PhaseWakeWear
import com.phasewake.app.wear.wake.WakeService
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Probewecker und Powernap auf der Uhr: vom Handy empfangen ([DataLayer.PATH_TEST], [DataLayer.PATH_NAP]),
 * auf der Uhr gestartet oder abgebrochen. Geplant werden sie wie ein Wecker ([com.phasewake.app.wear.schedule.AlarmScheduler]).
 */
object TransientInbox {
    private const val TAG = "TransientInbox"

    /**
     * Plan vom Handy übernehmen; einen Probewecker bestätigt die Uhr gleich ([DataLayer.PATH_TEST_ACK]). Kollidiert er
     * mit einem Wecker ([conflict]), verwirft die Uhr ihn ohne Bestätigung: Die Messung des echten Weckers läuft weiter.
     */
    suspend fun received(app: PhaseWakeWear, path: String, payload: ByteArray?) {
        val plan = try {
            Transient.decode(DataLayer.text(payload))
        } catch (e: CodecException) {
            Log.w(TAG, "Plan vom Handy unlesbar", e)
            return
        }
        val expected = if (path == DataLayer.PATH_TEST) TransientKind.TEST else TransientKind.NAP
        if (plan.kind != expected || !Transient.isActive(plan, Instant.now())) return
        conflict(app, plan)?.let { reason ->
            app.diag.log(DiagKind.OTHER, "transient refused $reason")
            return
        }
        begin(app, plan)
        if (plan.kind == TransientKind.TEST) {
            app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_TEST_ACK, plan.spec.id.encodeToByteArray())
        }
    }

    /**
     * Powernap auf der Uhr starten (Weckart wie der nächste Wecker) und dem Handy für die Sicherung melden.
     * @return `null`, wenn er läuft; sonst der Grund der Ablehnung (Hinweis auf dem Bildschirm), nichts geändert.
     */
    suspend fun startNap(app: PhaseWakeWear, minutes: Int): TransientConflict? {
        val now = Instant.now()
        val alarms = app.alarms.current().alarms
        val base = app.scheduler.nextAlarmPlan()?.let { plan -> alarms.firstOrNull { it.id == plan.alarmId } } ?: alarms.firstOrNull()
        val plan = Transient.nap(base, now, ZoneId.systemDefault(), minutes, UUID.randomUUID().toString())
        conflict(app, plan)?.let { return it }
        begin(app, plan)
        app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_NAP, Transient.encode(plan).encodeToByteArray())
        return null
    }

    /**
     * Ob [plan] jetzt abgelehnt wird ([Transient.conflict]): Fenster des nächsten Weckers läuft oder wird überdeckt,
     * oder die Uhr misst bzw. weckt gerade (auch einen anderen vorübergehenden Plan).
     */
    private suspend fun conflict(app: PhaseWakeWear, plan: TransientPlan): TransientConflict? =
        Transient.conflict(plan, app.scheduler.nextAlarmPlan(), WakeService.activePlan != null, Instant.now())

    /** Abbruch (vom Handy oder auf der Uhr): Plan weg, laufendes Wecken beenden, neu planen. */
    suspend fun cancelled(app: PhaseWakeWear, alarmId: String, fromWatch: Boolean = false) {
        if (!app.transient.clear(alarmId)) return
        app.diag.log(DiagKind.OTHER, "transient cancelled")
        if (WakeService.activePlan?.alarmId == alarmId) {
            WakeService.command(app, WakeService.ACTION_DISMISS, remoteAlarmId = alarmId)
        }
        if (fromWatch) app.sync.publishTransientCancel(DataLayer.CAPABILITY_PHONE, alarmId)
        app.scheduler.reschedule()
    }

    private suspend fun begin(app: PhaseWakeWear, plan: TransientPlan) {
        app.transient.set(plan)
        app.diag.log(if (plan.kind == TransientKind.TEST) DiagKind.TEST else DiagKind.NAP, "planned ${plan.plan.windowStart} – ${plan.plan.target}")
        app.scheduler.reschedule()
    }
}
