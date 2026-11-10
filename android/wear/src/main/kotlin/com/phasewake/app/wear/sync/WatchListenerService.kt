package com.phasewake.app.wear.sync

import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.SleepGoal
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.LearningSnapshot
import com.phasewake.app.shared.QueueMessages
import com.phasewake.app.shared.RecordSync
import com.phasewake.app.shared.RingMessage
import com.phasewake.app.shared.WearSync
import com.phasewake.app.wear.diag.WatchDiag
import com.phasewake.app.wear.record.RecordControl
import com.phasewake.app.wear.schedule.DirectBoot
import com.phasewake.app.wear.wake.WakeService
import com.phasewake.app.wear.wearApp
import java.time.Instant
import kotlinx.coroutines.runBlocking

/**
 * Empfängt Wecker, Feiertage, Schlafziel und Lernphase vom Handy, den Stopp-Befehl, wenn am Handy abgestellt wurde, die Meldungen zur
 * Weckaufgabe und Start und Stopp von «Nacht aufzeichnen».
 *
 * Ein neues Handy schickt Wecker und Einstellungen über die Warteschlange ([com.phasewake.app.shared.Outbox]), bestätigt
 * nach dem Übernehmen ([WearSync.receive]); Pakete eines älteren Handys ([onDataChanged]) gelten wie bisher.
 */
class WatchListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val changed = dataEvents.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem }
        val remote = changed
            .filter { it.uri.path == DataLayer.PATH_ALARMS }
            .mapNotNull { WearSync.readAlarms(DataMapItem.fromDataItem(it).dataMap) }
            .maxByOrNull { it.updatedAt }
        val clearedAt = changed
            .filter { it.uri.path == DataLayer.PATH_CLEAR_HISTORY }
            .maxOfOrNull { DataMapItem.fromDataItem(it).dataMap.getLong(DataLayer.KEY_UPDATED_AT) }
        val holidays = changed
            .filter { it.uri.path == DataLayer.PATH_HOLIDAYS }
            .map { WearSync.readHolidays(DataMapItem.fromDataItem(it).dataMap) }
            .maxByOrNull { it.second }
        val sleepGoal = changed
            .filter { it.uri.path == DataLayer.PATH_SLEEP_GOAL }
            .map { WearSync.readSleepGoal(DataMapItem.fromDataItem(it).dataMap) }
            .maxByOrNull { it.second }
        val autoOnset = changed
            .filter { it.uri.path == DataLayer.PATH_AUTO_ONSET }
            .map { WearSync.readAutoOnset(DataMapItem.fromDataItem(it).dataMap) }
            .maxByOrNull { it.second }
        val learning = changed
            .filter { it.uri.path == DataLayer.PATH_LEARNING }
            .map { LearningSnapshot.read(DataMapItem.fromDataItem(it).dataMap) }
            .maxByOrNull { it.updatedAt }
        // Abbruch am Handy, während die Uhr nicht erreichbar war: nachgeliefert als Paket.
        val cancelled = changed
            .filter { it.uri.path == DataLayer.PATH_TRANSIENT_CANCELLED }
            .mapNotNull { WearSync.readTransientCancel(DataMapItem.fromDataItem(it).dataMap) }
        val app = wearApp
        // Der Dienst läuft auf einem eigenen Hintergrundthread; kurzes Blockieren ist hier vorgesehen.
        runBlocking {
            cancelled.forEach { TransientInbox.cancelled(app, it) }
            val newHolidays = holidays?.let { (list, at) -> app.holidays.set(list, at, onlyIfNewer = true) } == true
            if (newHolidays) app.diag.log(DiagKind.SYNC, "holidays ${holidays.first.region} ${holidays.first.dates.size}")
            // Einschlafpunkt und Beginn der automatischen Einschlaf-Messung: neu planen.
            val newGoal = sleepGoal != null && app.sleepGoal.setIfNewer(sleepGoal.first, sleepGoal.second)
            if (newGoal) app.diag.log(DiagKind.SYNC, "sleep goal ${sleepGoal.first}")
            // Schalter der Einschlaf-Messung: Alarm der Messung stellen oder aufheben.
            val newOnset = autoOnset != null && app.autoOnset.setIfNewer(autoOnset.first, autoOnset.second)
            if (newOnset) app.diag.log(DiagKind.SYNC, "auto onset ${autoOnset.first}")
            // Lernphase: gilt ab der nächsten Einschlaf-Messung bzw. dem nächsten Weckfenster, kein Neuplanen nötig.
            if (learning != null && app.learning.setIfNewer(learning)) {
                app.diag.log(DiagKind.SYNC, "learning ${learning.diagLine()}")
                RecordControl.onLearningChanged(this@WatchListenerService, learning)
            }
            // Zeitpunkt merken, damit [PhoneCatchUp] dasselbe Löschen später nicht noch einmal anwendet.
            // Ältere Handys ohne Zeitpunkt: wie bisher alles löschen.
            when {
                clearedAt == null -> Unit
                clearedAt > 0 -> PhoneCatchUp.applyClear(app, clearedAt)
                else -> {
                    app.nights.clear()
                    app.recordings.clear()
                }
            }
            val newAlarms = remote != null && app.alarms.applyRemote(remote)
            if (newAlarms) app.diag.log(DiagKind.ALARMS_RECEIVED, "from phone ${remote.alarms.size}")
            if (newAlarms || newHolidays || newGoal || newOnset) app.scheduler.reschedule()
        }
    }

    /**
     * Eintrag der Warteschlange des Handys ([path], Text [body]): wie das entsprechende frühere Paket übernehmen (nur
     * Neueres), bei einer Änderung neu planen. Unlesbares wird übergangen (und bestätigt).
     */
    private suspend fun handleQueued(path: String, body: String) {
        val app = wearApp
        var replan = false
        when (path) {
            DataLayer.PATH_MSG_ALARMS -> QueueMessages.decodeAlarms(body)?.let { remote ->
                if (app.alarms.applyRemote(remote)) {
                    app.diag.log(DiagKind.ALARMS_RECEIVED, "from phone ${remote.alarms.size}")
                    replan = true
                }
            }
            DataLayer.PATH_MSG_HOLIDAYS -> QueueMessages.decodeHolidays(body)?.let { (list, at) ->
                if (app.holidays.set(list, at, onlyIfNewer = true)) {
                    app.diag.log(DiagKind.SYNC, "holidays ${list.region} ${list.dates.size}")
                    replan = true
                }
            }
            DataLayer.PATH_MSG_SLEEP_GOAL -> QueueMessages.decodeStamped(body)?.let { (minutes, at) ->
                val goal = SleepGoal.clamp(minutes)
                if (app.sleepGoal.setIfNewer(goal, at)) {
                    app.diag.log(DiagKind.SYNC, "sleep goal $goal")
                    replan = true
                }
            }
            DataLayer.PATH_MSG_AUTO_ONSET -> QueueMessages.decodeStamped(body)?.let { (on, at) ->
                if (app.autoOnset.setIfNewer(on == 1, at)) {
                    app.diag.log(DiagKind.SYNC, "auto onset ${on == 1}")
                    replan = true
                }
            }
            // Lernphase: gilt ab der nächsten Einschlaf-Messung bzw. dem nächsten Weckfenster, kein Neuplanen nötig.
            DataLayer.PATH_MSG_LEARNING -> LearningSnapshot.decode(body)?.let { learning ->
                if (app.learning.setIfNewer(learning)) {
                    app.diag.log(DiagKind.SYNC, "learning ${learning.diagLine()}")
                    RecordControl.onLearningChanged(this, learning)
                }
            }
        }
        if (replan) app.scheduler.reschedule()
    }

    /** Das Handy ist (wieder) erreichbar: wartende Nächte und Aufzeichnungen senden. */
    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.nodes.isNotEmpty() && DirectBoot.isUnlocked(this)) runBlocking { wearApp.sync.flush() }
    }

    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        when {
            path == DataLayer.PATH_ACK -> {
                runBlocking { wearApp.sync.acked(event.data) }
                return
            }
            // Das Handy will den Stand der Uhr (frisch installiert, neu gestartet): Wecker und Aufzeichnungs-Stand einreihen.
            path == DataLayer.PATH_SYNC_REQUEST -> {
                if (DirectBoot.isUnlocked(this)) runBlocking { publishToPhone() }
                return
            }
            path.startsWith(QUEUE_PREFIX) -> {
                if (DirectBoot.isUnlocked(this)) runBlocking { wearApp.sync.receive(event.sourceNodeId, event.data) { handleQueued(path, it) } }
                return
            }
        }
        // «Nacht aufzeichnen» vom Handy: Start oder Stopp (ohne Inhalt).
        RecordSync.commandOf(event.path)?.let {
            RecordControl.fromPhone(this, it)
            return
        }
        // Inhalt nachsichtig lesen: «snooze» und ältere Handy-Versionen schicken keine Kennung, dann gilt der Befehl der laufenden Nacht.
        val message = RingMessage.decode(event.data)
        val alarmId = message.alarmId
        when (event.path) {
            DataLayer.PATH_STOP -> {
                val active = WakeService.activePlan
                if (alarmId != null && active?.alarmId != alarmId && DirectBoot.isUnlocked(this)) {
                    // Am Handy beendet, aber nicht die Nacht, die die Uhr gerade führt (z. B. der Zweitwecker
                    // eines zweiten Weckers, während die Uhr den ersten schlummert): nur diese Nacht als
                    // erledigt merken, damit die Uhr sie danach nicht nachholt. Die laufende bleibt.
                    runBlocking { wearApp.scheduler.markDoneFromPhone(alarmId, message.target?.let(Instant::ofEpochMilli)) }
                } else {
                    WakeService.command(this, WakeService.ACTION_DISMISS, remoteAlarmId = alarmId)
                }
            }
            // Hat die Uhr keine Schlummer-Runde mehr, antwortet der Dienst mit «klingeln».
            DataLayer.PATH_SNOOZE -> WakeService.command(this, WakeService.ACTION_PHONE_SNOOZE, remoteAlarmId = alarmId)
            // Weckaufgabe: Das Handy zeigt sie (wiederholt) oder hat sie ungelöst beendet. Nur an einen laufenden Dienst.
            DataLayer.PATH_TASK_SHOWN -> WakeService.command(this, WakeService.ACTION_TASK_SHOWN, remoteAlarmId = alarmId, remoteTarget = message.target)
            DataLayer.PATH_TEST, DataLayer.PATH_NAP -> runBlocking { TransientInbox.received(wearApp, event.path, event.data) }
            DataLayer.PATH_TRANSIENT_CANCEL -> runBlocking { TransientInbox.cancelled(wearApp, DataLayer.text(event.data)) }
            DataLayer.PATH_DIAG_REQUEST -> runBlocking { WatchDiag.sendReport(wearApp) }
            DataLayer.PATH_TASK_GONE -> WakeService.command(this, WakeService.ACTION_TASK_GONE, remoteAlarmId = alarmId, remoteTarget = message.target)
        }
    }

    /** Wecker und Stand von «Nacht aufzeichnen» ans Handy einreihen (Wunsch des Handys). */
    private suspend fun publishToPhone() {
        val app = wearApp
        app.sync.publishAlarms(app.alarms.current())
        app.recordSync.publishStatus(app.recordings.active()?.start)
    }

    private companion object {
        /** Gemeinsamer Anfang der Pfade der Warteschlange (`DataLayer.PATH_MSG_…`). */
        const val QUEUE_PREFIX = "/phasewake/m/"
    }
}
