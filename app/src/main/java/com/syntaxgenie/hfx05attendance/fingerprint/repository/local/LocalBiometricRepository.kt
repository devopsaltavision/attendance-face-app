package com.syntaxgenie.hfx05attendance.fingerprint.repository.local

import android.database.sqlite.SQLiteConstraintException
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherMetadata
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRecord
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRepository
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRepositorySnapshot
import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition
import com.syntaxgenie.hfx05attendance.fingerprint.repository.RepositoryError
import com.syntaxgenie.hfx05attendance.fingerprint.repository.RepositoryResult

class LocalBiometricRepository(
    private val dao: BiometricTemplateDao,
    private val activeMatcherMetadata: MatcherMetadata,
    private val nanoTime: () -> Long = System::nanoTime,
) : BiometricRepository {
    @Volatile
    private var snapshot = BiometricRepositorySnapshot()

    override fun save(record: BiometricRecord): RepositoryResult<BiometricRecord> = timed("save") {
        if (record.template.metadata != activeMatcherMetadata) {
            return@timed incompatible(record.template.metadata)
        }
        try {
            if (
                dao.countLogicalSlot(
                    record.employeeId,
                    record.fingerPosition.persistedValue,
                    record.templateSlot,
                ) > 0
            ) {
                RepositoryResult.Error(RepositoryError.DUPLICATE_TEMPLATE_SLOT)
            } else {
                dao.insert(BiometricRecordMapper.toEntity(record))
                RepositoryResult.Success(record)
            }
        } catch (error: SQLiteConstraintException) {
            if (
                dao.countLogicalSlot(
                    record.employeeId,
                    record.fingerPosition.persistedValue,
                    record.templateSlot,
                ) > 0
            ) {
                RepositoryResult.Error(
                    RepositoryError.DUPLICATE_TEMPLATE_SLOT,
                    diagnosticDetails = "The logical biometric template slot already exists.",
                    cause = error,
                )
            } else {
                failure(RepositoryError.TEMPLATE_SAVE_FAILED, "A database constraint rejected the record.", error)
            }
        } catch (error: Exception) {
            failure(RepositoryError.TEMPLATE_SAVE_FAILED, "Room insert failed.", error)
        }
    }

    override fun getByEmployee(employeeId: String): RepositoryResult<List<BiometricRecord>> =
        read("getByEmployee") { dao.getByEmployee(employeeId) }

    override fun getByEmployeeAndFinger(
        employeeId: String,
        fingerPosition: FingerPosition,
    ): RepositoryResult<List<BiometricRecord>> = read("getByEmployeeAndFinger") {
        dao.getByEmployeeAndFinger(employeeId, fingerPosition.persistedValue)
    }

    override fun getAll(): RepositoryResult<List<BiometricRecord>> = read("getAll", dao::getAll)

    override fun deleteByEmployeeAndFinger(
        employeeId: String,
        fingerPosition: FingerPosition,
    ): RepositoryResult<Int> = delete("deleteByEmployeeAndFinger") {
        dao.deleteByEmployeeAndFinger(employeeId, fingerPosition.persistedValue)
    }

    override fun deleteByEmployee(employeeId: String): RepositoryResult<Int> =
        delete("deleteByEmployee") { dao.deleteByEmployee(employeeId) }

    override fun diagnostics(): BiometricRepositorySnapshot = snapshot

    private fun read(
        operation: String,
        query: () -> List<BiometricTemplateEntity>,
    ): RepositoryResult<List<BiometricRecord>> = timed(operation) {
        try {
            val entities = query()
            val incompatible = entities.firstOrNull { BiometricRecordMapper.metadata(it) != activeMatcherMetadata }
            if (incompatible != null) {
                incompatible(BiometricRecordMapper.metadata(incompatible))
            } else {
                RepositoryResult.Success(entities.map(BiometricRecordMapper::toDomain))
            }
        } catch (error: Exception) {
            failure(RepositoryError.TEMPLATE_READ_FAILED, "Room query or record mapping failed.", error)
        }
    }

    private fun delete(operation: String, action: () -> Int): RepositoryResult<Int> = timed(operation) {
        try {
            RepositoryResult.Success(action())
        } catch (error: Exception) {
            failure(RepositoryError.TEMPLATE_DELETE_FAILED, "Room delete failed.", error)
        }
    }

    private fun incompatible(metadata: MatcherMetadata): RepositoryResult.Error = RepositoryResult.Error(
        RepositoryError.INCOMPATIBLE_TEMPLATE,
        diagnosticDetails = buildString {
            append("Stored matcher metadata is incompatible with the active matcher. Stored engine=")
            append(metadata.engine)
            append(", implementationVersion=")
            append(metadata.implementationVersion)
            append(", templateFormat=")
            append(metadata.templateFormat)
            append(", templateFormatVersion=")
            append(metadata.templateFormatVersion)
        },
    )

    private fun failure(
        error: RepositoryError,
        details: String,
        cause: Exception,
    ): RepositoryResult.Error = RepositoryResult.Error(error, details, cause)

    private fun <T> timed(operation: String, action: () -> RepositoryResult<T>): RepositoryResult<T> {
        val started = nanoTime()
        val result = action()
        val count = when (result) {
            is RepositoryResult.Success<*> -> when (val value = result.value) {
                is Collection<*> -> value.size
                is Int -> value
                else -> 1
            }
            is RepositoryResult.Error -> 0
        }
        snapshot = BiometricRepositorySnapshot(operation, count, nanoTime() - started)
        return result
    }
}
