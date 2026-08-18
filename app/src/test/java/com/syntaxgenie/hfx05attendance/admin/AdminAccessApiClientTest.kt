package com.syntaxgenie.hfx05attendance.admin

import com.syntaxgenie.hfx05attendance.backend.config.BackendEnvironmentConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class AdminAccessApiClientTest {
    @Test fun sendsFirebaseBearerWithoutUsingFingerprintApiKey() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        try {
            val config = BackendEnvironmentConfig(
                baseUrl = server.url("/").toString(),
                apiKey = "DEVICE_API_KEY_MUST_NOT_BE_SENT",
                environmentName = "Test",
            )
            val response = AdminAccessApiClient(config).create()
                .checkAccess("Bearer FIREBASE_ID_TOKEN")
                .execute()
            val request = server.takeRequest()

            assertEquals(200, response.code())
            assertEquals("/api/admin/fingerprint-access", request.path)
            assertEquals("Bearer FIREBASE_ID_TOKEN", request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}
