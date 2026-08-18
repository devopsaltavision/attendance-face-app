package com.syntaxgenie.hfx05attendance.admin

object AdminAccessResultMapper {
    fun fromHttpStatus(status: Int): AdminAuthResult = when {
        status == 200 -> AdminAuthResult.Success
        status == 401 -> AdminAuthResult.SessionInvalid
        status == 403 -> AdminAuthResult.PermissionDenied
        status >= 500 -> AdminAuthResult.ServiceUnavailable
        else -> AdminAuthResult.Failure("Unexpected admin authentication response")
    }
}
