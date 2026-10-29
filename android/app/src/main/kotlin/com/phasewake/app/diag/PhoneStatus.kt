package com.phasewake.app.diag

import android.content.Context
import android.os.PowerManager
import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.R
import com.phasewake.app.core.DiagReport
import com.phasewake.app.ring.BackupAlarm
import com.phasewake.app.ring.plannedTarget
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.ui.home.NightCheckState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Eine Zeile «Bezeichnung: Wert» der Diagnose. */
data class StatusLine(val label: String, val value: String)

/** Zustand für die Diagnose-Seite: Handy aus dem System, Uhr aus Data Layer und ihrem Bericht. */
internal object PhoneStatus {

    /** Liest alles; ruft die Uhr nicht an (nur Data Layer und den zuletzt geholten [report]). */
    suspend fun lines(context: Context, app: PhaseWakeApp, report: DiagReport?): List<StatusLine> {
        val res = context.resources
        fun yesNo(value: Boolean?) = res.getString(
            when (value) {
                true -> R.string.diag_yes
                false -> R.string.diag_no
                null -> R.string.diag_unknown
            },
        )
        val watch = report?.status?.toMap().orEmpty()
        val power = context.getSystemService(PowerManager::class.java)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        val battery = app.sync.watchBattery()
        return listOf(
            StatusLine(res.getString(R.string.diag_next_phone), when (val at = app.backup.plannedTarget()) {
                null -> res.getString(R.string.diag_none)
                else -> dateTime(at)
            }),
            StatusLine(res.getString(R.string.diag_next_watch), watch["next"]?.takeIf { it.isNotEmpty() } ?: res.getString(if (report == null) R.string.diag_unknown else R.string.diag_none)),
            StatusLine(res.getString(R.string.check_exact_alarms), yesNo(BackupAlarm.canScheduleExact(context))),
            StatusLine(res.getString(R.string.diag_battery_opt), yesNo(!power.isIgnoringBatteryOptimizations(context.packageName))),
            StatusLine(res.getString(R.string.check_notifications), yesNo(NightCheckState.hasNotificationPermission(context))),
            StatusLine(res.getString(R.string.check_watch), yesNo(app.sync.isReachable(DataLayer.CAPABILITY_WATCH))),
            StatusLine(res.getString(R.string.diag_watch_battery), battery?.let { "$it %" } ?: res.getString(R.string.diag_unknown)),
            StatusLine(res.getString(R.string.diag_watch_seen), app.sync.watchSeenAt()?.let { dateTime(Instant.ofEpochMilli(it)) } ?: res.getString(R.string.diag_unknown)),
            StatusLine(
                res.getString(R.string.diag_versions),
                res.getString(R.string.diag_phone) + " " + version + " · " + res.getString(R.string.diag_watch) + " " +
                    (watch["version"] ?: res.getString(R.string.diag_unknown)),
            ),
        )
    }

    private fun dateTime(at: Instant): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(at.atZone(ZoneId.systemDefault()))
}
