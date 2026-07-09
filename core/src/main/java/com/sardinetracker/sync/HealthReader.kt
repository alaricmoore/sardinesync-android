package com.sardinetracker.sync

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads today's data from Health Connect and assembles the JSON payload for
 * POST /api/health-sync — the same field names the iOS app sends.
 *
 * Note: Health Connect cannot supply `hrv` (SDNN) or `sun_exposure_min`.
 * Those are filled later by the Wear OS raw-IBI path and the uv-wearable
 * project respectively, so they are intentionally absent here.
 */
class HealthReader(private val client: HealthConnectClient) {

    companion object {
        /**
         * Foreground read + write perms (writes used only by the debug seeder).
         * Gate all "are we ready to read?" checks on THIS set. The background-read
         * permission is deliberately NOT here: Health Connect grants it through a
         * separate "access data in the background" toggle, so it can stay ungranted
         * even after a normal "allow all" — gating on it would wedge the logic.
         */
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(RespiratoryRateRecord::class),
            HealthPermission.getReadPermission(BodyTemperatureRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(RestingHeartRateRecord::class),
            HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getWritePermission(OxygenSaturationRecord::class),
            HealthPermission.getWritePermission(RespiratoryRateRecord::class),
            HealthPermission.getWritePermission(BodyTemperatureRecord::class),
        )

        /**
         * What we actually ask for in the permission dialog: the foreground perms
         * plus two "additional access" grants —
         *  - background read, so the nightly WorkManager job can run, and
         *  - history, so backfill can read older than Health Connect's default
         *    30-day window (without it, a 365-day backfill silently gets 30).
         * Both are granted via separate HC toggles, so logic still gates on
         * PERMISSIONS only — a user who skips them isn't blocked (backfill just
         * gets the last 30 days; the nightly job needs background access).
         */
        val REQUESTED_PERMISSIONS: Set<String> = PERMISSIONS +
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND +
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    }

    suspend fun buildPayload(userId: Int): JSONObject {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val range = TimeRangeFilter.between(today.atStartOfDay(zone).toInstant(), Instant.now())

        val payload = JSONObject()
            .put("user_id", userId)
            .put("date", today.toString()) // YYYY-MM-DD

        // Steps — sum across today
        val steps = client.readRecords(ReadRecordsRequest(StepsRecord::class, range))
            .records.sumOf { it.count }
        if (steps > 0) payload.put("steps", steps)

        // Resting heart rate — most recent today
        client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, range))
            .records.maxByOrNull { it.time }
            ?.let { payload.put("resting_heart_rate", it.beatsPerMinute) }

        // HRV RMSSD — most recent today (the one HRV value Health Connect stores)
        client.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, range))
            .records.maxByOrNull { it.time }
            ?.let { payload.put("hrv_rmssd", it.heartRateVariabilityMillis) }

        // SpO2 — most recent today (Health Connect stores percentage 0..100)
        client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, range))
            .records.maxByOrNull { it.time }
            ?.let { payload.put("spo2", it.percentage.value) }

        // Respiratory rate — most recent today
        client.readRecords(ReadRecordsRequest(RespiratoryRateRecord::class, range))
            .records.maxByOrNull { it.time }
            ?.let { payload.put("respiratory_rate", it.rate) }

        // Body temperature — most recent today, in Fahrenheit (matches iOS field)
        client.readRecords(ReadRecordsRequest(BodyTemperatureRecord::class, range))
            .records.maxByOrNull { it.time }
            ?.let { payload.put("basal_temp_delta", it.temperature.inFahrenheit) }

        return payload
    }

    /**
     * Build one payload per day over the last [days] days, for a one-time
     * historical backfill. Reads each metric once across the whole window and
     * buckets by local date (steps summed, everything else = latest that day).
     * Only days that actually have data produce a payload. The server upsert is
     * field-level, so this never clobbers manually-entered symptoms/notes.
     */
    suspend fun buildPayloadsForRange(days: Int, userId: Int): List<JSONObject> {
        val zone = ZoneId.systemDefault()
        val startDate = LocalDate.now(zone).minusDays((days - 1).toLong())
        val range = TimeRangeFilter.between(startDate.atStartOfDay(zone).toInstant(), Instant.now())

        // date -> payload, created lazily so empty days are never sent
        val byDay = LinkedHashMap<LocalDate, JSONObject>()
        fun day(d: LocalDate): JSONObject = byDay.getOrPut(d) {
            JSONObject().put("user_id", userId).put("date", d.toString())
        }

        // Steps — sum per day
        val stepsByDay = HashMap<LocalDate, Long>()
        client.readRecords(ReadRecordsRequest(StepsRecord::class, range)).records.forEach { r ->
            val d = r.startTime.atZone(zone).toLocalDate()
            stepsByDay[d] = (stepsByDay[d] ?: 0L) + r.count
        }
        stepsByDay.forEach { (d, v) -> if (v > 0) day(d).put("steps", v) }

        // Latest-per-day metrics
        client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, range)).records
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .forEach { (d, recs) -> recs.maxByOrNull { it.time }?.let { day(d).put("resting_heart_rate", it.beatsPerMinute) } }

        client.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, range)).records
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .forEach { (d, recs) -> recs.maxByOrNull { it.time }?.let { day(d).put("hrv_rmssd", it.heartRateVariabilityMillis) } }

        client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, range)).records
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .forEach { (d, recs) -> recs.maxByOrNull { it.time }?.let { day(d).put("spo2", it.percentage.value) } }

        client.readRecords(ReadRecordsRequest(RespiratoryRateRecord::class, range)).records
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .forEach { (d, recs) -> recs.maxByOrNull { it.time }?.let { day(d).put("respiratory_rate", it.rate) } }

        client.readRecords(ReadRecordsRequest(BodyTemperatureRecord::class, range)).records
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .forEach { (d, recs) -> recs.maxByOrNull { it.time }?.let { day(d).put("basal_temp_delta", it.temperature.inFahrenheit) } }

        return byDay.toSortedMap().values.toList()
    }
}
