package com.syntaxgenie.hfx05attendance.backend

import com.syntaxgenie.hfx05attendance.backend.dto.SyncUsersRequestDto
import com.syntaxgenie.hfx05attendance.backend.dto.SyncUsersResponseDto
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

interface FingerprintApiService {
    @POST("api/fingerprint/sync-users")
    fun syncUsers(@Body request: SyncUsersRequestDto): Call<SyncUsersResponseDto>
}
