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
 * Wecker und Einstellungen kommen über die Warteschlange ([com.phasewake.app.shared.Outbox]), bestätigt nach dem
 * Übernehmen ([WearSync.receive]). Als Datenpaket ([onDataChanged]) nur «Verlauf löschen» und der Abbruch eines
 * Probeweckers oder Powernaps.
 */
class WatchListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val changed = dataEvents.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem }
        val clearedAt = changed
            .filter { it.uri.path == DataLayer.PATH_CLEAR_HISTORY }
            .maxOfOrNull { DataMapItem.fromDataItem(it).dataMap.getLong(DataLayer.KEY_UPDATED_AT) }
        // Abbruch am Handy, während die Uhr nicht erreichbar war: nachgeliefert als Paket.
        val cancelled = changed
            .filter { it.uri.path == DataLayer.PATH_TRANSIENT_CANCELLED }
            .mapNotNull { WearSync.readTransientCancel(DataMapItem.fromDataItem(it).dataMap) }
        val app = wearApp
        // Der Dienst läuft auf einem eigenen Hintergrundthread; kurzes Blockieren ist hier vorgesehen.
        runBlocking {
            cancelled.forEach { TransientInbox.cancelled(app, it) }
            // Zeitpunkt merken, damit [PhoneCatchUp] dasselbe Löschen später nicht noch einmal anwendet. Ohne
            // Zeitpunkt (veraltete Handy-App): nichts löschen, kein Rückfall.
            if (clearedAt != null && clearedAt > 0) PhoneCatchUp.applyClear(app, clearedAt)
        }
    }

    /**
     * Eintrag der Warteschlange des Handys ([path], Text [body]): übernehmen (nur Neueres), bei einer Änderung neu
     * planen. Unlesbares wird übergangen (und bestätigt).
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
        // «Nacht aufzeichnen» vom Handy: Start oder Stopp (Inhalt nur beim Start mit Diagnose am Handy).
        RecordSync.commandOf(event.path)?.let {
            RecordControl.fromPhone(this, it, RecordSync.continuousOf(event.data))
            return
        }
        // «snooze» trägt keinen Inhalt (gilt der laufenden Nacht); «stop» und die Meldungen zur Weckaufgabe tragen die
        // Sendezeit. Ohne sie stammen sie von einer veralteten Handy-App: verwerfen, kein Rückfall.
        val message = RingMessage.decode(event.data)
        val alarmId = message.alarmId
        if (event.path in NEEDS_SEND_TIME && !message.isCurrent()) return
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

        /** Meldungen des Handys, die eine Sendezeit tragen müssen ([RingMessage.isCurrent]). */
        val NEEDS_SEND_TIME = setOf(DataLayer.PATH_STOP, DataLayer.PATH_TASK_SHOWN, DataLayer.PATH_TASK_GONE)
    }
}
