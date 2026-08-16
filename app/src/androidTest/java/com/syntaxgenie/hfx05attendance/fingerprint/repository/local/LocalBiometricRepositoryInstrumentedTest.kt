package com.syntaxgenie.hfx05attendance.fingerprint.repository.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.FingerprintTemplate
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherMetadata
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRecord
import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition
import com.syntaxgenie.hfx05attendance.fingerprint.repository.RepositoryError
import com.syntaxgenie.hfx05attendance.fingerprint.repository.RepositoryResult
import com.syntaxgenie.hfx05attendance.fingerprint.repository.cache.BiometricTemplateCache
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalBiometricRepositoryInstrumentedTest {
    private val metadata = MatcherMetadata("sourceafis", "3.18.1", "sourceafis-cbor", 1)
    private lateinit var database: BiometricDatabase
    private lateinit var repository: LocalBiometricRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BiometricDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LocalBiometricRepository(database.biometricTemplateDao(), metadata)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndRetrievePreservesTemplateAndOwnership() {
        val original = record("record-1", "EMP001", FingerPosition.RIGHT_INDEX, 1)
        assertTrue(repository.save(original) is RepositoryResult.Success)

        val stored = success(repository.getByEmployeeAndFinger("EMP001", FingerPosition.RIGHT_INDEX)).single()
        assertEquals(original.recordId, stored.recordId)
        assertEquals(original.employeeId, stored.employeeId)
        assertEquals(original.fingerPosition, stored.fingerPosition)
        assertEquals(original.templateSlot, stored.templateSlot)
        assertEquals(metadata, stored.template.metadata)
        assertArrayEquals(original.template.bytes(), stored.template.bytes())
    }

    @Test
    fun supportsFiveTemplatesMultipleFingersAndEmployees() {
        (1..5).forEach { repository.save(record("r1-$it", "EMP001", FingerPosition.RIGHT_INDEX, it)) }
        repository.save(record("r2-1", "EMP001", FingerPosition.LEFT_INDEX, 1))
        repository.save(record("r3-1", "EMP002", FingerPosition.RIGHT_THUMB, 1))

        assertEquals(7, success(repository.getAll()).size)
        assertEquals(6, success(repository.getByEmployee("EMP001")).size)
        assertEquals(5, success(repository.getByEmployeeAndFinger("EMP001", FingerPosition.RIGHT_INDEX)).size)
    }

    @Test
    fun duplicateLogicalSlotIsRejectedWithoutReplacement() {
        repository.save(record("first", "EMP001", FingerPosition.RIGHT_INDEX, 1))
        val duplicate = repository.save(record("second", "EMP001", FingerPosition.RIGHT_INDEX, 1))

        assertEquals(RepositoryError.DUPLICATE_TEMPLATE_SLOT, (duplicate as RepositoryResult.Error).error)
        assertEquals("first", success(repository.getAll()).single().recordId)
    }

    @Test
    fun atomicEnrollmentStoresAllFiveRecords() {
        val records = (1..5).map { record("batch-$it", "EMP010", FingerPosition.LEFT_THUMB, it) }
        val result = repository.saveEnrollment(records)

        assertTrue(result is RepositoryResult.Success)
        assertEquals(5, success(repository.getByEmployeeAndFinger("EMP010", FingerPosition.LEFT_THUMB)).size)
    }

    @Test
    fun atomicEnrollmentConflictRollsBackEveryNewRecord() {
        repository.save(record("occupied", "EMP010", FingerPosition.LEFT_THUMB, 3))
        val records = (1..5).map { record("conflict-$it", "EMP010", FingerPosition.LEFT_THUMB, it) }

        val result = repository.saveEnrollment(records)

        assertEquals(RepositoryError.DUPLICATE_TEMPLATE_SLOT, (result as RepositoryResult.Error).error)
        val remaining = success(repository.getByEmployeeAndFinger("EMP010", FingerPosition.LEFT_THUMB))
        assertEquals(listOf("occupied"), remaining.map { it.recordId })
    }

    @Test
    fun atomicEnrollmentConflictLeavesOtherEmployeeUntouched() {
        repository.save(record("other", "EMP999", FingerPosition.RIGHT_INDEX, 1))
        repository.save(record("occupied", "EMP010", FingerPosition.LEFT_THUMB, 5))
        val records = (1..5).map { record("conflict-$it", "EMP010", FingerPosition.LEFT_THUMB, it) }

        repository.saveEnrollment(records)

        assertEquals(listOf("other"), success(repository.getByEmployee("EMP999")).map { it.recordId })
        assertEquals(listOf("occupied"), success(repository.getByEmployee("EMP010")).map { it.recordId })
    }

    @Test
    fun atomicTransactionRollsBackEarlierInsertsWhenLaterPrimaryKeyConflicts() {
        repository.save(record("conflict-3", "EMP999", FingerPosition.RIGHT_INDEX, 1))
        val records = (1..5).map { record("conflict-$it", "EMP010", FingerPosition.LEFT_THUMB, it) }

        val result = repository.saveEnrollment(records)

        assertEquals(RepositoryError.TEMPLATE_SAVE_FAILED, (result as RepositoryResult.Error).error)
        assertTrue(success(repository.getByEmployee("EMP010")).isEmpty())
        assertEquals(listOf("conflict-3"), success(repository.getByEmployee("EMP999")).map { it.recordId })
    }

    @Test
    fun incompatibleMetadataIsRejectedOnSaveAndRead() {
        val incompatible = MatcherMetadata("sourceafis", "future", "sourceafis-cbor", 1)
        val saveResult = repository.save(
            BiometricRecord(
                "bad", "EMP001", FingerPosition.RIGHT_INDEX, 1,
                FingerprintTemplate(incompatible, byteArrayOf(1)), 1,
            ),
        )
        assertEquals(RepositoryError.INCOMPATIBLE_TEMPLATE, (saveResult as RepositoryResult.Error).error)

        database.biometricTemplateDao().insert(
            BiometricTemplateEntity(
                "stored-bad", "EMP001", "right_index", 1,
                "sourceafis", "future", "sourceafis-cbor", 1,
                byteArrayOf(1), 1, 1,
            ),
        )
        val readResult = repository.getAll()
        assertEquals(RepositoryError.INCOMPATIBLE_TEMPLATE, (readResult as RepositoryResult.Error).error)
        assertTrue(readResult.diagnosticDetails?.contains("future") == true)
        assertTrue(readResult.diagnosticDetails?.contains("[1]") != true)
    }

    @Test
    fun deletesAreScopedAndLeaveOtherEmployeesUntouched() {
        repository.save(record("e1-r", "EMP001", FingerPosition.RIGHT_INDEX, 1))
        repository.save(record("e1-l", "EMP001", FingerPosition.LEFT_INDEX, 1))
        repository.save(record("e2-r", "EMP002", FingerPosition.RIGHT_INDEX, 1))

        assertEquals(1, success(repository.deleteByEmployeeAndFinger("EMP001", FingerPosition.RIGHT_INDEX)))
        assertEquals(1, success(repository.getByEmployee("EMP001")).size)
        assertEquals(1, success(repository.deleteByEmployee("EMP001")))
        assertEquals(listOf("EMP002"), success(repository.getAll()).map { it.employeeId })
    }

    @Test
    fun persistsAndLoads750RecordsIntoCacheWithOneLoadAll() {
        val insertStarted = System.nanoTime()
        (1..150).forEach { employee ->
            (1..5).forEach { slot ->
                val result = repository.save(
                    record("r-$employee-$slot", "EMP%03d".format(employee), FingerPosition.RIGHT_INDEX, slot),
                )
                assertTrue(result is RepositoryResult.Success)
            }
        }
        val insertDuration = System.nanoTime() - insertStarted
        val cache = BiometricTemplateCache(repository)
        val cacheResult = cache.reload() as RepositoryResult.Success

        assertEquals(750, cacheResult.value.recordCount)
        assertEquals(750, cacheResult.value.records.map { it.recordId }.toSet().size)
        assertEquals(150, cacheResult.value.records.map { it.employeeId }.toSet().size)
        assertEquals(setOf(1, 2, 3, 4, 5), cacheResult.value.records.map { it.templateSlot }.toSet())
        println("Room insert 750 records: $insertDuration ns")
        println("Room load/map/cache rebuild 750 records: ${cacheResult.value.lastReloadDurationNanos} ns")
    }

    private fun record(
        id: String,
        employeeId: String,
        position: FingerPosition,
        slot: Int,
    ) = BiometricRecord(
        id, employeeId, position, slot,
        FingerprintTemplate(metadata, byteArrayOf(id.hashCode().toByte(), slot.toByte())),
        createdAtEpochMillis = 1,
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> success(result: RepositoryResult<T>): T =
        (result as RepositoryResult.Success<T>).value
}
