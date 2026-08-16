package com.syntaxgenie.hfx05attendance.backend

import com.syntaxgenie.hfx05attendance.backend.config.BackendEnvironmentConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class FingerprintApiClient(private val config: BackendEnvironmentConfig) {
    fun create(): FingerprintApiService {
        val interceptor = Interceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("Authorization", "Bearer ${config.apiKey}")
                .build())
        }
        return Retrofit.Builder()
            .baseUrl(config.normalizedBaseUrl())
            .client(OkHttpClient.Builder().addInterceptor(interceptor).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build().create(FingerprintApiService::class.java)
    }
}
