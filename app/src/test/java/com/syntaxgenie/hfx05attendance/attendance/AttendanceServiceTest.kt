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
        assertEquals(TEST_DEVICE_ID, repository.get("event-1")!!.deviceId)
        assertEquals("FINGERPRINT", repository.get("event-1")!!.source)
        assertEquals(AttendanceSyncState.PENDING, repository.get("event-1")!!.syncState)
        assertEquals(0, server.requestCount)
    }

    @Test fun networkPendingRecordSchedulesExistingQueueSync() {
        var schedules = 0

        service(networkAvailable = false, pendingSyncScheduler = { schedules++ })
            .record(TEST_USER_ID, TEST_EMPLOYEE_ID, "CHECK_IN", "FACE")

        assertEquals(1, schedules)
        assertEquals(AttendanceSyncState.PENDING, repository.get("event-1")!!.syncState)
    }

    @Test fun faceCheckInSendsExplicitActionAndBiometricType() {
        server.enqueue(successResponse("event-1", "CHECK_IN"))
        service().record(TEST_USER_ID, TEST_EMPLOYEE_ID, "CHECK_IN", "FACE", "FACE")
        val json = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("CHECK_IN", json["action"].asString)
        assertEquals("FACE", json["biometricType"].asString)
        assertEquals("FACE", json["source"].asString)
    }

    @Test fun faceCheckOutSendsExplicitActionAndBiometricType() {
        server.enqueue(successResponse("event-1", "CHECK_OUT"))
        service().record(TEST_USER_ID, TEST_EMPLOYEE_ID, "CHECK_OUT", "FACE", "FACE")
        val json = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("CHECK_OUT", json["action"].asString)
        assertEquals("FACE", json["biometricType"].asString)
    }

    @Test fun fingerprintRequestOmitsOptionalFaceFields() {
        server.enqueue(successResponse("event-1", "CHECK_IN"))
        service().record(TEST_USER_ID, TEST_EMPLOYEE_ID)
        val json = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertFalse(json.has("action")); assertFalse(json.has("biometricType"))
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

    @Test fun pendingFaceRetryRetainsOriginalTimestampActionAndBiometricType() {
        service(networkAvailable = false).record(TEST_USER_ID, TEST_EMPLOYEE_ID, "CHECK_OUT", "FACE", "FACE")
        server.enqueue(bulkResponse("event-1", "RECORDED", "CHECK_OUT"))

        service().syncPendingAttendance()
        val event = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            .getAsJsonArray("events").single().asJsonObject

        assertEquals("event-1", event["attendanceEventId"].asString)
        assertEquals(TEST_TIMESTAMP, event["deviceTimestamp"].asString)
        assertEquals("CHECK_OUT", event["action"].asString)
        assertEquals("FACE", event["biometricType"].asString)
        assertEquals("FACE", event["source"].asString)
    }

    @Test fun pendingRetryUsesOriginalDeviceIdAfterConfigurationChanges() {
        service(networkAvailable = false, deviceId = "DEVICE_A").record(TEST_USER_ID, TEST_EMPLOYEE_ID)
        server.enqueue(bulkResponse("event-1", "RECORDED", "CHECK_IN"))

        service(deviceId = "DEVICE_B").syncPendingAttendance()
        val json = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject

        assertEquals("DEVICE_A", json["deviceId"].asString)
        assertEquals("DEVICE_A", repository.get("event-1")!!.deviceId)
    }

    @Test fun bulkMatchesOutOfOrderTerminalResultsByEventIdAndContinuesPastTerminalEvents() {
        repeat(4) { service(networkAvailable = false).record(TEST_USER_ID, TEST_EMPLOYEE_ID) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{
            "results":[
              {"attendanceEventId":"event-3","status":"REJECTED","attendanceRecordId":null,"attendanceAction":null,"errorCode":"FPA-202"},
              {"attendanceEventId":"event-1","status":"RECORDED","attendanceRecordId":"r1","attendanceAction":"CHECK_IN"},
              {"attendanceEventId":"event-4","status":"DEBOUNCED","attendanceRecordId":null,"attendanceAction":null,"errorCode":"FPA-202"},
              {"attendanceEventId":"event-2","status":"ALREADY_RECORDED","attendanceRecordId":"r2","attendanceAction":"CHECK_OUT"}
            ],"serverTimestamp":"2026-08-16T04:45:00.000Z"} """))

        val result = service().syncPendingAttendance()
        val request = server.takeRequest()
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject

        assertEquals("POST", request.method)
        assertEquals("/api/fingerprint/attendance/bulk", request.path)
        assertEquals("Bearer TEST_DEVICE_API_KEY", request.getHeader("Authorization"))
        assertEquals(TEST_DEVICE_ID, json["deviceId"].asString)
        assertEquals(4, json["events"].asJsonArray.size())
        assertEquals(listOf("event-1", "event-2", "event-3", "event-4"), json["events"].asJsonArray
            .map { it.asJsonObject["attendanceEventId"].asString })
        assertEquals(setOf("attendanceEventId", "userId", "employeeId", "source", "deviceTimestamp"),
            json["events"].asJsonArray[0].asJsonObject.keySet())
        assertEquals("FINGERPRINT", json["events"].asJsonArray[0].asJsonObject["source"].asString)
        assertEquals(2, result.synced)
        assertEquals(0, result.remaining)
        assertEquals(AttendanceSyncState.SYNCED, repository.get("event-1")!!.syncState)
        assertEquals(AttendanceSyncState.SYNCED, repository.get("event-2")!!.syncState)
        assertEquals(AttendanceSyncState.REJECTED, repository.get("event-3")!!.syncState)
        assertEquals("FPA-202", repository.get("event-3")!!.rejectionReason)
        assertEquals(AttendanceSyncState.DEBOUNCED, repository.get("event-4")!!.syncState)
        assertEquals("FPA-202", repository.get("event-4")!!.rejectionReason)
        assertTrue(repository.pending(50).isEmpty())
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

    @Test fun partialBulkResponseLeavesEveryEventPendingForRetry() {
        repeat(2) { service(networkAvailable = false).record(TEST_USER_ID, TEST_EMPLOYEE_ID) }
        server.enqueue(bulkResponse("event-1", "RECORDED", "CHECK_IN"))

        val result = service().syncPendingAttendance()

        assertEquals(BackendApiError.INVALID_RESPONSE, result.error)
        assertEquals(listOf("event-1", "event-2"), repository.pending(50).map { it.eventId })
    }

    @Test fun duplicateOrUnknownBulkResultIdsLeaveEveryEventPendingForRetry() {
        repeat(2) { service(networkAvailable = false).record(TEST_USER_ID, TEST_EMPLOYEE_ID) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{
            "results":[
              {"attendanceEventId":"event-1","status":"RECORDED","attendanceRecordId":"r1","attendanceAction":"CHECK_IN"},
              {"attendanceEventId":"event-1","status":"RECORDED","attendanceRecordId":"r1","attendanceAction":"CHECK_IN"}
            ],"serverTimestamp":"2026-08-16T04:45:00.000Z"} """))

        val duplicateResult = service().syncPendingAttendance()
        assertEquals(BackendApiError.INVALID_RESPONSE, duplicateResult.error)
        assertEquals(listOf("event-1", "event-2"), repository.pending(50).map { it.eventId })

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{
            "results":[
              {"attendanceEventId":"event-1","status":"RECORDED","attendanceRecordId":"r1","attendanceAction":"CHECK_IN"},
              {"attendanceEventId":"unexpected","status":"RECORDED","attendanceRecordId":"r2","attendanceAction":"CHECK_IN"}
            ],"serverTimestamp":"2026-08-16T04:45:00.000Z"} """))
        val unknownResult = service().syncPendingAttendance()
        assertEquals(BackendApiError.INVALID_RESPONSE, unknownResult.error)
        assertEquals(listOf("event-1", "event-2"), repository.pending(50).map { it.eventId })
    }

    @Test fun unsupportedBulkStatusLeavesEventPendingForRetry() {
        service(networkAvailable = false).record(TEST_USER_ID, TEST_EMPLOYEE_ID)
        server.enqueue(bulkResponse("event-1", "UNSUPPORTED", "CHECK_IN"))

        val result = service().syncPendingAttendance()

        assertEquals(BackendApiError.INVALID_RESPONSE, result.error)
        assertEquals(AttendanceSyncState.PENDING, repository.get("event-1")!!.syncState)
    }

    @Test fun retryableBulkFailedStatusKeepsOnlyThatEventPending() {
        repeat(2) { service(networkAvailable = false).record(TEST_USER_ID, TEST_EMPLOYEE_ID) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{
            "results":[
              {"attendanceEventId":"event-1","status":"RECORDED","attendanceRecordId":"r1","attendanceAction":"CHECK_IN"},
              {"attendanceEventId":"event-2","status":"FAILED","attendanceRecordId":null,"attendanceAction":null}
            ],"serverTimestamp":"2026-08-16T04:45:00.000Z"} """))

        val first = service().syncPendingAttendance()

        assertEquals(BackendApiError.SERVER_FAILURE, first.error)
        assertEquals(AttendanceSyncState.SYNCED, repository.get("event-1")!!.syncState)
        assertEquals(AttendanceSyncState.PENDING, repository.get("event-2")!!.syncState)
        server.enqueue(bulkResponse("event-2", "RECORDED", "CHECK_OUT"))
        service().syncPendingAttendance()
        assertEquals(AttendanceSyncState.SYNCED, repository.get("event-2")!!.syncState)
    }

    @Test fun immediateDebouncedAttendanceIsRetainedAsTerminalHistory() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{
            "success":false,"attendanceEventId":"event-1","status":"DEBOUNCED",
            "attendanceRecordId":null,"attendanceAction":null,"message":"Attendance is too close to the prior event."
        }"""))

        val outcome = service().record(TEST_USER_ID, TEST_EMPLOYEE_ID)

        assertEquals(AttendanceRecordStatus.DUPLICATE_IGNORED, outcome.status)
        assertEquals(AttendanceSyncState.DEBOUNCED, repository.get("event-1")!!.syncState)
        assertTrue(repository.pending(50).isEmpty())
    }

    @Test fun immediateBusinessRejectionIsRetainedAsTerminalHistory() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{
            "success":false,"attendanceEventId":"event-1","status":"REJECTED",
            "attendanceRecordId":null,"attendanceAction":null,"message":"No open session found to check out."
        }"""))

        val outcome = service().record(TEST_USER_ID, TEST_EMPLOYEE_ID)

        assertEquals(AttendanceRecordStatus.REJECTED, outcome.status)
        assertEquals(AttendanceSyncState.REJECTED, repository.get("event-1")!!.syncState)
        assertEquals("No open session found to check out.", repository.get("event-1")!!.rejectionReason)
        assertTrue(repository.pending(50).isEmpty())
    }

    @Test fun retryableServerFailureSchedulesPendingEvent() {
        var schedules = 0
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"code\":\"FPA-999\"}"))

        val outcome = service(pendingSyncScheduler = { schedules++ }).record(TEST_USER_ID, TEST_EMPLOYEE_ID)

        assertEquals(AttendanceRecordStatus.PENDING, outcome.status)
        assertEquals(BackendApiError.SERVER_FAILURE, outcome.error)
        assertEquals(1, schedules)
        assertEquals(AttendanceSyncState.PENDING, repository.get("event-1")!!.syncState)
    }

    private fun service(
        networkAvailable: Boolean = true,
        eventIdProvider: () -> String = { "event-${nextId++}" },
        api: FingerprintApiService = FingerprintApiClient(config()).create(),
        pendingSyncScheduler: (() -> Unit)? = null,
        deviceId: String = TEST_DEVICE_ID,
    ) = AttendanceService(api, config(), { deviceId }, repository,
        networkAvailable = { networkAvailable }, eventIdProvider = eventIdProvider,
        timestampProvider = { TEST_TIMESTAMP }, pendingSyncScheduler = pendingSyncScheduler)

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
        override fun markRejected(eventId: String, rejectionReason: String?) {
            events[eventId] = events.getValue(eventId).copy(syncState = AttendanceSyncState.REJECTED,
                rejectionReason = rejectionReason)
        }
        override fun markDebounced(eventId: String, reason: String?) {
            events[eventId] = events.getValue(eventId).copy(syncState = AttendanceSyncState.DEBOUNCED,
                rejectionReason = reason)
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
