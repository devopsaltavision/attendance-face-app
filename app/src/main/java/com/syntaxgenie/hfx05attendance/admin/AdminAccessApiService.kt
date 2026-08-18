package com.syntaxgenie.hfx05attendance.admin

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Header

interface AdminAccessApiService {
    @GET("api/admin/fingerprint-access")
    fun checkAccess(@Header("Authorization") authorization: String): Call<Void>
}
