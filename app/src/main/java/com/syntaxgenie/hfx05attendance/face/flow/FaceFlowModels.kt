package com.syntaxgenie.hfx05attendance.face.flow

enum class FaceFlowState { SCANNING, PROCESSING, BEST_CANDIDATE, CANDIDATE_LIST, ATTENDANCE_ACTION, UNKNOWN, AMBIGUOUS, NO_TEMPLATES, ERROR }
/** Safe ranking metadata passed between face-flow screens; it contains no biometric payload. */
data class FaceCandidateSeed(val employeeId: String, val score: Double)
data class FaceCandidate(val employeeId: String, val displayName: String, val score: Double)
sealed interface FaceVerificationResult { data object CalibrationRequired : FaceVerificationResult; data object Verified : FaceVerificationResult; data object Rejected : FaceVerificationResult; data class Error(val message: String) : FaceVerificationResult }
enum class AttendanceAction { CHECK_IN, CHECK_OUT }
