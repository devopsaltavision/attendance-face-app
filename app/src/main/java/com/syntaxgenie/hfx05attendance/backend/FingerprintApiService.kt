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
import retrofit2.http.GET
import retrofit2.http.Query
import com.syntaxgenie.hfx05attendance.backend.dto.RecordEnrollmentRequestDto
import com.syntaxgenie.hfx05attendance.backend.dto.RecordEnrollmentResponseDto
import com.syntaxgenie.hfx05attendance.backend.dto.FaceEnrollmentRequestDto
import com.syntaxgenie.hfx05attendance.backend.dto.FaceEnrollmentResponseDto
import com.syntaxgenie.hfx05attendance.backend.dto.FaceEnrollmentRemoteDto
import com.syntaxgenie.hfx05attendance.backend.dto.FaceEnrollmentLookupResponseDto

interface FingerprintApiService {
    @POST("api/fingerprint/enrollments")
    fun recordEnrollment(@Body request: RecordEnrollmentRequestDto): Call<RecordEnrollmentResponseDto>

    @POST("api/fingerprint/enrollments")
    fun recordFaceEnrollment(@Body request: FaceEnrollmentRequestDto): Call<FaceEnrollmentResponseDto>

    @GET("api/fingerprint/enrollments")
    fun getFaceEnrollments(
        @Query("deviceId") deviceId: String,
        @Query("userId") userId: String,
        @Query("biometricType") biometricType: String = "FACE",
    ): Call<FaceEnrollmentLookupResponseDto>

    @POST("api/fingerprint/sync-users")
    fun syncUsers(@Body request: SyncUsersRequestDto): Call<SyncUsersResponseDto>

    @POST("api/fingerprint/attendance")
    fun recordAttendance(@Body request: RecordAttendanceRequestDto): Call<RecordAttendanceResponseDto>

    @POST("api/fingerprint/attendance/bulk")
    fun recordAttendanceBulk(@Body request: BulkAttendanceRequestDto): Call<BulkAttendanceResponseDto>
}
