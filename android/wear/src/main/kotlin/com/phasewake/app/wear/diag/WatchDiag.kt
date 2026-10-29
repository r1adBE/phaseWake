package com.phasewake.app.wear.diag

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.phasewake.app.core.DiagReport
import com.phasewake.app.core.DiagReports
import com.phasewake.app.shared.DataLayer
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
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
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

    /** Antwort auf [DataLayer.PATH_DIAG_REQUEST] des Handys. */
    suspend fun sendReport(app: PhaseWakeWear) {
        app.sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_DIAG_LOG, DiagReports.encode(report(app)).encodeToByteArray())
    }
}
