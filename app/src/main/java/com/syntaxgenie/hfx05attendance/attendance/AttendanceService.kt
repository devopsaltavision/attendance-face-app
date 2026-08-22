package com.syntaxgenie.hfx05attendance.attendance

import com.syntaxgenie.hfx05attendance.backend.BackendApiError
import com.syntaxgenie.hfx05attendance.backend.BackendErrorMapper
import com.syntaxgenie.hfx05attendance.backend.FingerprintApiService
import com.syntaxgenie.hfx05attendance.backend.config.BackendEnvironmentConfig
import com.syntaxgenie.hfx05attendance.backend.dto.AttendanceEventDto
import com.syntaxgenie.hfx05attendance.backend.dto.BulkAttendanceRequestDto
import com.syntaxgenie.hfx05attendance.backend.dto.RecordAttendanceRequestDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class AttendanceRecordStatus { SYNCED, PENDING, DUPLICATE_IGNORED }

data class AttendanceRecordOutcome(
    val event: AttendanceEvent,
    val status: AttendanceRecordStatus,
    val error: BackendApiError? = null,
    val message: String? = null,
)

data class AttendanceSyncSummary(
    val attempted: Int,
    val synced: Int,
    val remaining: Int,
    val error: BackendApiError? = null,
)

class AttendanceService(
    private val api: FingerprintApiService,
    private val config: BackendEnvironmentConfig,
    private val deviceIdProvider: () -> String,
    private val repository: AttendanceRepository,
    private val networkAvailable: () -> Boolean = { true },
    private val eventIdProvider: () -> String = { UUID.randomUUID().toString() },
    private val timestampProvider: () -> String = {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
    },
) {
    fun record(userId: String, employeeId: String): AttendanceRecordOutcome {
        val event = AttendanceEvent(eventIdProvider(), userId, employeeId, timestampProvider())
        repository.insertPending(event)
        val durableEvent = repository.get(event.eventId) ?: event
        return submitSingle(durableEvent)
    }

    fun syncPendingAttendance(limit: Int = MAX_BATCH_SIZE): AttendanceSyncSummary {
        val pending = repository.pending(limit.coerceIn(1, MAX_BATCH_SIZE))
        if (pending.isEmpty()) return AttendanceSyncSummary(0, 0, 0)
        configurationError()?.let { return AttendanceSyncSummary(0, 0, pending.size, it) }
        if (!networkAvailable()) {
            return AttendanceSyncSummary(0, 0, pending.size, BackendApiError.NETWORK_UNAVAILABLE)
        }

        val deviceId = deviceIdProvider().trim()
        return try {
            val response = api.recordAttendanceBulk(BulkAttendanceRequestDto(
                deviceId,
                pending.map { it.toDto() },
            )).execute()
            if (!response.isSuccessful) {
                val body = response.errorBody()?.string().orEmpty()
                val code = Regex("FPA-\\d{3}").find(body)?.value
                AttendanceSyncSummary(pending.size, 0, pending.size,
                    BackendErrorMapper.fromHttp(response.code(), code))
            } else {
                val value = response.body()
                    ?: return AttendanceSyncSummary(pending.size, 0, pending.size, BackendApiError.INVALID_RESPONSE)
                if (value.serverTimestamp.isBlank()) {
                    return AttendanceSyncSummary(pending.size, 0, pending.size, BackendApiError.INVALID_RESPONSE)
                }
                var synced = 0
                val pendingIds = pending.map { it.eventId }.toSet()
                value.results.filter { it.attendanceEventId in pendingIds }.forEach { result ->
                    if (result.status == RECORDED || result.status == ALREADY_RECORDED) {
                        repository.markSynced(result.attendanceEventId, result.attendanceRecordId,
                            result.attendanceAction, value.serverTimestamp)
                        synced++
                    }
                }
                AttendanceSyncSummary(pending.size, synced, pending.size - synced)
            }
        } catch (error: Throwable) {
            AttendanceSyncSummary(pending.size, 0, pending.size, BackendErrorMapper.fromThrowable(error))
        }
    }

    private fun submitSingle(event: AttendanceEvent): AttendanceRecordOutcome {
        configurationError()?.let { return AttendanceRecordOutcome(event, AttendanceRecordStatus.PENDING, it) }
        if (!networkAvailable()) {
            return AttendanceRecordOutcome(event, AttendanceRecordStatus.PENDING, BackendApiError.NETWORK_UNAVAILABLE)
        }
        return try {
            val response = api.recordAttendance(RecordAttendanceRequestDto(
                attendanceEventId = event.eventId,
                deviceId = deviceIdProvider().trim(),
                userId = event.userId,
                employeeId = event.employeeId,
                deviceTimestamp = event.deviceTimestamp,
            )).execute()
            if (!response.isSuccessful) {
                val body = response.errorBody()?.string().orEmpty()
                val code = Regex("FPA-\\d{3}").find(body)?.value
                AttendanceRecordOutcome(event, AttendanceRecordStatus.PENDING,
                    BackendErrorMapper.fromHttp(response.code(), code))
            } else {
                val value = response.body()
                    ?: return AttendanceRecordOutcome(event, AttendanceRecordStatus.PENDING, BackendApiError.INVALID_RESPONSE)
                if (value.attendanceEventId != event.eventId) {
                    return AttendanceRecordOutcome(event, AttendanceRecordStatus.PENDING, BackendApiError.INVALID_RESPONSE)
                }
                if (value.status == DUPLICATE_IGNORED) {
                    repository.delete(event.eventId)
                    AttendanceRecordOutcome(event, AttendanceRecordStatus.DUPLICATE_IGNORED,
                        message = value.message)
                } else if (value.status == RECORDED || value.status == ALREADY_RECORDED) {
                    val serverTimestamp = value.serverTimestamp?.takeIf { it.isNotBlank() }
                        ?: return AttendanceRecordOutcome(event, AttendanceRecordStatus.PENDING,
                            BackendApiError.INVALID_RESPONSE)
                    repository.markSynced(event.eventId, value.attendanceRecordId,
                        value.attendanceAction, serverTimestamp)
                    AttendanceRecordOutcome(repository.get(event.eventId) ?: event,
                        AttendanceRecordStatus.SYNCED)
                } else {
                    AttendanceRecordOutcome(event, AttendanceRecordStatus.PENDING,
                        if (value.status == FAILED) BackendApiError.SERVER_FAILURE
                        else BackendApiError.INVALID_ATTENDANCE_EVENT)
                }
            }
        } catch (error: Throwable) {
            AttendanceRecordOutcome(event, AttendanceRecordStatus.PENDING, BackendErrorMapper.fromThrowable(error))
        }
    }

    private fun configurationError(): BackendApiError? =
        if (!config.apiKeyConfigured || config.baseUrl.isBlank() || deviceIdProvider().trim().isBlank()) {
            BackendApiError.CONFIGURATION_REQUIRED
        } else null

    private fun AttendanceEvent.toDto() = AttendanceEventDto(eventId, userId, employeeId,
        deviceTimestamp = deviceTimestamp)

    private companion object {
        const val MAX_BATCH_SIZE = 50
        const val RECORDED = "RECORDED"
        const val ALREADY_RECORDED = "ALREADY_RECORDED"
        const val DUPLICATE_IGNORED = "DUPLICATE_IGNORED"
        const val FAILED = "FAILED"
    }
}
