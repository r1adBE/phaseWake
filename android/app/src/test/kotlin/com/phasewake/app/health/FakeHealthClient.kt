package com.phasewake.app.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import kotlin.reflect.KClass

/**
 * Health Connect im Speicher für [HealthSleep]: liefert [pages] (je Seite eine Liste, verbunden über Seiten-Kennungen),
 * merkt sich die Anfragen, kann den Zugriff verweigern ([granted]) oder beim Lesen scheitern ([failure]). Alles andere
 * braucht Phase Wake nicht und bricht ab.
 */
internal class FakeHealthClient(
    var pages: List<List<SleepSessionRecord>> = emptyList(),
    var granted: Set<String> = emptySet(),
    var failure: Exception? = null,
) : HealthConnectClient {
    val requests = mutableListOf<ReadRecordsRequest<*>>()

    override val permissionController: PermissionController = object : PermissionController {
        override suspend fun getGrantedPermissions(): Set<String> = granted
        override suspend fun revokeAllPermissions() = Unit
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Record> readRecords(request: ReadRecordsRequest<T>): ReadRecordsResponse<T> {
        requests += request
        failure?.let { throw it }
        val index = request.pageToken?.toInt() ?: 0
        val next = (index + 1).takeIf { it < pages.size }?.toString()
        return ReadRecordsResponse(pages.getOrElse(index) { emptyList() } as List<T>, next)
    }

    override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse = unused()
    override suspend fun updateRecords(records: List<Record>) = unused()
    override suspend fun deleteRecords(recordType: KClass<out Record>, recordIdsList: List<String>, clientRecordIdsList: List<String>) = unused()
    override suspend fun deleteRecords(recordType: KClass<out Record>, timeRangeFilter: TimeRangeFilter) = unused()
    override suspend fun <T : Record> readRecord(recordType: KClass<T>, recordId: String): ReadRecordResponse<T> = unused()
    override suspend fun aggregate(request: AggregateRequest): AggregationResult = unused()
    override suspend fun aggregateGroupByDuration(request: AggregateGroupByDurationRequest): List<AggregationResultGroupedByDuration> = unused()
    override suspend fun aggregateGroupByPeriod(request: AggregateGroupByPeriodRequest): List<AggregationResultGroupedByPeriod> = unused()
    override suspend fun getChangesToken(request: ChangesTokenRequest): String = unused()
    override suspend fun getChanges(changesToken: String): ChangesResponse = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("von Phase Wake nicht benutzt")
}
