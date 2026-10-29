package com.phasewake.app.transient

import android.util.Log
import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.CodecException
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.TestRun
import com.phasewake.app.core.Transient
import com.phasewake.app.core.TransientKind
import com.phasewake.app.core.TransientPlan
import com.phasewake.app.shared.DataLayer
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Probewecker und Powernap am Handy: starten, abbrechen, Meldungen der Uhr übernehmen. Die Uhr führt beide
 * wie eine Nacht; das Handy legt dafür eine Sicherung (Zweitwecker), aber nie einen Eintrag im Verlauf an.
 */
object Transients {
    private const val TAG = "Transients"

    /**
     * «In 2 Minuten testen»: Weckart des nächsten echten Weckers (ohne Wecker die Vorgaben). Ersetzt einen
     * laufenden Probewecker oder Powernap.
     */
    suspend fun startTest(app: PhaseWakeApp): TransientPlan {
        val now = Instant.now()
        val plan = Transient.test(nextAlarm(app, now), now, ZoneId.systemDefault(), UUID.randomUUID().toString())
        app.testRuns.start(TestRun(plan.spec.id, plan.plan.target, plan.spec.phone))
        begin(app, plan, DataLayer.PATH_TEST)
        return plan
    }

    /** Powernap ab jetzt mit [minutes] Dauer; die Dauer bleibt als Vorschlag für das nächste Mal. */
    suspend fun startNap(app: PhaseWakeApp, minutes: Int): TransientPlan {
        val now = Instant.now()
        app.settings.setNapMinutes(minutes)
        val plan = Transient.nap(nextAlarm(app, now), now, ZoneId.systemDefault(), minutes, UUID.randomUUID().toString())
        begin(app, plan, DataLayer.PATH_NAP)
        return plan
    }

    /** Bricht den Plan [alarmId] ab, hier und auf der Uhr. */
    suspend fun cancel(app: PhaseWakeApp, alarmId: String) {
        if (!app.transient.clear(alarmId)) return
        if (Transient.kindOf(alarmId) == TransientKind.TEST) app.testRuns.clear()
        app.diag.log(DiagKind.OTHER, "transient cancelled")
        app.sync.message(DataLayer.CAPABILITY_WATCH, DataLayer.PATH_TRANSIENT_CANCEL, alarmId.encodeToByteArray())
        app.backup.reschedule()
    }

    /** Die Uhr hat einen Powernap gestartet ([DataLayer.PATH_NAP]): für die Sicherung übernehmen. */
    suspend fun received(app: PhaseWakeApp, payload: ByteArray?) {
        val plan = try {
            Transient.decode(DataLayer.text(payload))
        } catch (e: CodecException) {
            Log.w(TAG, "Plan der Uhr unlesbar", e)
            return
        }
        if (plan.kind != TransientKind.NAP || !Transient.isActive(plan, Instant.now())) return
        app.transient.set(plan)
        app.diag.log(DiagKind.NAP, "from watch until ${plan.plan.target}")
        app.backup.reschedule()
    }

    /** Die Uhr hat abgebrochen ([DataLayer.PATH_TRANSIENT_CANCEL]). */
    suspend fun cancelled(app: PhaseWakeApp, payload: ByteArray?) {
        val id = DataLayer.text(payload)
        if (app.transient.clear(id)) app.backup.reschedule()
    }

    /** Am Handy beendet (Stopp beim Handy-Klingeln): ein Probewecker oder Powernap ist damit vorbei. */
    fun endedHere(app: PhaseWakeApp, alarmId: String?) {
        if (Transient.kindOf(alarmId) == null || alarmId == null) return
        app.appScope.launch { app.transient.clear(alarmId) }
    }

    private suspend fun begin(app: PhaseWakeApp, plan: TransientPlan, path: String) {
        app.transient.set(plan)
        app.diag.log(if (plan.kind == TransientKind.TEST) DiagKind.TEST else DiagKind.NAP, "start until ${plan.plan.target}")
        app.sync.message(DataLayer.CAPABILITY_WATCH, path, Transient.encode(plan).encodeToByteArray())
        app.backup.reschedule()
    }

    /** Der nächste echte Wecker (für die Weckart), oder `null`. */
    private suspend fun nextAlarm(app: PhaseWakeApp, now: Instant) = app.alarms.current().alarms.let { list ->
        AlarmSchedule.earliestPlan(list, now, ZoneId.systemDefault(), app.holidays.store.current())?.let { plan -> list.firstOrNull { it.id == plan.alarmId } }
            ?: list.firstOrNull()
    }
}
