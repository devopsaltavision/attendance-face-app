package com.syntaxgenie.hfx05attendance.attendance

import android.util.Log
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

enum class AttendanceRecordStatus { SYNCED, PENDING, REJECTED, DUPLICATE_IGNORED }

enum class AttendanceBusinessRejection { NO_OPEN_SESSION, UNKNOWN }

data class AttendanceRecordOutcome(
    val event: AttendanceEvent,
    val status: AttendanceRecordStatus,
    val error: BackendApiError? = null,
    val message: String? = null,
    val businessRejection: AttendanceBusinessRejection? = null,
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
    private val pendingSyncScheduler: (() -> Unit)? = null,
) {
    fun record(userId: String, employeeId: String, action: String? = null, biometricType: String? = null): AttendanceRecordOutcome {
        require(action == null || action in setOf("CHECK_IN", "CHECK_OUT"))
        require(biometricType == null || biometricType in setOf("FINGERPRINT", "FACE"))
        val event = AttendanceEvent(eventIdProvider(), userId, employeeId, timestampProvider(), requestedAction = action, biometricType = biometricType)
        repository.insertPending(event)
        val durableEvent = repository.get(event.eventId) ?: event
        return submitSingle(durableEvent).also { outcome ->
            if (outcome.isNetworkPending()) pendingSyncScheduler?.invoke()
        }
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
                    } else if (result.status == REJECTED) {
                        repository.delete(result.attendanceEventId)
                        logWarning("Attendance queued event rejected eventId=${result.attendanceEventId} " +
                            "message=${result.message.orEmpty()}")
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
            val request = RecordAttendanceRequestDto(
                attendanceEventId = event.eventId,
                deviceId = deviceIdProvider().trim(),
                userId = event.userId,
                employeeId = event.employeeId,
                deviceTimestamp = event.deviceTimestamp,
                action = event.requestedAction,
                biometricType = event.biometricType,
            )
            logDebug("Attendance request eventId=${request.attendanceEventId} deviceId=${request.deviceId} " +
                "userId=${request.userId} employeeId=${request.employeeId} timestamp=${request.deviceTimestamp} " +
                "clientSequence=${request.clientSequence} action=${request.action} " +
                "biometricType=${request.biometricType} source=${request.source}")
            val response = api.recordAttendance(request).execute()
            if (!response.isSuccessful) {
                val body = response.errorBody()?.string().orEmpty()
                val code = Regex("FPA-\\d{3}").find(body)?.value
                val mappedError = BackendErrorMapper.fromHttp(response.code(), code)
                logHttpFailure(response.code(), code, mappedError, body)
                AttendanceRecordOutcome(event, AttendanceRecordStatus.PENDING, mappedError)
            } else {
                logDebug("Attendance HTTP result status=${response.code()} rawBody=${response.rawBodyForLog()}")
                val value = response.body()
                    ?: return invalidResponse(event, "HTTP success response body was null")
                logDebug("Attendance parsed response eventId=${value.attendanceEventId} status=${value.status} " +
                    "serverTimestamp=${value.serverTimestamp} success=${value.success}")
                if (value.attendanceEventId != event.eventId) {
                    return invalidResponse(event, "attendanceEventId mismatch expected=${event.eventId} actual=${value.attendanceEventId}")
                }
                if (value.status == DUPLICATE_IGNORED) {
                    repository.delete(event.eventId)
                    AttendanceRecordOutcome(event, AttendanceRecordStatus.DUPLICATE_IGNORED,
                        message = value.message)
                } else if (value.status == REJECTED) {
                    repository.delete(event.eventId)
                    val rejection = businessRejection(value.message)
                    logWarning("Attendance business rejection reason=$rejection message=${value.message.orEmpty()}")
                    AttendanceRecordOutcome(event, AttendanceRecordStatus.REJECTED,
                        businessRejection = rejection)
                } else if (value.status == RECORDED || value.status == ALREADY_RECORDED) {
                    val serverTimestamp = value.serverTimestamp?.takeIf { it.isNotBlank() }
                        ?: return invalidResponse(event, "serverTimestamp missing or blank for status=${value.status}")
                    repository.markSynced(event.eventId, value.attendanceRecordId,
                        value.attendanceAction, serverTimestamp)
                    AttendanceRecordOutcome(repository.get(event.eventId) ?: event,
                        AttendanceRecordStatus.SYNCED)
                } else {
                    logWarning("Attendance response status=${value.status} message=${value.message.orEmpty()}")
                    AttendanceRecordOutcome(event, AttendanceRecordStatus.PENDING,
                        if (value.status == FAILED) BackendApiError.SERVER_FAILURE
                        else BackendApiError.INVALID_ATTENDANCE_EVENT)
                }
            }
        } catch (error: Throwable) {
            val mappedError = BackendErrorMapper.fromThrowable(error)
            logWarning("Attendance request failure type=${error.javaClass.name} mappedError=$mappedError " +
                "message=${error.message.orEmpty()}")
            AttendanceRecordOutcome(event, AttendanceRecordStatus.PENDING, mappedError)
        }
    }

    private fun invalidResponse(event: AttendanceEvent, reason: String): AttendanceRecordOutcome {
        logWarning("Attendance response rejected mappedError=${BackendApiError.INVALID_RESPONSE} reason=$reason")
        return AttendanceRecordOutcome(event, AttendanceRecordStatus.PENDING, BackendApiError.INVALID_RESPONSE)
    }

    private fun businessRejection(message: String?): AttendanceBusinessRejection =
        if (message == NO_OPEN_SESSION_MESSAGE) AttendanceBusinessRejection.NO_OPEN_SESSION
        else AttendanceBusinessRejection.UNKNOWN

    private fun logHttpFailure(status: Int, code: String?, mappedError: BackendApiError, body: String) {
        val message = Regex("\\\"message\\\"\\s*:\\s*\\\"([^\\\"]*)").find(body)?.groupValues?.getOrNull(1).orEmpty()
        logWarning("Attendance HTTP failure status=$status backendCode=${code.orEmpty()} mappedError=$mappedError " +
            "message=$message errorBody=$body")
    }

    private fun retrofit2.Response<*>.rawBodyForLog(): String = runCatching {
        raw().peekBody(MAX_LOGGED_BODY_BYTES).string()
    }.getOrElse { "<unavailable: ${it.javaClass.simpleName}>" }

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logWarning(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private fun configurationError(): BackendApiError? =
        if (!config.apiKeyConfigured || config.baseUrl.isBlank() || deviceIdProvider().trim().isBlank()) {
            BackendApiError.CONFIGURATION_REQUIRED
        } else null

    private fun AttendanceEvent.toDto() = AttendanceEventDto(eventId, userId, employeeId,
        deviceTimestamp = deviceTimestamp, action = requestedAction, biometricType = biometricType)

    private fun AttendanceRecordOutcome.isNetworkPending(): Boolean =
        status == AttendanceRecordStatus.PENDING &&
            (error == BackendApiError.NETWORK_UNAVAILABLE || error == BackendApiError.NETWORK_FAILURE)

    private companion object {
        const val TAG = "AttendanceService"
        const val MAX_BATCH_SIZE = 50
        const val RECORDED = "RECORDED"
        const val ALREADY_RECORDED = "ALREADY_RECORDED"
        const val DUPLICATE_IGNORED = "DUPLICATE_IGNORED"
        const val REJECTED = "REJECTED"
        const val FAILED = "FAILED"
        const val NO_OPEN_SESSION_MESSAGE = "No open session found to check out."
        const val MAX_LOGGED_BODY_BYTES = 16L * 1024L
    }
}
