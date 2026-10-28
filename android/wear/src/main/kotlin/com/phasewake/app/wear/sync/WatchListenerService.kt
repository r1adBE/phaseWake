package com.phasewake.app.wear.sync

import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.RingMessage
import com.phasewake.app.shared.WearSync
import com.phasewake.app.wear.wake.WakeService
import com.phasewake.app.wear.wearApp
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
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
        // Inhalt nachsichtig lesen: Ältere Handy-Versionen schicken nichts, dann gilt der Befehl der laufenden Nacht.
        val alarmId = RingMessage.decode(event.data).alarmId
        when (event.path) {
            DataLayer.PATH_STOP -> WakeService.command(this, WakeService.ACTION_DISMISS, remoteAlarmId = alarmId)
            // Hat die Uhr keine Schlummer-Runde mehr, antwortet der Dienst mit «klingeln».
            DataLayer.PATH_SNOOZE -> WakeService.command(this, WakeService.ACTION_PHONE_SNOOZE, remoteAlarmId = alarmId)
        }
    }
}
