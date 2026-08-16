package com.syntaxgenie.hfx05attendance.employee.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syntaxgenie.hfx05attendance.employee.EmployeeRecord
import com.syntaxgenie.hfx05attendance.employee.EmployeeSyncState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalEmployeeDirectoryInstrumentedTest {
    private lateinit var database: EmployeeDirectoryDatabase
    private lateinit var directory: LocalEmployeeDirectory

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            EmployeeDirectoryDatabase::class.java).allowMainThreadQueries().build()
        directory = LocalEmployeeDirectory(database.employeeDao())
    }
    @After fun tearDown() = database.close()

    @Test fun fullReplaceIncrementalUpsertSearchAndStateRoundTrip() {
        directory.replaceAll(listOf(employee("EPF-OLD", "EMP-OLD", "Old User")),
            EmployeeSyncState("cursor-1", "time-1"))
        directory.replaceAll(listOf(employee("EPF001", "EMP001", "John Silva", active = false)),
            EmployeeSyncState("cursor-2", "time-2"))
        assertEquals(listOf("EPF001"), directory.getAll().map { it.userId })
        assertFalse(directory.getAll().single().active)
        assertEquals(1, directory.search("john").size)
        assertEquals(1, directory.search("emp001").size)
        assertEquals(1, directory.search("epf001").size)

        directory.upsertAll(listOf(employee("EPF002", "EMP002", "Nimal Perera")),
            EmployeeSyncState("cursor-3", "time-3"))
        assertEquals(setOf("EPF001", "EPF002"), directory.getAll().map { it.userId }.toSet())
        assertEquals("cursor-3", directory.syncState().nextUpdatedAfter)
        assertEquals("time-3", directory.syncState().lastSuccessfulSyncAt)
    }

    private fun employee(userId: String, employeeId: String, name: String, active: Boolean = true) =
        EmployeeRecord(userId, employeeId, name, active, false, null, "2026-08-16T04:05:12.000Z")
}
