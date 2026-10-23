package com.example.fitnotes.data

import android.content.Context
import androidx.annotation.RequiresApi
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import java.time.Instant

@RequiresApi(28)
class HealthConnectSyncSource(context: Context, private val client: HealthConnectClient = HealthConnectClient.getOrCreate(context)) : HealthSyncSource {
    private val reader = HealthConnectReader(context, client)
    override suspend fun access() = reader.access()
    private fun type(feed: HealthFeed) = when (feed) {
        HealthFeed.EXERCISE -> ExerciseSessionRecord::class
        HealthFeed.HEART_RATE -> HeartRateRecord::class
        HealthFeed.DISTANCE -> DistanceRecord::class
        HealthFeed.ELEVATION -> ElevationGainedRecord::class
    }
    override suspend fun token(feed: HealthFeed) = client.getChangesToken(ChangesTokenRequest(setOf(type(feed))))
    override suspend fun sessions(start: Instant, end: Instant) = reader.sessions(start, end)
    override suspend fun enrich(candidate: HealthCandidate) = reader.enrich(candidate)
    override fun invalidateMetrics() = reader.invalidateMetrics()
    override suspend fun changes(feed: HealthFeed, token: String): HealthDeltaPage {
        val page = try { client.getChanges(token) } catch (_: IllegalArgumentException) { throw HealthTokenExpired() }
        if (page.changesTokenExpired) throw HealthTokenExpired()
        val upserts = page.changes.filterIsInstance<UpsertionChange>().map { it.record }
        return HealthDeltaPage(sessions = upserts.filterIsInstance<ExerciseSessionRecord>().map(reader::candidate),
            deletedIds = page.changes.filterIsInstance<DeletionChange>().map { it.recordId }.toSet(),
            metricsChanged = feed != HealthFeed.EXERCISE && upserts.isNotEmpty(), next = page.nextChangesToken, hasMore = page.hasMore)
    }
}

fun healthSessionDate(s: HealthSession): String {
    val offset = s.startOffsetSeconds?.let(java.time.ZoneOffset::ofTotalSeconds)
    return Instant.ofEpochMilli(s.start).atZone(offset ?: java.time.ZoneId.systemDefault()).toLocalDate().toString()
}
