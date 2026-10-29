package com.phasewake.app.sync

import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.TestStep
import com.phasewake.app.phoneApp
import com.phasewake.app.transient.Transients
import com.phasewake.app.diag.WatchReport
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
            if (applied) app.diag.log(DiagKind.ALARMS_RECEIVED, "from watch")
            val status = changed.any { it.uri.path == DataLayer.PATH_WATCH_STATUS }
            if (applied || status) app.backup.reschedule()
            changed.filter { it.uri.path.orEmpty().startsWith(DataLayer.PATH_NIGHT_PREFIX) }.forEach { item ->
                val night = WearSync.readNight(DataMapItem.fromDataItem(item).dataMap) ?: return@forEach
                app.nights.add(night)
                // Die Uhr hat geweckt und die Nacht beendet: Sicherungen dieser Nacht nicht mehr nötig
                // (falls «ring» oder das letzte «stop» verloren ging).
                app.backup.cancelSnoozeSafety(night.alarmId)
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
            // Probewecker auf der Uhr angekommen.
            DataLayer.PATH_TEST_ACK -> runBlocking {
                app.testRuns.record(DataLayer.text(event.data), TestStep.RECEIVED, Instant.now())
                app.diag.log(DiagKind.TEST, "watch received")
            }
            DataLayer.PATH_NAP -> runBlocking { Transients.received(app, event.data) }
            DataLayer.PATH_TRANSIENT_CANCEL -> runBlocking { Transients.cancelled(app, event.data) }
            DataLayer.PATH_DIAG_LOG -> WatchReport.received(event.data)
            DataLayer.PATH_STOP -> {
                val message = RingMessage.decode(event.data)
                // Restzahl vor dem Verstummen: für die Sicherung, falls die Uhr nach dem Schlummern ausfällt.
                val left = (PhoneRingService.ringState.value as? PhoneRingService.State.Ringing)?.snoozesLeft
                PhoneRingService.command(this, PhoneRingService.ACTION_STOP_SILENT)
                // Die Uhr schickt «stop» auch beim Schlummern (mit Schlummer-Ende); einen Einmal-Wecker schaltet sie selbst aus.
                runBlocking { app.backup.watchStopped(message, left) }
            }
        }
    }
}
