package com.syntaxgenie.hfx05attendance.employee

import com.syntaxgenie.hfx05attendance.backend.BackendApiError
import com.syntaxgenie.hfx05attendance.backend.BackendResult
import com.syntaxgenie.hfx05attendance.backend.FingerprintApiClient
import com.syntaxgenie.hfx05attendance.backend.config.BackendEnvironmentConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmployeeSyncServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: FakeDirectory

    @Before fun setUp() { server = MockWebServer(); server.start(); directory = FakeDirectory() }
    @After fun tearDown() { runCatching { server.shutdown() } }

    @Test fun fullSyncSendsExactAuthenticatedRequestAndMapsResponse() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson()))
        val result = service().syncFull()
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/fingerprint/sync-users", request.path)
        assertEquals("Bearer TEST_KEY", request.getHeader("Authorization"))
        assertEquals("{\"deviceId\":\"HF-X05-001\"}", request.body.readUtf8())
        val saved = directory.records.single()
        assertEquals("EPF001", saved.userId)
        assertEquals("EMP001", saved.employeeId)
        assertEquals("John Silva", saved.displayName)
        assertFalse(saved.fingerprintEnrolled)
        assertNull(saved.fingerprintEnrollmentId)
        assertTrue(result is BackendResult.Success)
    }

    @Test fun incrementalSyncUsesSavedCursorAndRetainsOtherUsers() {
        directory.records += employee("OLD", "OLD")
        directory.state = EmployeeSyncState("2026-08-16T04:10:00.000Z", "earlier")
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson()))
        service().syncIncremental()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"updatedAfter\":\"2026-08-16T04:10:00.000Z\""))
        assertTrue(directory.records.any { it.userId == "OLD" })
        assertTrue(directory.records.any { it.userId == "EPF001" })
    }

    @Test fun fullSyncReplacesExistingDirectoryAndRetainsInactiveUsers() {
        directory.records += employee("OLD", "OLD")
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson(active = false)))
        service().syncFull()
        assertEquals(listOf("EPF001"), directory.records.map { it.userId })
        assertFalse(directory.records.single().active)
    }

    @Test fun cursorDoesNotAdvanceWhenLocalTransactionFails() {
        directory.state = EmployeeSyncState("old-cursor", "old-time")
        directory.failWrites = true
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson()))
        val result = service().syncFull()
        assertTrue(result is BackendResult.Error)
        assertEquals("old-cursor", directory.state.nextUpdatedAfter)
    }

    @Test fun searchMatchesNameEmployeeIdAndUserIdCaseInsensitively() {
        directory.records += employee("EPF001", "EMP001", "John Silva")
        assertEquals(1, directory.search("john").size)
        assertEquals(1, directory.search("emp001").size)
        assertEquals(1, directory.search("epf001").size)
    }

    @Test fun missingKeyAndMissingDeviceIdDoNotIssueRequests() {
        val missingKey = service(key = "").syncFull() as BackendResult.Error
        val missingDevice = service(deviceId = "").syncFull() as BackendResult.Error
        assertEquals(BackendApiError.CONFIGURATION_REQUIRED, missingKey.error)
        assertEquals(BackendApiError.CONFIGURATION_REQUIRED, missingDevice.error)
        assertEquals(0, server.requestCount)
    }

    @Test fun mapsKnownHttpErrors() {
        listOf(
            Triple(401, "FPA-401", BackendApiError.UNAUTHORIZED),
            Triple(403, "FPA-002", BackendApiError.DEVICE_DISABLED),
            Triple(404, "FPA-001", BackendApiError.DEVICE_NOT_FOUND),
            Triple(400, "FPA-301", BackendApiError.INVALID_SYNC_CURSOR),
            Triple(500, "FPA-500", BackendApiError.SERVER_FAILURE),
            Triple(503, "FPA-503", BackendApiError.SERVER_FAILURE),
        ).forEach { (status, code, expected) ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("{\"code\":\"$code\"}"))
            assertEquals(expected, (service().syncFull() as BackendResult.Error).error)
        }
    }

    @Test fun ioExceptionMapsToNetworkFailure() {
        val api = FingerprintApiClient(config()).create()
        server.shutdown()
        val result = EmployeeSyncService(api, config(), { "HF-X05-001" }, directory).syncFull()
        assertEquals(BackendApiError.NETWORK_FAILURE, (result as BackendResult.Error).error)
    }

    @Test fun unavailableNetworkDoesNotIssueRequest() {
        val config = config()
        val result = EmployeeSyncService(FingerprintApiClient(config).create(), config,
            { "HF-X05-001" }, directory, networkAvailable = { false }).syncFull()
        assertEquals(BackendApiError.NETWORK_UNAVAILABLE, (result as BackendResult.Error).error)
        assertEquals(0, server.requestCount)
    }

    private fun service(key: String = "TEST_KEY", deviceId: String = "HF-X05-001"): EmployeeSyncService {
        val config = config(key)
        return EmployeeSyncService(FingerprintApiClient(config).create(), config, { deviceId }, directory,
            networkAvailable = { true }, now = { "sync-time" })
    }
    private fun config(key: String = "TEST_KEY") = BackendEnvironmentConfig(server.url("/").toString(), key, "Test")
    private fun employee(user: String, employee: String, name: String = "Name") = EmployeeRecord(user, employee, name, true, false, null, "updated")
    private fun responseJson(active: Boolean = true) = """{"users":[{"userId":"EPF001","employeeId":"EMP001","displayName":"John Silva","active":$active,"fingerprintEnrolled":false,"fingerprintEnrollmentId":null,"updatedAt":"2026-08-16T04:05:12.000Z"}],"serverTime":"2026-08-16T04:10:00.000Z","nextUpdatedAfter":"2026-08-16T04:10:00.000Z"}"""

    private class FakeDirectory : EmployeeDirectory {
        val records = mutableListOf<EmployeeRecord>()
        var state = EmployeeSyncState(null, null)
        var failWrites = false
        override fun getAll() = records.toList()
        override fun search(query: String) = records.filter {
            it.displayName.contains(query, true) || it.employeeId.contains(query, true) || it.userId.contains(query, true)
        }
        override fun syncState() = state
        override fun replaceAll(records: List<EmployeeRecord>, state: EmployeeSyncState) {
            if (failWrites) throw IllegalStateException("write failed")
            this.records.clear(); this.records.addAll(records); this.state = state
        }
        override fun upsertAll(records: List<EmployeeRecord>, state: EmployeeSyncState) {
            if (failWrites) throw IllegalStateException("write failed")
            records.forEach { record -> this.records.removeAll { it.userId == record.userId }; this.records += record }
            this.state = state
        }
    }
}
