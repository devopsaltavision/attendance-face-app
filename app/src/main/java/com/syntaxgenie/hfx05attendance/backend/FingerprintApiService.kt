package com.syntaxgenie.hfx05attendance.backend

import com.syntaxgenie.hfx05attendance.backend.dto.SyncUsersRequestDto
import com.syntaxgenie.hfx05attendance.backend.dto.SyncUsersResponseDto
import com.syntaxgenie.hfx05attendance.backend.dto.BulkAttendanceRequestDto
import com.syntaxgenie.hfx05attendance.backend.dto.BulkAttendanceResponseDto
import com.syntaxgenie.hfx05attendance.backend.dto.RecordAttendanceRequestDto
import com.syntaxgenie.hfx05attendance.backend.dto.RecordAttendanceResponseDto
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST
import com.syntaxgenie.hfx05attendance.backend.dto.RecordEnrollmentRequestDto
import com.syntaxgenie.hfx05attendance.backend.dto.RecordEnrollmentResponseDto

interface FingerprintApiService {
    @POST("api/fingerprint/enrollments")
    fun recordEnrollment(@Body request: RecordEnrollmentRequestDto): Call<RecordEnrollmentResponseDto>

    @POST("api/fingerprint/sync-users")
    fun syncUsers(@Body request: SyncUsersRequestDto): Call<SyncUsersResponseDto>

    @POST("api/fingerprint/attendance")
    fun recordAttendance(@Body request: RecordAttendanceRequestDto): Call<RecordAttendanceResponseDto>

    @POST("api/fingerprint/attendance/bulk")
    fun recordAttendanceBulk(@Body request: BulkAttendanceRequestDto): Call<BulkAttendanceResponseDto>
}
