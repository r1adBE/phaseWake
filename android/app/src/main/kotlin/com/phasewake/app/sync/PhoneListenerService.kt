package com.phasewake.app.sync

import com.phasewake.app.phoneApp
import com.phasewake.app.ring.PhoneRingService
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.RingMessage
import com.phasewake.app.shared.WearSync
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.time.Instant
import kotlinx.coroutines.runBlocking

/** Empfängt von der Uhr: geänderte Wecker, aufgezeichnete Nächte, Status, Weck- und Stopp-Meldungen. */
class PhoneListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val app = phoneApp
        val changed = dataEvents.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem }
        // Der Dienst läuft auf einem eigenen Hintergrundthread; kurzes Blockieren ist hier vorgesehen.
        runBlocking {
            val applied = changed.filter { it.uri.path == DataLayer.PATH_ALARMS }
                .mapNotNull { WearSync.readAlarms(DataMapItem.fromDataItem(it).dataMap) }
                .maxByOrNull { it.updatedAt }
                ?.let { app.alarms.applyRemote(it) } == true
            // Eine (neu gekoppelte) Uhr meldet sich: Der Zweitwecker wartet jetzt die Nachfrist ab.
            val status = changed.any { it.uri.path == DataLayer.PATH_WATCH_STATUS }
            if (applied || status) app.backup.reschedule()
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
        val app = phoneApp
        when (event.path) {
            // Nicht direkt den Weckdienst starten: aus dem Hintergrund ab Android 12 nicht erlaubt.
            DataLayer.PATH_RING -> {
                val message = RingMessage.decode(event.data)
                // Blockierend, damit der Weiterleitungs-Alarm steht, bevor der Dienst endet.
                runBlocking { app.backup.watchRang(message) }
            }
            DataLayer.PATH_STOP -> {
                val message = RingMessage.decode(event.data)
                PhoneRingService.command(this, PhoneRingService.ACTION_STOP_SILENT)
                // Die Uhr schickt «stop» auch beim Schlummern; einen Einmal-Wecker schaltet sie selbst aus.
                runBlocking { app.backup.watchHandled(message.alarmId, message.target?.let(Instant::ofEpochMilli)) }
            }
        }
    }
}
