package com.phasewake.app.sync

import com.phasewake.app.phoneApp
import com.phasewake.app.ring.PhoneRingService
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.WearSync
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Empfängt von der Uhr: geänderte Wecker, aufgezeichnete Nächte, Weck- und Stopp-Meldungen. */
class PhoneListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val app = phoneApp
        val changed = dataEvents.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem }
        // Der Dienst läuft auf einem eigenen Hintergrundthread; kurzes Blockieren ist hier vorgesehen.
        runBlocking {
            changed.filter { it.uri.path == DataLayer.PATH_ALARMS }
                .mapNotNull { WearSync.readAlarms(DataMapItem.fromDataItem(it).dataMap) }
                .maxByOrNull { it.updatedAt }
                ?.let { if (app.alarms.applyRemote(it)) app.backup.reschedule() }
            changed.filter { it.uri.path.orEmpty().startsWith(DataLayer.PATH_NIGHT_PREFIX) }.forEach { item ->
                val night = WearSync.readNight(DataMapItem.fromDataItem(item).dataMap) ?: return@forEach
                app.nights.add(night)
                // Die Uhr hat geweckt: Sicherung dieser Nacht nicht mehr nötig (falls «ring» verloren ging).
                app.backup.watchHandled(night.alarmId, night.target)
                // Datensparsamkeit: lokal gespeichert, im Data Layer nicht mehr nötig.
                app.sync.delete(item.uri)
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            DataLayer.PATH_RING -> PhoneRingService.ring(this, event.data.decodeToString(), fromWatch = true)
            DataLayer.PATH_STOP -> {
                PhoneRingService.command(this, PhoneRingService.ACTION_STOP_SILENT)
                phoneApp.appScope.launch { phoneApp.backup.watchHandled(null) }
            }
        }
    }
}
