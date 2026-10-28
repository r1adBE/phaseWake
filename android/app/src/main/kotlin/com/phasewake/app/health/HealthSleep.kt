package com.phasewake.app.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.SleepSummary
import com.phasewake.app.core.StageSpan
import java.time.Duration
import java.time.Instant

/**
 * Liest Schlafphasen, die eine Gesundheits-App (z. B. Samsung Health) nach dem Aufwachen in
 * Health Connect ablegt. Nur Lesen, nur auf Wunsch, nichts verlässt das Handy.
 */
class HealthSleep(private val context: Context) {

    val permissions: Set<String> = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))

    /** Ob Health Connect auf dem Gerät nutzbar ist. */
    val isAvailable: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    /** Health Connect ist installiert, muss aber erst aktualisiert werden. */
    val needsUpdate: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun isGranted(): Boolean =
        isAvailable && runCatching { client.permissionController.getGrantedPermissions().containsAll(permissions) }.getOrDefault(false)

    /** Alle Schlafphasen von [from] bis [to] in einer Abfrage (für den ganzen Verlauf). */
    suspend fun stagesBetween(from: Instant, to: Instant): List<StageSpan> {
        if (!isGranted()) return emptyList()
        return try {
            client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, to))).records.flatMap { session ->
                session.stages.map { StageSpan(it.startTime, it.endTime, map(it.stage)) }
            }.sortedBy { it.start }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Schlafphasen nicht lesbar", e)
            emptyList()
        }
    }

    /** Schlafphasen rund um die Nacht; leer, wenn nichts vorliegt oder der Zugriff fehlt. */
    suspend fun stagesFor(night: NightRecord): List<StageSpan> {
        if (!isGranted()) return emptyList()
        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    SleepSessionRecord::class,
                    TimeRangeFilter.between(night.windowStart.minus(LOOK_BACK), night.wokeAt.plus(LOOK_AHEAD)),
                ),
            )
            response.records.flatMap { session ->
                session.stages.map { StageSpan(it.startTime, it.endTime, map(it.stage)) }
            }.sortedBy { it.start }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Schlafphasen nicht lesbar", e)
            emptyList()
        }
    }

    /**
     * Ganze Nächte der letzten [days] Tage für die Trends. Sitzungen ohne Phasen zählen als
     * Schlaf über die ganze Dauer.
     */
    suspend fun recentSleep(days: Long = 15): List<SleepSummary> {
        if (!isGranted()) return emptyList()
        return try {
            val now = Instant.now()
            val response = client.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(days)), now)),
            )
            SleepSummary.group(
                response.records.flatMap { session ->
                    session.stages.map { StageSpan(it.startTime, it.endTime, map(it.stage)) }
                        .ifEmpty { listOf(StageSpan(session.startTime, session.endTime, SleepStage.UNKNOWN)) }
                },
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Schlafdauer nicht lesbar", e)
            emptyList()
        }
    }

    private fun map(stage: Int): SleepStage = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
        -> SleepStage.AWAKE
        SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepStage.LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStage.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> SleepStage.REM
        else -> SleepStage.UNKNOWN
    }

    private companion object {
        const val TAG = "HealthSleep"
        val LOOK_BACK: Duration = Duration.ofHours(14)
        val LOOK_AHEAD: Duration = Duration.ofHours(2)
    }
}
