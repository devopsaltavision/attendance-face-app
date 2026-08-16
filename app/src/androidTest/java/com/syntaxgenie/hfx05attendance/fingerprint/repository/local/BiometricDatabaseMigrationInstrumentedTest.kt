package com.syntaxgenie.hfx05attendance.fingerprint.repository.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class BiometricDatabaseMigrationInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BiometricDatabase::class.java,
    )

    @Test
    fun migration1To2PreservesRecordsAndGroupsEmployeeFingerRows() {
        val databaseName = "biometric-migration-1-2"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        val original = buildList {
            (1..5).forEach { slot -> add(legacyRow("EMP001", "right_index", slot)) }
            (1..5).forEach { slot -> add(legacyRow("EMP002", "right_index", slot)) }
        }
        helper.createDatabase(databaseName, 1).use { database ->
            original.forEach { row ->
                database.execSQL(
                    """INSERT INTO biometric_templates (
                        id, employee_id, finger_position, template_slot,
                        matcher_engine, matcher_implementation_version, template_format,
                        template_format_version, template_bytes, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                    arrayOf(
                        row.id,
                        row.employeeId,
                        row.fingerPosition,
                        row.slot,
                        row.matcherEngine,
                        row.matcherVersion,
                        row.templateFormat,
                        row.templateFormatVersion,
                        row.templateBytes,
                        row.createdAt,
                        row.updatedAt,
                    ),
                )
            }
        }

        helper.runMigrationsAndValidate(
            databaseName,
            2,
            true,
            BiometricDatabase.MIGRATION_1_2,
        ).use { database ->
            val migrated = mutableListOf<MigratedRow>()
            database.query(
                """SELECT id, enrollment_id, employee_id, finger_position, template_slot,
                    matcher_engine, matcher_implementation_version, template_format,
                    template_format_version, template_bytes, created_at, updated_at
                    FROM biometric_templates ORDER BY employee_id, template_slot""".trimIndent(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    migrated += MigratedRow(
                        cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                        cursor.getInt(4), cursor.getString(5), cursor.getString(6), cursor.getString(7),
                        cursor.getInt(8), cursor.getBlob(9), cursor.getLong(10), cursor.getLong(11),
                    )
                }
            }

            assertEquals(10, migrated.size)
            val employee1EnrollmentIds = migrated.filter { it.employeeId == "EMP001" }.map { it.enrollmentId }.toSet()
            val employee2EnrollmentIds = migrated.filter { it.employeeId == "EMP002" }.map { it.enrollmentId }.toSet()
            assertEquals(1, employee1EnrollmentIds.size)
            assertEquals(1, employee2EnrollmentIds.size)
            assertNotEquals(employee1EnrollmentIds.single(), employee2EnrollmentIds.single())
            assertEquals(true, employee1EnrollmentIds.single().matches(Regex("legacy-[0-9a-f]{64}")))

            original.zip(migrated).forEach { (before, after) ->
                assertEquals(before.id, after.id)
                assertEquals(before.employeeId, after.employeeId)
                assertEquals(before.fingerPosition, after.fingerPosition)
                assertEquals(before.slot, after.slot)
                assertEquals(before.matcherEngine, after.matcherEngine)
                assertEquals(before.matcherVersion, after.matcherVersion)
                assertEquals(before.templateFormat, after.templateFormat)
                assertEquals(before.templateFormatVersion, after.templateFormatVersion)
                assertArrayEquals(before.templateBytes, after.templateBytes)
                assertEquals(before.createdAt, after.createdAt)
                assertEquals(before.updatedAt, after.updatedAt)
            }
        }
    }

    private fun legacyRow(employeeId: String, finger: String, slot: Int): LegacyRow = LegacyRow(
        id = "$employeeId-$finger-$slot",
        employeeId = employeeId,
        fingerPosition = finger,
        slot = slot,
        matcherEngine = "sourceafis",
        matcherVersion = "3.18.1",
        templateFormat = "sourceafis-cbor",
        templateFormatVersion = 1,
        templateBytes = byteArrayOf(employeeId.last().code.toByte(), slot.toByte(), 0x5a),
        createdAt = 1_000L + slot,
        updatedAt = 2_000L + slot,
    )

    private data class LegacyRow(
        val id: String,
        val employeeId: String,
        val fingerPosition: String,
        val slot: Int,
        val matcherEngine: String,
        val matcherVersion: String,
        val templateFormat: String,
        val templateFormatVersion: Int,
        val templateBytes: ByteArray,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private data class MigratedRow(
        val id: String,
        val enrollmentId: String,
        val employeeId: String,
        val fingerPosition: String,
        val slot: Int,
        val matcherEngine: String,
        val matcherVersion: String,
        val templateFormat: String,
        val templateFormatVersion: Int,
        val templateBytes: ByteArray,
        val createdAt: Long,
        val updatedAt: Long,
    )
}
