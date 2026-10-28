package ch.r1ad.phasewake.wear.sync

import ch.r1ad.phasewake.shared.DataLayer
import ch.r1ad.phasewake.shared.WearSync
import ch.r1ad.phasewake.wear.wake.WakeService
import ch.r1ad.phasewake.wear.wearApp
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/** Empfängt Wecker vom Handy und den Stopp-Befehl, wenn am Handy abgestellt wurde. */
class WatchListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val remote = dataEvents
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == DataLayer.PATH_ALARMS }
            .mapNotNull { WearSync.readAlarms(DataMapItem.fromDataItem(it.dataItem).dataMap) }
            .maxByOrNull { it.updatedAt }
            ?: return
        val app = wearApp
        // Der Dienst läuft auf einem eigenen Hintergrundthread; kurzes Blockieren ist hier vorgesehen.
        runBlocking {
            if (app.alarms.applyRemote(remote)) app.scheduler.reschedule()
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            DataLayer.PATH_STOP -> WakeService.command(this, WakeService.ACTION_DISMISS)
            DataLayer.PATH_SNOOZE -> WakeService.command(this, WakeService.ACTION_SNOOZE)
        }
    }
}
