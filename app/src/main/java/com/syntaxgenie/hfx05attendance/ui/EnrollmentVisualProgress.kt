package com.syntaxgenie.hfx05attendance.ui

object EnrollmentVisualProgress {
    const val REQUIRED_CAPTURES = 5

    // Deterministic visual-guidance order from the source SVG; not biological scan regions.
    fun zoneStates(completedCaptures: Int, requiredCaptures: Int = REQUIRED_CAPTURES): List<FingerprintZoneState> {
        require(requiredCaptures == REQUIRED_CAPTURES)
        require(completedCaptures in 0..requiredCaptures)
        return List(requiredCaptures) { index -> when {
            index < completedCaptures -> FingerprintZoneState.COMPLETE
            index == completedCaptures && completedCaptures < requiredCaptures -> FingerprintZoneState.ACTIVE
            else -> FingerprintZoneState.PENDING
        } }
    }
}
