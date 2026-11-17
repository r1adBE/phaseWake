package com.phasewake.app.shared

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.phasewake.app.core.DiagKind

/**
 * Prozessenden der App, wie das System sie festhält (`ApplicationExitInfo`, ab API 30), für das Diagnose-Protokoll:
 * Ob das System den Prozess nachts wegen Speicher, Freezer, zu hohem Verbrauch, Absturz oder ANR
 * beendet hat, steht sonst nirgends – Android Vitals gibt es nur für Store-Installationen.
 *
 * Nur Kennzahlen (Grund, Status, Wichtigkeit, Zeit, Speicher), keine Beschreibung und keine Spur; nichts verlässt das
 * Gerät. Der zuletzt gemeldete Zeitpunkt liegt im geräteverschlüsselten Speicher (auch vor dem ersten Entsperren).
 */
object ExitReasons {
    /** So viele der jüngsten Prozessenden fragt die App beim Start ab. */
    const val MAX: Int = 5

    private const val TAG = "ExitReasons"
    private const val FILE = "exit_reasons"
    private const val KEY_LAST = "last_ms"

    /**
     * Neue Prozessenden seit dem letzten Aufruf (Zeitpunkt nach dem gemerkten), älteste zuerst, je als Zeile für das
     * Protokoll; merkt den jüngsten Zeitpunkt. Ein Aufruf beim System: nicht auf dem Hauptthread aufrufen.
     */
    fun collect(context: Context, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val infos = try {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX)
        } catch (e: RuntimeException) {
            // Nur eine Hilfe für die Diagnose: ein Fehler des Systemdienstes hält den Start nicht auf.
            Log.w(TAG, "Prozessenden nicht lesbar", e)
            return emptyList()
        }
        val prefs = context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST, 0L)
        val fresh = infos.filter { it.timestamp > last }.sortedBy { it.timestamp }
        // Gleich geschrieben (Hintergrund): Endet der Prozess kurz danach, käme derselbe Eintrag sonst nochmals.
        if (fresh.isNotEmpty()) prefs.edit(commit = true) { putLong(KEY_LAST, fresh.last().timestamp) }
        return fresh.map { line(it, zone) }
    }

    /** Neue Prozessenden ([collect]) als Fehler ins Protokoll [diag]; beim App-Start, nicht auf dem Hauptthread. */
    fun logTo(context: Context, diag: DiagnosticsLog, zone: ZoneId = ZoneId.systemDefault()) = collect(context, zone).forEach { diag.log(DiagKind.ERROR, it) }

    /**
     * Zeile für das Protokoll im Schema des iPhones («exit <art> at <Ortszeit> key=value …»), z. B.
     * «exit low_memory at 2026-11-17 02:10 status=0 importance=125 pss=51200» (pss in KB).
     */
    fun line(info: ApplicationExitInfo, zone: ZoneId = ZoneId.systemDefault()): String =
        "exit ${reasonName(info.reason)} at ${TIME.format(Instant.ofEpochMilli(info.timestamp).atZone(zone))} " +
            "status=${info.status} importance=${info.importance} pss=${info.pss}"

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    /** Kurzname eines Grundes (`ApplicationExitInfo.REASON_…`, klein); unbekannte (neuere) als Zahl. */
    fun reasonName(reason: Int): String = REASONS.getOrNull(reason)?.lowercase(Locale.ROOT) ?: "reason_$reason"

    /** Namen nach Wert von `REASON_…` (0–16; FREEZER ab API 33, PACKAGE_… ab API 34, darum als Zahlen). */
    private val REASONS = listOf(
        "UNKNOWN", "EXIT_SELF", "SIGNALED", "LOW_MEMORY", "CRASH", "CRASH_NATIVE", "ANR", "INITIALIZATION_FAILURE",
        "PERMISSION_CHANGE", "EXCESSIVE_RESOURCE_USAGE", "USER_REQUESTED", "USER_STOPPED", "DEPENDENCY_DIED", "OTHER",
        "FREEZER", "PACKAGE_STATE_CHANGE", "PACKAGE_UPDATED",
    )
}
