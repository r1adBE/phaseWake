package com.phasewake.app.transient

import android.util.Log
import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.CodecException
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.TestRun
import com.phasewake.app.core.Transient
import com.phasewake.app.core.TransientConflict
import com.phasewake.app.core.TransientKind
import com.phasewake.app.core.TransientPlan
import com.phasewake.app.ring.PhoneRingService
import com.phasewake.app.ring.SnoozeSafety
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
     * wartenden Probewecker oder Powernap. Abgelehnt ([conflict]), solange ein Wecker läuft oder sein Fenster vorher
     * beginnt. @return `null`, wenn gestartet; sonst der Grund (Hinweis), nichts geändert.
     */
    suspend fun startTest(app: PhaseWakeApp): TransientConflict? {
        val now = Instant.now()
        val plan = Transient.test(nextAlarm(app, now), now, ZoneId.systemDefault(), UUID.randomUUID().toString())
        conflict(app, plan, now)?.let { return it }
        app.testRuns.start(TestRun(plan.spec.id, plan.plan.target, plan.spec.phone))
        begin(app, plan, DataLayer.PATH_TEST)
        return null
    }

    /** Powernap ab jetzt mit [minutes] Dauer; die Dauer bleibt als Vorschlag. Abgelehnt wie [startTest]. */
    suspend fun startNap(app: PhaseWakeApp, minutes: Int): TransientConflict? {
        val now = Instant.now()
        app.settings.setNapMinutes(minutes)
        val plan = Transient.nap(nextAlarm(app, now), now, ZoneId.systemDefault(), minutes, UUID.randomUUID().toString())
        conflict(app, plan, now)?.let { return it }
        begin(app, plan, DataLayer.PATH_NAP)
        return null
    }

    /**
     * Ob [plan] abgelehnt wird ([Transient.conflict]). Nächster Plan: der nächste echte Wecker ohne erledigte Nacht.
     * Eine Nacht läuft: Das Handy klingelt oder schlummert, die Sicherung nach dem Schlummern auf der Uhr steht,
     * oder ein vorübergehender Plan ist in seinem Fenster.
     */
    private suspend fun conflict(app: PhaseWakeApp, plan: TransientPlan, now: Instant): TransientConflict? {
        val zone = ZoneId.systemDefault()
        val next = AlarmSchedule.earliestPlan(app.alarms.current().alarms, now, zone, app.holidays.store.current(), app.backup.doneUntil())
        val running = app.transient.active()?.plan?.let { !now.isBefore(it.windowStart) && now.isBefore(it.target) } == true
        val night = PhoneRingService.ringState.value != PhoneRingService.State.Idle || SnoozeSafety.load(app) != null || running
        return Transient.conflict(plan, next, night, now)
    }

    /** Bricht den Plan [alarmId] ab, hier und auf der Uhr. */
    suspend fun cancel(app: PhaseWakeApp, alarmId: String) {
        if (!app.transient.clear(alarmId)) return
        if (Transient.kindOf(alarmId) == TransientKind.TEST) app.testRuns.clear()
        app.diag.log(DiagKind.OTHER, "transient cancelled")
        app.sync.publishTransientCancel(DataLayer.CAPABILITY_WATCH, alarmId)
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

    /** Die Uhr hat abgebrochen ([DataLayer.PATH_TRANSIENT_CANCEL] bzw. nachgeliefert als Paket). */
    suspend fun cancelled(app: PhaseWakeApp, payload: ByteArray?) = cancelled(app, DataLayer.text(payload))

    /** Abbruch des Plans [alarmId] durch die Uhr: nicht mehr anzeigen und nicht mehr sichern. */
    suspend fun cancelled(app: PhaseWakeApp, alarmId: String) {
        if (app.transient.clear(alarmId)) app.backup.reschedule()
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
