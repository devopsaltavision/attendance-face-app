package com.syntaxgenie.hfx05attendance.admin

import com.syntaxgenie.hfx05attendance.backend.config.BackendEnvironmentConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit

class AdminAccessApiClient(private val config: BackendEnvironmentConfig) {
    fun create(): AdminAccessApiService = Retrofit.Builder()
        .baseUrl(config.normalizedBaseUrl())
        .client(OkHttpClient.Builder().build())
        .build()
        .create(AdminAccessApiService::class.java)
}
