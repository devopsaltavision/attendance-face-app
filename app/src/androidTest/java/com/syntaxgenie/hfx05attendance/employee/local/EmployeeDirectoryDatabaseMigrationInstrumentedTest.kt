package com.syntaxgenie.hfx05attendance.employee.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EmployeeDirectoryDatabaseMigrationInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EmployeeDirectoryDatabase::class.java,
    )

    @Test fun migration1To2PreservesEmployeesAndCreatesAttendanceQueue() {
        val databaseName = "employee-directory-migration-1-2"
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 1).use { database ->
            database.execSQL("""INSERT INTO employees (user_id, employee_id, display_name, active,
                fingerprint_enrolled, fingerprint_enrollment_id, updated_at)
                VALUES ('TEST_USER', 'TEST_EMPLOYEE_ID', 'Test Employee', 1, 1, NULL, 'updated')""")
        }

        helper.runMigrationsAndValidate(databaseName, 2, true,
            EmployeeDirectoryDatabase.MIGRATION_1_2).use { database ->
            database.query("SELECT COUNT(*) FROM employees").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            database.execSQL("""INSERT INTO attendance_events (event_id, user_id, employee_id,
                device_timestamp, sync_state) VALUES ('event-1', 'TEST_USER', 'TEST_EMPLOYEE_ID',
                '2026-08-16T09:30:12.000+05:30', 'PENDING')""")
            database.query("SELECT sync_state FROM attendance_events WHERE event_id = 'event-1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("PENDING", cursor.getString(0))
            }
        }
    }
}
