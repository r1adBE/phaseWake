package com.phasewake.app.wear.sync

import android.util.Log
import com.phasewake.app.core.CodecException
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Transient
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

    /** Plan vom Handy übernehmen; einen Probewecker bestätigt die Uhr gleich ([DataLayer.PATH_TEST_ACK]). */
    suspend fun received(app: PhaseWakeWear, path: String, payload: ByteArray?) {
        val plan = try {
            Transient.decode(DataLayer.text(payload))
        } catch (e: CodecException) {
            Log.w(TAG, "Plan vom Handy unlesbar", e)
            return
        }
        val expected = if (path == DataLayer.PATH_TEST) TransientKind.TEST else TransientKind.NAP
        if (plan.kind != expected || !Transient.isActive(plan, Instant.now())) return
        begin(app, plan)
        if (plan.kind == TransientKind.TEST) {
            app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_TEST_ACK, plan.spec.id.encodeToByteArray())
        }
    }

    /** Powernap auf der Uhr starten (Weckart wie der nächste Wecker) und dem Handy für die Sicherung melden. */
    suspend fun startNap(app: PhaseWakeWear, minutes: Int) {
        val now = Instant.now()
        val alarms = app.alarms.current().alarms
        val base = app.scheduler.nextPlan()?.let { plan -> alarms.firstOrNull { it.id == plan.alarmId } } ?: alarms.firstOrNull()
        val plan = Transient.nap(base, now, ZoneId.systemDefault(), minutes, UUID.randomUUID().toString())
        begin(app, plan)
        app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_NAP, Transient.encode(plan).encodeToByteArray())
    }

    /** Abbruch (vom Handy oder auf der Uhr): Plan weg, laufendes Wecken beenden, neu planen. */
    suspend fun cancelled(app: PhaseWakeWear, alarmId: String, fromWatch: Boolean = false) {
        if (!app.transient.clear(alarmId)) return
        app.diag.log(DiagKind.OTHER, "transient cancelled")
        if (WakeService.activePlan?.alarmId == alarmId) {
            WakeService.command(app, WakeService.ACTION_DISMISS, remoteAlarmId = alarmId)
        }
        if (fromWatch) app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_TRANSIENT_CANCEL, alarmId.encodeToByteArray())
        app.scheduler.reschedule()
    }

    private suspend fun begin(app: PhaseWakeWear, plan: TransientPlan) {
        app.transient.set(plan)
        app.diag.log(if (plan.kind == TransientKind.TEST) DiagKind.TEST else DiagKind.NAP, "planned ${plan.plan.windowStart} – ${plan.plan.target}")
        app.scheduler.reschedule()
    }
}
