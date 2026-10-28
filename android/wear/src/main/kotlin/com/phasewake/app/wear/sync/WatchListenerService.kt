package com.phasewake.app.wear.sync

import com.phasewake.app.shared.DataLayer
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

/** Empfängt Wecker vom Handy und den Stopp-Befehl, wenn am Handy abgestellt wurde. */
class WatchListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val changed = dataEvents.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem }
        val remote = changed
            .filter { it.uri.path == DataLayer.PATH_ALARMS }
            .mapNotNull { WearSync.readAlarms(DataMapItem.fromDataItem(it).dataMap) }
            .maxByOrNull { it.updatedAt }
        val clear = changed.any { it.uri.path == DataLayer.PATH_CLEAR_HISTORY }
        val app = wearApp
        // Der Dienst läuft auf einem eigenen Hintergrundthread; kurzes Blockieren ist hier vorgesehen.
        runBlocking {
            if (clear) app.nights.clear()
            if (remote != null && app.alarms.applyRemote(remote)) app.scheduler.reschedule()
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
        }
    }
}
