package com.phasewake.app.sync

import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.LearnSkip
import com.phasewake.app.core.TestStep
import com.phasewake.app.phoneApp
import com.phasewake.app.transient.Transients
import com.phasewake.app.diag.WatchReport
import com.phasewake.app.ring.PhoneRingService
import com.phasewake.app.ring.cancelSnoozeOf
import com.phasewake.app.ring.onsetShiftReceived
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.OnsetShift
import com.phasewake.app.shared.QueueMessages
import com.phasewake.app.shared.RecordSync
import com.phasewake.app.shared.RingMessage
import com.phasewake.app.shared.WearSync
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.phasewake.app.core.Codec
import com.phasewake.app.core.NightRecord
import com.phasewake.app.shared.NightRecording
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Empfängt von der Uhr: geänderte Wecker, aufgezeichnete Nächte und Aufzeichnungen, Status, Weck- und Stopp-Meldungen.
 *
 * Inhalte mit persönlichen Daten kommen über die Warteschlange ([com.phasewake.app.shared.Outbox]): als Nachricht (grosse
 * Aufzeichnungen über einen Kanal), bestätigt nach dem Speichern ([WearSync.receive]). Als Datenpaket ([onDataChanged])
 * nur der Status der Uhr und der Abbruch eines Probeweckers oder Powernaps.
 */
class PhoneListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val app = phoneApp
        val changed = dataEvents.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem }
        // Der Dienst läuft auf einem eigenen Hintergrundthread; kurzes Blockieren ist hier vorgesehen.
        runBlocking {
            // Eine (neu gekoppelte) Uhr meldet sich: Der Zweitwecker wartet jetzt die Nachfrist ab.
            if (changed.any { it.uri.path == DataLayer.PATH_WATCH_STATUS }) app.backup.reschedule()
            // Abbruch auf der Uhr, während das Handy nicht erreichbar war: nachgeliefert als Paket.
            changed.filter { it.uri.path == DataLayer.PATH_TRANSIENT_CANCELLED }
                .mapNotNull { WearSync.readTransientCancel(DataMapItem.fromDataItem(it).dataMap) }
                .forEach { Transients.cancelled(app, it) }
        }
    }

    /**
     * Nacht der Uhr speichern. Die Uhr hat geweckt und die Nacht beendet: Sicherungen und Wiederaufnahme dieser Nacht
     * nicht mehr nötig (falls «ring» oder das letzte «stop» verloren ging). Dieselbe Nacht zweimal ändert nichts.
     */
    private suspend fun storeNight(night: NightRecord) {
        val app = phoneApp
        // Nach «Verlauf löschen» nachgeliefert (Audit D-02): eine Nacht, die bis dahin endete, nicht wieder aufnehmen.
        if (endedBeforeClear(night.wokeAt)) {
            app.diag.log(DiagKind.SYNC, "night before history clear dropped")
        } else {
            app.nights.add(night)
        }
        app.backup.cancelSnoozeSafety(night.alarmId)
        app.backup.cancelSnoozeOf(night.alarmId, night.target.toEpochMilli())
        app.backup.watchHandled(night.alarmId, night.target)
    }

    /** Abgeschlossene Aufzeichnung speichern; Lernphase: zählen, nach 7 Nächten anpassen. */
    private suspend fun storeRecording(recording: NightRecording) {
        val app = phoneApp
        // Wie bei den Nächten (Audit D-02): vor «Verlauf löschen» beendet, nicht wieder aufnehmen (und nicht lernen).
        val end = recording.end
        if (end != null && endedBeforeClear(end)) {
            app.diag.log(DiagKind.SYNC, "recording before history clear dropped")
            return
        }
        app.recordings.put(recording)
        app.diag.log(DiagKind.SYNC, "recording received epochs=${recording.epochs.size} end=${recording.endReason?.code}")
        app.updateLearning(recording)
    }

    /** Ob [end] bis zum letzten «Verlauf löschen» liegt; dann ist der Eintrag gelöscht (bestätigt wird er trotzdem). */
    private suspend fun endedBeforeClear(end: Instant): Boolean {
        val cleared = phoneApp.settings.historyClearedAt.first() ?: return false
        return !end.isAfter(cleared)
    }

    /**
     * Eintrag der Warteschlange der Uhr ([path], Text [body]); unlesbare Inhalte werden übergangen (und bestätigt, sonst
     * käme derselbe Fehler immer wieder). Intern für Tests (ohne die Bestätigung über die Wear-Dienste).
     */
    internal suspend fun handleQueued(path: String, body: String) {
        val app = phoneApp
        when (path) {
            DataLayer.PATH_MSG_ALARMS -> QueueMessages.decodeAlarms(body)?.let { snapshot ->
                val result = app.alarms.receive(snapshot)
                // Der Stand hier weicht ab (z. B. eine Änderung, deren Nachricht verloren ging): zurück an die Uhr, damit
                // beide gleich werden (Audit AN-1). Einreihen ist lokal und schnell, gesendet wird im Hintergrund.
                result.reply?.let { app.sync.publishAlarms(it) }
                if (result.changed) {
                    app.diag.log(DiagKind.ALARMS_RECEIVED, "from watch")
                    app.backup.reschedule()
                }
            }
            DataLayer.PATH_MSG_NIGHT -> runCatching { Codec.decodeNight(body) }.getOrNull()?.let { storeNight(it) }
            DataLayer.PATH_MSG_RECORDING -> NightRecording.decode(body)?.takeIf { it.finished }?.let { storeRecording(it) }
            DataLayer.PATH_MSG_RECORD_STATUS -> RecordSync.decodeStatus(body)?.let { app.recordStatus.value = it }
            // Lern-Übersicht: Die Uhr zeichnet eine Nacht nicht auf (Grund, ggf. Akku).
            DataLayer.PATH_MSG_LEARN_SKIP -> LearnSkip.decode(body)?.let { skip ->
                app.learnLog.addSkip(skip)
                app.diag.log(DiagKind.SYNC, "learn skip ${skip.reason.code}${skip.battery?.let { " $it%" }.orEmpty()}")
            }
            // Einschlaf-Modus der Galaxy Watch: frühere Zielzeit auch für den Zweitwecker dieser Nacht (Review K-07).
            DataLayer.PATH_MSG_ONSET_SHIFT -> OnsetShift.decode(body)?.let { app.backup.onsetShiftReceived(it) }
        }
    }

    /**
     * Kanal der Uhr (grosse Aufzeichnung, voller Diagnose-Bericht): ganz lesen (höchstens `PeerLimit.MAX_BYTES`),
     * schliessen und verarbeiten ([handleChannel]).
     */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val app = phoneApp
        // Im App-Bereich, nicht im Thread des Empfangs (Audit AN-4): Lesen und Verarbeiten halten keine Nachricht auf.
        app.appScope.launch { app.sync.readChannel(channel)?.let { data -> handleChannel(channel.nodeId, channel.path, data) } }
    }

    /**
     * Gelesener Inhalt eines Kanals von [node] unter [path]: der Diagnose-Bericht geht an [WatchReport] (ohne
     * Warteschlange, keine Bestätigung), alles andere wie eine Nachricht der Warteschlange (verarbeiten, bestätigen).
     * Intern für Tests.
     */
    internal suspend fun handleChannel(node: String, path: String, data: ByteArray) {
        if (path == DataLayer.PATH_DIAG_LOG) {
            WatchReport.received(data)
            return
        }
        if (phoneApp.sync.receive(node, data) { handleQueued(path, it) }) flushLater()
    }

    /**
     * Eigene wartende Einträge senden: Die Uhr ist ja da. Im App-Bereich, nicht im Thread des Empfangs (Audit A-8), damit
     * ein hängender Schritt zur Uhr keine weiteren Nachrichten aufhält.
     */
    private fun flushLater() {
        val app = phoneApp
        app.appScope.launch { app.sync.flush() }
    }

    /** Die Uhr ist (wieder) erreichbar: wartende Einträge senden. */
    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.nodes.isNotEmpty()) flushLater()
    }

    override fun onMessageReceived(event: MessageEvent) {
        val app = phoneApp
        val path = event.path
        when {
            // Im App-Bereich (Audit AN-4): Die Warteschlange kann gerade beschäftigt sein, der Empfang wartet nicht darauf.
            path == DataLayer.PATH_ACK -> {
                app.appScope.launch { app.sync.acked(event.data) }
                return
            }
            // Die Uhr will den Stand des Handys (frisch installiert, neu gestartet): einreihen und senden.
            path == DataLayer.PATH_SYNC_REQUEST -> {
                // Im App-Bereich (Audit A-8); danach auch alles andere Wartende ([PhaseWakeApp.publishToWatch], Audit AN-3).
                app.appScope.launch { app.publishToWatch() }
                return
            }
            // Verarbeiten (Speichern, Neuplanen) im App-Bereich (Audit AN-4). Endet der Prozess vorher, fehlt nur die
            // Bestätigung: Die Uhr sendet den Eintrag dann später erneut.
            path.startsWith(QUEUE_PREFIX) -> {
                val data = event.data
                val node = event.sourceNodeId
                app.appScope.launch { if (app.sync.receive(node, data) { handleQueued(path, it) }) app.sync.flush() }
                return
            }
        }
        when (event.path) {
            // Nicht direkt den Weckdienst starten: aus dem Hintergrund ab Android 12 nicht erlaubt.
            DataLayer.PATH_RING -> {
                val message = RingMessage.decode(event.data)
                // Ohne Sendezeit, Kennung oder Schlummer-Runden: veraltete Uhr-App, kein Rückfall (Nacht-Check meldet sie).
                if (!message.isCompleteRing()) {
                    runBlocking { app.diag.log(DiagKind.RING, "watch rang (incomplete, ignored)") }
                    return
                }
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
                // Ohne Sendezeit: veraltete Uhr-App, kein Rückfall.
                if (!message.isCurrent()) {
                    runBlocking { app.diag.log(DiagKind.STOP, "from watch (incomplete, ignored)") }
                    return
                }
                val nowMs = System.currentTimeMillis()
                // Verspätet zugestellt (älter als 2 Minuten, wie auf dem iPhone): ändert nichts mehr.
                if (message.isStale(nowMs)) {
                    runBlocking { app.diag.log(DiagKind.STOP, "from watch (stale, ignored)") }
                    return
                }
                // Nur die Nacht, die hier klingelt oder schlummert, verstummt; ein «stop» einer anderen Nacht
                // (zwei Wecker kurz nacheinander) lässt das Klingeln des späteren Weckers weiterlaufen.
                val own = PhoneRingService.stopsRing(message, nowMs)
                // Restzahl vor dem Verstummen: für die Sicherung, falls die Uhr nach dem Schlummern ausfällt.
                val left = (PhoneRingService.ringState.value as? PhoneRingService.State.Ringing)?.snoozesLeft?.takeIf { own }
                if (own) PhoneRingService.command(this, PhoneRingService.ACTION_STOP_SILENT)
                // Die Uhr schickt «stop» auch beim Schlummern (mit Schlummer-Ende); einen Einmal-Wecker schaltet sie selbst aus.
                runBlocking { app.backup.watchStopped(message, left) }
            }
        }
    }

    private companion object {
        /** Gemeinsamer Anfang der Pfade der Warteschlange (`DataLayer.PATH_MSG_…`). */
        const val QUEUE_PREFIX = "/phasewake/m/"
    }
}
