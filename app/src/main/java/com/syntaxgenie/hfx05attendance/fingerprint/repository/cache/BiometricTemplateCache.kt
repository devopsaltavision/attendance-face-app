package com.syntaxgenie.hfx05attendance.fingerprint.repository.cache

import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRecord
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRepository
import com.syntaxgenie.hfx05attendance.fingerprint.repository.RepositoryResult

class BiometricTemplateCache(
    private val repository: BiometricRepository,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var records: List<CachedBiometricRecord> = emptyList()
    private var lastReloadDurationNanos: Long? = null

    @Synchronized
    fun reload(): RepositoryResult<BiometricCacheSnapshot> {
        val started = nanoTime()
        return when (val result = repository.getAll()) {
            is RepositoryResult.Success -> {
                records = result.value.map(CachedBiometricRecord::from)
                lastReloadDurationNanos = nanoTime() - started
                RepositoryResult.Success(snapshotLocked())
            }
            is RepositoryResult.Error -> result
        }
    }

    @Synchronized
    fun snapshot(): BiometricCacheSnapshot = snapshotLocked()

    @Synchronized
    fun clear() {
        records = emptyList()
        lastReloadDurationNanos = null
    }

    @Synchronized
    fun update(record: BiometricRecord): BiometricCacheSnapshot {
        val cached = CachedBiometricRecord.from(record)
        records = records.filterNot {
            it.recordId == record.recordId ||
                (it.employeeId == record.employeeId &&
                    it.fingerPosition == record.fingerPosition &&
                    it.templateSlot == record.templateSlot)
        }.plus(cached).sortedWith(
            compareBy(
                CachedBiometricRecord::employeeId,
                { it.fingerPosition.persistedValue },
                CachedBiometricRecord::templateSlot,
            ),
        )
        return snapshotLocked()
    }

    private fun snapshotLocked() = BiometricCacheSnapshot(records.toList(), lastReloadDurationNanos)
}
