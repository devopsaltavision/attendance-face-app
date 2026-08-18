package com.syntaxgenie.hfx05attendance.attendance

import com.google.gson.JsonParser
import com.syntaxgenie.hfx05attendance.backend.BackendApiError
import com.syntaxgenie.hfx05attendance.backend.FingerprintApiClient
import com.syntaxgenie.hfx05attendance.backend.FingerprintApiService
import com.syntaxgenie.hfx05attendance.backend.config.BackendEnvironmentConfig
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class AttendanceServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: FakeAttendanceRepository
    private var nextId = 1

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        repository = FakeAttendanceRepository()
    }

    @After fun tearDown() = runCatching { server.shutdown() }.let { Unit }

    @Test fun singleCheckInPersistsBeforeExactAuthenticatedRequestAndMarksSynced() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertNotNull(repository.get("event-1"))
                return successResponse("event-1", "CHECK_IN")
            }
        }

        val outcome = service().record(TEST_USER_ID, TEST_EMPLOYEE_ID)
        val request = server.takeRequest()
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject

        assertEquals("POST", request.method)
        assertEquals("/api/fingerprint/attendance", request.path)
        assertEquals("Bearer TEST_DEVICE_API_KEY", request.getHeader("Authorization"))
        assertFalse(request.getHeader("Authorization")!!.contains("TEST_FIREBASE_TOKEN"))
        assertEquals(setOf("attendanceEventId", "deviceId", "userId", "employeeId", "source", "deviceTimestamp"),
            json.keySet())
        assertEquals("event-1", json["attendanceEventId"].asString)
        assertEquals(TEST_DEVICE_ID, json["deviceId"].asString)
        assertEquals(TEST_USER_ID, json["userId"].asString)
        assertEquals(TEST_EMPLOYEE_ID, json["employeeId"].asString)
        assertEquals("FINGERPRINT", json["source"].asString)
        assertEquals(TEST_TIMESTAMP, json["deviceTimestamp"].asString)
        assertEquals(AttendanceRecordStatus.SYNCED, outcome.status)
        assertEquals(AttendanceSyncState.SYNCED, repository.get("event-1")!!.syncState)
        assertEquals("CHECK_IN", repository.get("event-1")!!.attendanceAction)
    }

    @Test fun singleCheckOutUsesBackendActionAndMarksSynced() {
        server.enqueue(successResponse("event-1", "CHECK_OUT"))

        val outcome = service().record(TEST_USER_ID, TEST_EMPLOYEE_ID)

        assertEquals(AttendanceRecordStatus.SYNCED, outcome.status)
        assertEquals("CHECK_OUT", outcome.event.attendanceAction)
    }

    @Test fun offlineRecordRemainsPendingWithStableUuidAndMakesNoRequest() {
        val outcome = service(networkAvailable = false).record(TEST_USER_ID, TEST_EMPLOYEE_ID)

        assertEquals(AttendanceRecordStatus.PENDING, outcome.status)
        assertEquals(BackendApiError.NETWORK_UNAVAILABLE, outcome.error)
        assertEquals("event-1", repository.get("event-1")!!.eventId)
        assertEquals(AttendanceSyncState.PENDING, repository.get("event-1")!!.syncState)
        assertEquals(0, server.requestCount)
    }

    @Test fun missingDeviceConfigurationStillPersistsWithoutRequest() {
        val missingConfig = BackendEnvironmentConfig(server.url("/").toString(), "", "Test")
        val service = AttendanceService(FingerprintApiClient(missingConfig).create(), missingConfig,
            { "" }, repository, eventIdProvider = { "event-1" }, timestampProvider = { TEST_TIMESTAMP })

        val outcome = service.record(TEST_USER_ID, TEST_EMPLOYEE_ID)

        assertEquals(BackendApiError.CONFIGURATION_REQUIRED, outcome.error)
        assertEquals(AttendanceSyncState.PENDING, repository.get("event-1")!!.syncState)
        assertEquals(0, server.requestCount)
    }

    @Test fun pendingRetryUsesSameUuidAndAlreadyRecordedMarksSynced() {
        service(networkAvailable = false).record(TEST_USER_ID, TEST_EMPLOYEE_ID)
        server.enqueue(bulkResponse("event-1", "ALREADY_RECORDED", "CHECK_IN"))

        val result = service().syncPendingAttendance()
        val request = server.takeRequest()
        val event = JsonParser.parseString(request.body.readUtf8()).asJsonObject["events"].asJsonArray.single().asJsonObject

        assertEquals("/api/fingerprint/attendance/bulk", request.path)
        assertEquals("event-1", event["attendanceEventId"].asString)
        assertEquals(1, result.synced)
        assertEquals(AttendanceSyncState.SYNCED, repository.get("event-1")!!.syncState)
    }

    @Test fun bulkMarksOnlyRecordedAndAlreadyRecordedResultsSynced() {
        repeat(3) { service(networkAvailable = false).record(TEST_USER_ID, TEST_EMPLOYEE_ID) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{
            "results":[
              {"attendanceEventId":"event-1","status":"RECORDED","attendanceRecordId":"r1","attendanceAction":"CHECK_IN"},
              {"attendanceEventId":"event-2","status":"ALREADY_RECORDED","attendanceRecordId":"r2","attendanceAction":"CHECK_OUT"},
              {"attendanceEventId":"event-3","status":"REJECTED","attendanceRecordId":null,"attendanceAction":null,"errorCode":"FPA-202"}
            ],"serverTimestamp":"2026-08-16T04:45:00.000Z"} """))

        val result = service().syncPendingAttendance()
        val request = server.takeRequest()
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject

        assertEquals("POST", request.method)
        assertEquals("/api/fingerprint/attendance/bulk", request.path)
        assertEquals("Bearer TEST_DEVICE_API_KEY", request.getHeader("Authorization"))
        assertEquals(TEST_DEVICE_ID, json["deviceId"].asString)
        assertEquals(3, json["events"].asJsonArray.size())
        assertEquals(setOf("attendanceEventId", "userId", "employeeId", "source", "deviceTimestamp"),
            json["events"].asJsonArray[0].asJsonObject.keySet())
        assertEquals("FINGERPRINT", json["events"].asJsonArray[0].asJsonObject["source"].asString)
        assertEquals(2, result.synced)
        assertEquals(1, result.remaining)
        assertEquals(AttendanceSyncState.SYNCED, repository.get("event-1")!!.syncState)
        assertEquals(AttendanceSyncState.SYNCED, repository.get("event-2")!!.syncState)
        assertEquals(AttendanceSyncState.PENDING, repository.get("event-3")!!.syncState)
    }

    @Test fun duplicateLocalUuidDoesNotInsertAnotherEvent() {
        server.enqueue(successResponse("fixed-event", "CHECK_IN"))
        server.enqueue(successResponse("fixed-event", "ALREADY_RECORDED"))
        val fixedService = service(eventIdProvider = { "fixed-event" })

        fixedService.record(TEST_USER_ID, TEST_EMPLOYEE_ID)
        fixedService.record(TEST_USER_ID, TEST_EMPLOYEE_ID)

        assertEquals(1, repository.all().size)
        assertEquals("fixed-event", repository.all().single().eventId)
    }

    @Test fun requiredHttpErrorsLeaveDurableEventPending() {
        listOf(
            Triple(400, "FPA-201", BackendApiError.INVALID_ATTENDANCE_EVENT),
            Triple(401, "FPA-401", BackendApiError.UNAUTHORIZED),
            Triple(403, "FPA-002", BackendApiError.DEVICE_DISABLED),
            Triple(404, "FPA-001", BackendApiError.DEVICE_NOT_FOUND),
            Triple(409, "FPA-202", BackendApiError.ATTENDANCE_CONFLICT),
            Triple(500, "FPA-999", BackendApiError.SERVER_FAILURE),
            Triple(502, "FPA-999", BackendApiError.SERVER_FAILURE),
            Triple(503, "FPA-999", BackendApiError.SERVER_FAILURE),
        ).forEach { (status, code, expected) ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("{\"code\":\"$code\"}"))
            val outcome = service().record(TEST_USER_ID, TEST_EMPLOYEE_ID)
            assertEquals(expected, outcome.error)
            assertEquals(AttendanceSyncState.PENDING, repository.get(outcome.event.eventId)!!.syncState)
        }
    }

    @Test fun malformedResponseLeavesEventPending() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{not-json"))

        val outcome = service().record(TEST_USER_ID, TEST_EMPLOYEE_ID)

        assertEquals(BackendApiError.INVALID_RESPONSE, outcome.error)
        assertEquals(AttendanceSyncState.PENDING, repository.get("event-1")!!.syncState)
    }

    @Test fun timeoutLeavesEventPending() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val timeoutApi = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build())
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(FingerprintApiService::class.java)

        val outcome = service(api = timeoutApi).record(TEST_USER_ID, TEST_EMPLOYEE_ID)

        assertEquals(BackendApiError.NETWORK_FAILURE, outcome.error)
        assertEquals(AttendanceSyncState.PENDING, repository.get("event-1")!!.syncState)
    }

    @Test fun failedBulkRequestLeavesEveryEventPending() {
        repeat(2) { service(networkAvailable = false).record(TEST_USER_ID, TEST_EMPLOYEE_ID) }
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"code\":\"FPA-999\"}"))

        val result = service().syncPendingAttendance()

        assertEquals(BackendApiError.SERVER_FAILURE, result.error)
        assertEquals(2, repository.pending(50).size)
    }

    private fun service(
        networkAvailable: Boolean = true,
        eventIdProvider: () -> String = { "event-${nextId++}" },
        api: FingerprintApiService = FingerprintApiClient(config()).create(),
    ) = AttendanceService(api, config(), { TEST_DEVICE_ID }, repository,
        networkAvailable = { networkAvailable }, eventIdProvider = eventIdProvider,
        timestampProvider = { TEST_TIMESTAMP })

    private fun config() = BackendEnvironmentConfig(server.url("/").toString(), "TEST_DEVICE_API_KEY", "Test")
    private fun successResponse(eventId: String, action: String) = MockResponse().setResponseCode(200).setBody("""{
        "success":true,"attendanceEventId":"$eventId","status":"${if (action == "ALREADY_RECORDED") action else "RECORDED"}",
        "attendanceRecordId":"TEST_USER_2026-08-16","attendanceAction":"${if (action == "ALREADY_RECORDED") "CHECK_IN" else action}",
        "serverTimestamp":"2026-08-16T04:10:00.000Z"} """)
    private fun bulkResponse(eventId: String, status: String, action: String) = MockResponse().setResponseCode(200).setBody("""{
        "results":[{"attendanceEventId":"$eventId","status":"$status","attendanceRecordId":"record-1","attendanceAction":"$action"}],
        "serverTimestamp":"2026-08-16T04:45:00.000Z"} """)

    private class FakeAttendanceRepository : AttendanceRepository {
        private val events = linkedMapOf<String, AttendanceEvent>()
        override fun insertPending(event: AttendanceEvent): Boolean = if (events.containsKey(event.eventId)) false
            else { events[event.eventId] = event; true }
        override fun get(eventId: String) = events[eventId]
        override fun pending(limit: Int) = events.values.filter { it.syncState == AttendanceSyncState.PENDING }
            .sortedWith(compareBy(AttendanceEvent::deviceTimestamp, AttendanceEvent::eventId)).take(limit)
        override fun markSynced(eventId: String, attendanceRecordId: String?, attendanceAction: String?, serverTimestamp: String) {
            events[eventId] = events.getValue(eventId).copy(syncState = AttendanceSyncState.SYNCED,
                attendanceRecordId = attendanceRecordId, attendanceAction = attendanceAction,
                serverTimestamp = serverTimestamp)
        }
        fun all() = events.values.toList()
    }

    private companion object {
        const val TEST_DEVICE_ID = "TEST_DEVICE_ID"
        const val TEST_USER_ID = "TEST_USER"
        const val TEST_EMPLOYEE_ID = "TEST_EMPLOYEE_ID"
        const val TEST_TIMESTAMP = "2026-08-16T09:30:12.000+05:30"
    }
}
