package com.phasewake.app.wear.sync

import com.phasewake.app.core.DiagKind
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.wear.diag.WatchDiag
import com.phasewake.app.shared.RingMessage
import com.phasewake.app.shared.WearSync
import com.phasewake.app.wear.schedule.DirectBoot
import com.phasewake.app.wear.wake.WakeService
import com.phasewake.app.wear.wearApp
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.time.Instant
import kotlinx.coroutines.runBlocking

/** Empfängt Wecker vom Handy, den Stopp-Befehl, wenn am Handy abgestellt wurde, und die Meldungen zur Weckaufgabe. */
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
        val app = wearApp
        // Der Dienst läuft auf einem eigenen Hintergrundthread; kurzes Blockieren ist hier vorgesehen.
        runBlocking {
            val newHolidays = holidays?.let { (list, at) -> app.holidays.set(list, at, onlyIfNewer = true) } == true
            if (newHolidays) app.diag.log(DiagKind.SYNC, "holidays ${holidays.first.region} ${holidays.first.dates.size}")
            // Zeitpunkt merken, damit [PhoneCatchUp] dasselbe Löschen später nicht noch einmal anwendet.
            // Ältere Handys ohne Zeitpunkt: wie bisher alles löschen.
            when {
                clearedAt == null -> Unit
                clearedAt > 0 -> PhoneCatchUp.applyClear(app, clearedAt)
                else -> app.nights.clear()
            }
            val newAlarms = remote != null && app.alarms.applyRemote(remote)
            if (newAlarms) app.diag.log(DiagKind.ALARMS_RECEIVED, "from phone ${remote.alarms.size}")
            if (newAlarms || newHolidays) app.scheduler.reschedule()
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
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
}
