package com.phasewake.app.wear.diag

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.DiagReport
import com.phasewake.app.core.DiagReports
import com.phasewake.app.shared.BuildNumber
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.Outbox
import com.phasewake.app.shared.phaseWakeStore
import com.phasewake.app.wear.PhaseWakeWear
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Diagnose der Uhr: Bericht ans Handy ([DataLayer.PATH_DIAG_LOG]) und ob die Seite freigeschaltet ist. */
object WatchDiag {
    private val SHOWN = booleanPreferencesKey("diagnostics")

    /** Ob die Diagnose auf der Uhr sichtbar ist (7× auf das Weckfenster getippt). */
    fun shown(context: Context): Flow<Boolean> = context.phaseWakeStore.data.map { it[SHOWN] ?: false }

    /** Schaltet die Diagnose-Seite der Uhr ein oder aus. */
    suspend fun setShown(context: Context, shown: Boolean) {
        context.phaseWakeStore.edit { it[SHOWN] = shown }
    }

    /** Zustand und Protokoll der Uhr; Schlüssel wie in `DiagReports` beschrieben (version, next, battery, exact, notifications). */
    suspend fun report(app: PhaseWakeWear): DiagReport {
        val plan = app.scheduler.nextPlan()
        // Mit Build-Nummer, z. B. «16.2.2 (96)»: Das Handy zeigt sie so unter «Versionen».
        val version = BuildNumber.ownLabel(app, watch = true)
        val battery = app.getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = listOf(
            "version" to version,
            "next" to (plan?.let { "${it.windowStart} – ${it.target}" } ?: ""),
            "battery" to (battery?.toString() ?: ""),
            "exact" to (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || app.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()).toString(),
            "notifications" to NotificationManagerCompat.from(app).areNotificationsEnabled().toString(),
        )
        return DiagReport(status, withContext(Dispatchers.IO) { app.diag.entries() })
    }

    /**
     * Antwort auf [DataLayer.PATH_DIAG_REQUEST] des Handys über [transport]: klein als Nachricht, ein voller Bericht
     * (500 Einträge, über der Grenze einer Nachricht) über einen Kanal ([Outbox.sendOnce]). Erreicht er kein Handy, steht
     * das im Protokoll der Uhr.
     */
    suspend fun sendReport(app: PhaseWakeWear, transport: Outbox.Transport = Outbox.WearTransport(app)) {
        val data = DiagReports.encode(report(app)).encodeToByteArray()
        if (Outbox.sendOnce(transport, DataLayer.CAPABILITY_PHONE, DataLayer.PATH_DIAG_LOG, data) == 0) {
            app.diag.log(DiagKind.SYNC, "diag report not sent (${data.size} bytes)")
        }
    }
}
