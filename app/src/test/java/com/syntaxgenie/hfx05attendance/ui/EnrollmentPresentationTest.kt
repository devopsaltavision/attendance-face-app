package com.syntaxgenie.hfx05attendance.ui

import com.syntaxgenie.hfx05attendance.R
import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentPresentationTest {
    @Test fun everyFingerHasStableFriendlyLabelResource() {
        val expected = mapOf(
            FingerPosition.RIGHT_THUMB to R.string.finger_right_thumb,
            FingerPosition.RIGHT_INDEX to R.string.finger_right_index,
            FingerPosition.RIGHT_MIDDLE to R.string.finger_right_middle,
            FingerPosition.RIGHT_RING to R.string.finger_right_ring,
            FingerPosition.RIGHT_LITTLE to R.string.finger_right_little,
            FingerPosition.LEFT_THUMB to R.string.finger_left_thumb,
            FingerPosition.LEFT_INDEX to R.string.finger_left_index,
            FingerPosition.LEFT_MIDDLE to R.string.finger_left_middle,
            FingerPosition.LEFT_RING to R.string.finger_left_ring,
            FingerPosition.LEFT_LITTLE to R.string.finger_left_little,
        )
        assertEquals(expected, FingerPosition.entries.associateWith { it.displayNameResource() })
    }

    @Test fun zeroOfFiveActivatesFirstGuidanceZone() {
        assertEquals(listOf(FingerprintZoneState.ACTIVE, FingerprintZoneState.PENDING,
            FingerprintZoneState.PENDING, FingerprintZoneState.PENDING, FingerprintZoneState.PENDING),
            EnrollmentVisualProgress.zoneStates(0))
    }

    @Test fun oneOfFivePreservesCompleteAndAdvancesActiveZone() {
        assertEquals(listOf(FingerprintZoneState.COMPLETE, FingerprintZoneState.ACTIVE,
            FingerprintZoneState.PENDING, FingerprintZoneState.PENDING, FingerprintZoneState.PENDING),
            EnrollmentVisualProgress.zoneStates(1))
    }

    @Test fun fourOfFiveActivatesFinalZone() {
        assertEquals(4, EnrollmentVisualProgress.zoneStates(4).count { it == FingerprintZoneState.COMPLETE })
        assertEquals(FingerprintZoneState.ACTIVE, EnrollmentVisualProgress.zoneStates(4).last())
    }

    @Test fun fiveOfFiveCompletesEveryZone() {
        assertTrue(EnrollmentVisualProgress.zoneStates(5).all { it == FingerprintZoneState.COMPLETE })
    }

    @Test fun failedCaptureRetainsSameActiveIndex() {
        val beforeFailure = EnrollmentVisualProgress.zoneStates(2)
        val afterFailure = EnrollmentVisualProgress.zoneStates(2)
        assertEquals(beforeFailure, afterFailure)
        assertEquals(FingerprintZoneState.ACTIVE, afterFailure[2])
    }

    @Test fun selectedEmployeeModelKeepsDisplayIdentity() {
        val selected = RegistrationEmployeeUiModel("EMP001", "Madhawa Welikumbura", "EPF001")
        assertEquals("EMP001", selected.employeeId)
        assertEquals("Madhawa Welikumbura", selected.displayName)
        assertEquals("EPF001", selected.userId)
    }

    @Test fun incompleteEmployeeIntentIdentityIsRejected() {
        assertEquals(null, RegistrationEmployeeUiModel.from(null, "Name", "EPF001"))
        assertEquals(null, RegistrationEmployeeUiModel.from("EMP001", " ", "EPF001"))
        assertEquals(null, RegistrationEmployeeUiModel.from("EMP001", "Name", null))
    }

    @Test fun completeEmployeeIntentIdentityIsTrimmedAndAccepted() {
        assertEquals(
            RegistrationEmployeeUiModel("EMP001", "John Silva", "EPF001"),
            RegistrationEmployeeUiModel.from(" EMP001 ", " John Silva ", " EPF001 "),
        )
    }

    @Test fun fingerDropdownDefaultsToRightIndexAndMapsSelection() {
        assertEquals(FingerPosition.RIGHT_INDEX, FingerDropdownOptions.default)
        assertEquals(FingerPosition.LEFT_MIDDLE, FingerDropdownOptions.positionAt(2))
    }

    @Test fun fingerDropdownContainsAllTenFingersExactlyOnceInDisplayOrder() {
        val options = FingerDropdownOptions.positions
        assertEquals(10, options.size)
        assertEquals(10, options.toSet().size)
        assertEquals(FingerPosition.entries.toSet(), options.toSet())
        assertEquals(FingerPosition.LEFT_THUMB, options.first())
        assertEquals(FingerPosition.RIGHT_LITTLE, options.last())
    }
}
