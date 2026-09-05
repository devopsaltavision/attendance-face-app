package com.syntaxgenie.hfx05attendance.employee

import com.syntaxgenie.hfx05attendance.backend.BackendApiError
import com.syntaxgenie.hfx05attendance.backend.BackendErrorMapper
import com.syntaxgenie.hfx05attendance.backend.BackendResult
import com.syntaxgenie.hfx05attendance.backend.FingerprintApiService
import com.syntaxgenie.hfx05attendance.backend.config.BackendEnvironmentConfig
import com.syntaxgenie.hfx05attendance.backend.dto.AttendanceDeviceUserDto
import com.syntaxgenie.hfx05attendance.backend.dto.SyncUsersRequestDto
import com.syntaxgenie.hfx05attendance.backend.dto.SyncUsersResponseDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class EmployeeSyncSummary(
    val userCount: Int,
    val serverTime: String,
    val nextUpdatedAfter: String,
    val fullRefresh: Boolean,
)

class EmployeeSyncService(
    private val api: FingerprintApiService,
    private val config: BackendEnvironmentConfig,
    private val deviceIdProvider: () -> String,
    private val directory: EmployeeDirectory,
    private val networkAvailable: () -> Boolean = { true },
    private val now: () -> String = {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    },
) {
    fun syncFull(): BackendResult<EmployeeSyncSummary> = sync(full = true)
    fun syncIncremental(): BackendResult<EmployeeSyncSummary> =
        if (directory.syncState().nextUpdatedAfter.isNullOrBlank()) syncFull() else sync(full = false)

    private fun sync(full: Boolean): BackendResult<EmployeeSyncSummary> {
        val deviceId = deviceIdProvider().trim()
        if (!config.apiKeyConfigured || config.baseUrl.isBlank() || deviceId.isBlank()) {
            return BackendResult.Error(BackendApiError.CONFIGURATION_REQUIRED)
        }
        if (!networkAvailable()) return BackendResult.Error(BackendApiError.NETWORK_UNAVAILABLE)
        val cursor = if (full) null else directory.syncState().nextUpdatedAfter
        return try {
            val response = api.syncUsers(SyncUsersRequestDto(deviceId, cursor)).execute()
            if (!response.isSuccessful) {
                val body = response.errorBody()?.string().orEmpty()
                val backendCode = Regex("FPA-\\d{3}").find(body)?.value
                val error = BackendErrorMapper.fromHttp(response.code(), backendCode)
                BackendResult.Error(error, response.code(), backendCode, "HTTP ${response.code()}")
            } else {
                val value = response.body() ?: return BackendResult.Error(BackendApiError.INVALID_RESPONSE)
                persist(value, full)
            }
        } catch (error: Throwable) {
            BackendResult.Error(BackendErrorMapper.fromThrowable(error), diagnosticDetails = error.javaClass.simpleName, cause = error)
        }
    }

    private fun persist(response: SyncUsersResponseDto, full: Boolean): BackendResult<EmployeeSyncSummary> {
        if (response.serverTime.isBlank() || response.nextUpdatedAfter.isBlank()) {
            return BackendResult.Error(BackendApiError.INVALID_RESPONSE)
        }
        return try {
            val records = response.users.map(::mapUser)
            val state = EmployeeSyncState(response.nextUpdatedAfter, now())
            if (full) directory.replaceAll(records, state) else directory.upsertAll(records, state)
            BackendResult.Success(EmployeeSyncSummary(records.size, response.serverTime, response.nextUpdatedAfter, full))
        } catch (error: Throwable) {
            BackendResult.Error(BackendApiError.INVALID_RESPONSE, diagnosticDetails = "Local directory transaction failed", cause = error)
        }
    }

    private fun mapUser(dto: AttendanceDeviceUserDto) = EmployeeRecord(dto.userId, dto.employeeId,
        dto.displayName, dto.active, dto.fingerprintEnrolled, dto.fingerprintEnrollmentId,
        dto.updatedAt, dto.faceEnrolled, dto.faceEnrollmentId)
}
