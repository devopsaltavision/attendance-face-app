package com.syntaxgenie.hfx05attendance.attendance.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syntaxgenie.hfx05attendance.attendance.AttendanceEvent
import com.syntaxgenie.hfx05attendance.attendance.AttendanceSyncState
import com.syntaxgenie.hfx05attendance.employee.local.EmployeeDirectoryDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAttendanceRepositoryInstrumentedTest {
    private lateinit var database: EmployeeDirectoryDatabase
    private lateinit var repository: LocalAttendanceRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            EmployeeDirectoryDatabase::class.java).allowMainThreadQueries().build()
        repository = LocalAttendanceRepository(database.attendanceDao())
    }

    @After fun tearDown() = database.close()

    @Test fun pendingInsertIsIdempotentAndAcknowledgementIsDurable() {
        val event = AttendanceEvent("event-1", "TEST_USER", "TEST_EMPLOYEE_ID",
            "2026-08-16T09:30:12.000+05:30")

        assertTrue(repository.insertPending(event))
        assertFalse(repository.insertPending(event))
        assertEquals(listOf("event-1"), repository.pending(50).map { it.eventId })

        repository.markSynced("event-1", "TEST_USER_2026-08-16", "CHECK_IN",
            "2026-08-16T04:10:00.000Z")

        assertTrue(repository.pending(50).isEmpty())
        assertEquals(AttendanceSyncState.SYNCED, repository.get("event-1")!!.syncState)
        assertEquals("CHECK_IN", repository.get("event-1")!!.attendanceAction)
    }
}
