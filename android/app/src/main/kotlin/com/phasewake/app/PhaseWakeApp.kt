package com.phasewake.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.phasewake.app.health.HealthSleep
import com.phasewake.app.ring.BackupAlarm
import com.phasewake.app.ring.RingNotifications
import com.phasewake.app.ring.onsetShiftReceived
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DiagDevice
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Learning
import com.phasewake.app.learn.LearningRepository
import com.phasewake.app.holidays.HolidayRepository
import com.phasewake.app.shared.AlarmRepository
import com.phasewake.app.shared.DiagnosticsLog
import com.phasewake.app.shared.HolidayStore
import com.phasewake.app.shared.LearningSync
import com.phasewake.app.shared.TestRunStore
import com.phasewake.app.shared.TransientStore
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.NightRepository
import com.phasewake.app.shared.RecordSync
import com.phasewake.app.shared.RecordingRepository
import com.phasewake.app.shared.WearSync
import com.phasewake.app.shared.phaseWakeStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Einstieg der Handy-App: hält die wenigen gemeinsamen Objekte (bewusst ohne DI-Bibliothek). Offen nur für Tests, die
 * [initUnlocked] weglassen (Robolectric, ohne Start-Arbeiten im Hintergrund).
 */
open class PhaseWakeApp : Application() {

    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            // Ein Fehler in einer Hintergrundarbeit darf den Prozess (und einen klingelnden Wecker) nicht beenden.
            Log.e("PhaseWakeApp", "Hintergrundarbeit fehlgeschlagen", e)
            diag.log(DiagKind.ERROR, e.javaClass.simpleName)
        },
    )

    val alarms by lazy { AlarmRepository(phaseWakeStore) }
    val nights by lazy { NightRepository(File(filesDir, "nights.v1")) }
    val settings by lazy { SettingsRepository(phaseWakeStore) }
    val sync by lazy { WearSync(this, com.phasewake.app.shared.DataLayer.CAPABILITY_WATCH) }

    /** «Nacht aufzeichnen» der Galaxy Watch: empfangene Aufzeichnungen (privat, nicht gesichert) und der Austausch. */
    val recordings by lazy { RecordingRepository(File(filesDir, "recordings")) }
    val recordSync by lazy { RecordSync(this) }

    /** Stand der Aufzeichnung auf der Uhr; `null`, solange unbekannt oder die Uhr sie nicht kennt (dann kein Knopf). */
    val recordStatus = MutableStateFlow<RecordSync.RecordStatus?>(null)

    /** Lernphase: 7 Nächte aufzeichnen, danach Wecker anpassen ([updateLearning]). */
    val learning by lazy { LearningRepository(phaseWakeStore) }

    /** Stand der Lernphase an die Uhr ([publishLearning]). */
    val learningSync by lazy { LearningSync(this) }
    val health by lazy { HealthSleep(this) }
    /** Zweitwecker; offen nur für Robolectric-Tests ohne Wear-Dienste («zuletzt gesehen» der Uhr fest). */
    open val backup: BackupAlarm by lazy { BackupAlarm(this, alarms, phaseWakeStore, sync) }
    val notes by lazy { com.phasewake.app.notes.NotesRepository(phaseWakeStore) }
    val bedtime by lazy { com.phasewake.app.reminder.BedtimeReminder(this, alarms, settings) }
    val weekly by lazy { com.phasewake.app.reminder.WeeklyReminder(this) }
    val nextAlarm by lazy { com.phasewake.app.widget.NextAlarmNotice(this) }
    val sounds by lazy { com.phasewake.app.sounds.SoundStore(this) }
    val routine by lazy { com.phasewake.app.habits.RoutineRepository(phaseWakeStore) }

    /** Diagnose-Protokoll (geräteverschlüsselt, auch vor dem ersten Entsperren). */
    val diag by lazy { DiagnosticsLog.forContext(this, DiagDevice.PHONE) }

    /** Probewecker oder Powernap, der gerade gilt. */
    val transient by lazy { TransientStore(phaseWakeStore) }
    val testRuns by lazy { TestRunStore(phaseWakeStore) }
    val holidays by lazy { HolidayRepository(this, settings, HolidayStore(phaseWakeStore), sync, diag::log) }

    /** Weckart zu einer Kennung: gespeicherter Wecker oder vorübergehender Plan (Probewecker, Powernap). */
    suspend fun specFor(alarmId: String?): AlarmSpec? =
        alarms.current().alarms.firstOrNull { it.id == alarmId } ?: transient.specFor(alarmId)

    override fun onCreate() {
        super.onCreate()
        RingNotifications.createChannels(this)
        com.phasewake.app.ui.Format.update(this)
        com.phasewake.app.ring.SunriseLight.createChannel(this)
        com.phasewake.app.reminder.BedtimeReminder.createChannel(this)
        com.phasewake.app.widget.NextAlarmNotice.createChannel(this)
        com.phasewake.app.sounds.SleepSoundService.createChannel(this)
        com.phasewake.app.sounds.SoundRecorderService.createChannel(this)
        // Vor dem ersten Entsperren nach einem Neustart (nur der Zweitwecker läuft) ist der Speicher
        // nicht lesbar. Der Prozess lebt nach dem Entsperren weiter: dann [initUnlocked] nachholen (Review M-1).
        if (!BackupAlarm.isUnlocked(this)) {
            diag.log(DiagKind.DIRECT_BOOT, "app start locked")
            awaitUnlock()
            return
        }
        diag.log(DiagKind.BOOT, "app start")
        initUnlocked()
    }

    /** Ob [initUnlocked] in diesem Prozess schon lief. */
    private val unlockedDone = AtomicBoolean(false)

    /**
     * Start-Arbeiten, die den entsperrten Speicher brauchen: unterbrochenes Wiederherstellen abschliessen, Stand der Uhr
     * anfragen, neu planen, Widget, Geräusch-Ausschnitte aufräumen (täglich). Einmal pro Prozess, sobald entsperrt: beim
     * Start, nach dem Entsperren ([awaitUnlock]) und bei BOOT_COMPLETED ([com.phasewake.app.ring.onBackupSystemEvent]).
     * Vor dem Entsperren und bei jedem weiteren Aufruf: nichts.
     */
    open fun initUnlocked() {
        if (!BackupAlarm.isUnlocked(this) || !unlockedDone.compareAndSet(false, true)) return
        // Datensparsamkeit: ein früher geteilter Export bleibt nicht im Cache liegen.
        java.io.File(cacheDir, "export").deleteRecursively()
        appScope.launch {
            // Ein Wiederherstellen, das ein Prozessende unterbrochen hat, abschliessen oder zurücksetzen (Review K-03).
            // Ein Fehler dabei hält das Planen unten nicht auf.
            try {
                com.phasewake.app.backup.BackupManager.recover(this@PhaseWakeApp)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("PhaseWakeApp", "Unterbrochenes Wiederherstellen nicht geprüft", e)
            }
            // Warteschlange: einmalig eigene Pakete der früheren Wege löschen, Wartendes senden, Stand der Uhr anfragen
            // (Wecker und Aufzeichnungs-Stand; Nächte und Aufzeichnungen kommen aus ihrer Warteschlange).
            sync.migrateTransport()
            sync.flush()
            sync.requestSync()
            backup.reschedule()
            // Feiertage nachführen (Fenster der nächsten 400 Tage); bei einer Änderung neu planen.
            if (holidays.refresh()) backup.reschedule()
            // Schlafziel für den Einschlafpunkt auf der Uhr (unverändert: gleiches Paket, die Uhr merkt nichts).
            publishSleepGoal()
            // Schalter der Einschlaf-Messung am Abend (ebenso unverändert, wenn nichts geändert wurde).
            publishAutoOnset()
            // Lernphase: beginnt nie von selbst, nur mit Zustimmung ([setLearningConsent]); zählen, Stand an die Uhr
            // (unverändert: gleiches Paket, die Uhr merkt nichts). Zuletzt: Ein Fehler hier hält das Planen nicht auf.
            updateLearning()
            publishLearning()
        }
        // Widget «Details von heute»: neu zeichnen, sobald eine Nacht gespeichert oder das Befinden gesetzt wird.
        appScope.launch {
            nights.nights.filterNotNull().collect { com.phasewake.app.widget.NextAlarmWidget.update(this@PhaseWakeApp) }
        }
        // Geräusche: Abgelaufenes beim Start löschen, danach täglich.
        appScope.launch { sounds.cleanup() }
        com.phasewake.app.sounds.SoundStore.scheduleCleanup(this)
    }

    /**
     * Gestartet vor dem ersten Entsperren: auf das Entsperren warten (nur im laufenden Prozess; endet er vorher, startet
     * der nächste entsperrt). Danach [initUnlocked]. Auch wenn das Entsperren zwischen Prüfen und Anmelden fiel.
     */
    private fun awaitUnlock() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_UNLOCKED) return
                try {
                    unregisterReceiver(this)
                } catch (e: IllegalArgumentException) {
                    Log.w("PhaseWakeApp", "Empfänger schon abgemeldet", e)
                }
                diag.log(DiagKind.BOOT, "app unlocked")
                initUnlocked()
            }
        }
        // Systemmeldung: kommt auch bei einem nicht exportierten Empfänger an.
        ContextCompat.registerReceiver(this, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
        initUnlocked()
    }

    /**
     * Aktuellen Stand an die Uhr einreihen (Wunsch der Uhr, [com.phasewake.app.shared.DataLayer.PATH_SYNC_REQUEST]):
     * Wecker, Schlafziel, Schalter der Einschlaf-Messung, Feiertage und Lernphase. Gleiche Schlüssel ersetzen ältere
     * Einträge; die Uhr übernimmt nur Neueres.
     */
    suspend fun publishToWatch() {
        sync.publishAlarms(alarms.current())
        publishSleepGoal()
        publishAutoOnset()
        holidays.republish()
        publishLearning()
    }

    /** Schickt das Schlafziel an die Uhr (nach einer Änderung, beim Start, nach dem Wiederherstellen). */
    suspend fun publishSleepGoal() {
        sync.publishSleepGoal(settings.sleepGoalNow(), settings.sleepGoalStamp())
    }

    /** Schickt den Schalter der Einschlaf-Messung an die Uhr (nach einer Änderung, beim Start, nach dem Wiederherstellen). */
    suspend fun publishAutoOnset() {
        val (enabled, stamp) = settings.autoOnsetStamped()
        sync.publishAutoOnset(enabled, stamp)
    }

    /**
     * Lernphase: zählt die Aufzeichnungen seit ihrem Beginn ([Learning.counted]); nach [Learning.NIGHTS] gezählten
     * Nächten werden die Werte gelernt, gespeichert und an die Uhr geschickt. [received]: eben angekommene Aufzeichnung,
     * fürs Diagnose-Protokoll («learn night counted 3/7» bzw. «… not counted epochs=210»).
     */
    suspend fun updateLearning(received: NightRecording? = null) {
        // Die Lernphase ist eine Zugabe: Ein Fehler (Speicher) hält das Speichern und Abholen der Aufzeichnungen nicht auf.
        try {
            countLearning(received)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w("PhaseWakeApp", "Lernphase nicht nachgeführt", e)
        }
    }

    private suspend fun countLearning(received: NightRecording?) {
        val state = learning.current()
        val since = state.since ?: return
        if (!state.active) return
        val counted = Learning.counted(recordings.load().map { it.toLearnNight() }, since, ZoneId.systemDefault())
        if (received != null && !received.start.isBefore(since)) {
            val night = received.toLearnNight()
            val result = if (Learning.counts(night)) "counted" else "not counted epochs=${Learning.measuredEpochs(night)}"
            diag.log(DiagKind.SYNC, "learn night $result ${counted.size}/${Learning.NIGHTS}")
        }
        if (!Learning.finished(counted.size)) return
        val values = Learning.learn(counted.takeLast(Learning.NIGHTS))
        if (learning.finish(values, Instant.now())) {
            diag.log(DiagKind.SYNC, "learn done ${learning.current().snapshot().diagLine()} lat=${values.latencyMinutes ?: "-"}")
            publishLearning()
        }
    }

    /**
     * Schickt den Stand der Lernphase an die Uhr (beim Start, fertig, neu begonnen). Offen nur für Robolectric-Tests, die
     * die Meldung prüfen (dort gibt es keinen Data Layer).
     */
    internal open suspend fun publishLearning() {
        learningSync.publish(learning.current().snapshot())
    }

    /**
     * Zustimmung zur Lernphase (Einrichtung, Karte auf der Startseite, Schalter in den Einstellungen): an beginnt sie
     * bzw. läuft weiter, aus zeichnet die Uhr nicht mehr automatisch auf (eine laufende Aufzeichnung der Lernphase endet).
     * Stand an die Uhr.
     */
    suspend fun setLearningConsent(on: Boolean) {
        val started = learning.setConsent(on, Instant.now())
        diag.log(DiagKind.SYNC, "learn consent ${if (on) "on" else "off"}${if (started) " started" else ""}")
        updateLearning()
        publishLearning()
    }

    /** «Verlauf löschen»: Lernphase ab jetzt von vorn, gelernte Werte verworfen (auch auf der Uhr). */
    suspend fun restartLearning() {
        learning.restart(Instant.now())
        diag.log(DiagKind.SYNC, "learn restarted")
        publishLearning()
    }

    /**
     * «Neu lernen»: Lernphase ab jetzt von vorn; die bisherigen Werte gelten am Handy und auf der Uhr weiter, bis die neuen
     * Nächte gezählt sind ([updateLearning] ersetzt sie dann).
     */
    suspend fun relearn() {
        learning.relearn(Instant.now())
        diag.log(DiagKind.SYNC, "learn relearn ${learning.current().snapshot().diagLine()}")
        publishLearning()
    }

    /** Speichert eine Änderung an den Weckern, schickt sie an die Uhr und plant den Zweitwecker neu. */
    fun afterAlarmsChanged(change: suspend () -> com.phasewake.app.shared.AlarmSnapshot) {
        appScope.launch {
            val snapshot = change()
            // Zuerst der Zweitwecker am Handy: Das Senden an die Uhr kann warten, wenn sie nicht erreichbar ist.
            backup.reschedule()
            com.phasewake.app.widget.NextAlarmWidget.update(this@PhaseWakeApp)
            diag.log(DiagKind.SYNC, "alarms sent ${snapshot.alarms.size}")
            sync.publishAlarms(snapshot)
        }
    }
}

val Context.phoneApp: PhaseWakeApp get() = applicationContext as PhaseWakeApp
