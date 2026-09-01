package com.syntaxgenie.hfx05attendance.fingerprint.backup

import com.syntaxgenie.hfx05attendance.fingerprint.matcher.FingerprintTemplate
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherMetadata
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRecord
import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintSyncPlannerTest {
    @Test
    fun `local-only enrollments are uploaded and remote-only enrollments are added locally`() {
        val local = enrollment("local", "EMP-1", 1)
        val remote = enrollment("remote", "EMP-2", 2)

        val plan = FingerprintSyncPlanner.plan(local, remote)

        assertEquals(setOf("local", "remote"), plan.remoteMerged.map { it.enrollmentId }.toSet())
        assertEquals(remote, plan.localAdditions)
        assertEquals(1, plan.localOnlyEnrollmentCount)
    }

    @Test
    fun `identical shared enrollment is retained without a local addition`() {
        val enrollment = enrollment("shared", "EMP-1", 3)

        val plan = FingerprintSyncPlanner.plan(enrollment, enrollment.map { it.copy() })

        assertEquals(enrollment, plan.remoteMerged)
        assertTrue(plan.localAdditions.isEmpty())
        assertEquals(0, plan.localOnlyEnrollmentCount)
    }

    @Test(expected = FingerprintSynchronizationConflict::class)
    fun `different template bytes under the same enrollment id conflict`() {
        val local = enrollment("shared", "EMP-1", 4)
        val remote = enrollment("shared", "EMP-1", 4).toMutableList().apply {
            this[2] = record("shared", "EMP-1", 3, 99)
        }

        FingerprintSyncPlanner.plan(local, remote)
    }

    private fun enrollment(enrollmentId: String, employeeId: String, seed: Int) =
        (1..5).map { slot -> record(enrollmentId, employeeId, slot, seed) }

    private fun record(enrollmentId: String, employeeId: String, slot: Int, seed: Int) = BiometricRecord(
        recordId = "$enrollmentId-$slot",
        enrollmentId = enrollmentId,
        employeeId = employeeId,
        fingerPosition = FingerPosition.RIGHT_INDEX,
        templateSlot = slot,
        template = FingerprintTemplate(METADATA, byteArrayOf(seed.toByte(), slot.toByte())),
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 2_000L,
    )

    private companion object {
        val METADATA = MatcherMetadata("sourceafis", "3.18.1", "sourceafis-cbor", 1)
    }
}
