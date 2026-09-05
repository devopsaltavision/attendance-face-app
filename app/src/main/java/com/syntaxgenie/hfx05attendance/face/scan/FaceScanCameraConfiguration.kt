package com.syntaxgenie.hfx05attendance.face.scan

/**
 * HF-X05 physical-camera evidence: Camera 0 is aimed at the ceiling/upper enclosure.
 * Camera 1 is the candidate user-facing sensor and is therefore selected for this proof.
 * Its spectral role is intentionally not labelled RGB until chroma evidence is collected.
 */
object FaceScanCameraConfiguration {
    // MVP RGB identity path; Camera 1 is reserved for IR/liveness diagnostics.
    const val USER_FACING_CAMERA_ID = 0
    // Physical Camera 1 screenshot showed the user upside down at metadata-derived 270°.
    // The RGB module is physically inverted on this enclosure; a 90-degree
    // override corrects the prior 270-degree result by 180 degrees.
    const val USER_FACING_DISPLAY_ORIENTATION = 90
}
