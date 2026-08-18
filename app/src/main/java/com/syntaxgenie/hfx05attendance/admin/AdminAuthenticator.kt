package com.syntaxgenie.hfx05attendance.admin

interface AdminAuthenticator {
    fun authenticate(username: String, password: String, callback: (AdminAuthResult) -> Unit)
}

sealed class AdminAuthResult {
    data object Unavailable : AdminAuthResult()
    data object Success : AdminAuthResult()
    data object InvalidCredentials : AdminAuthResult()
    data object AccountDisabled : AdminAuthResult()
    data object NetworkUnavailable : AdminAuthResult()
    data object SessionInvalid : AdminAuthResult()
    data object PermissionDenied : AdminAuthResult()
    data object ServiceUnavailable : AdminAuthResult()
    data class Failure(val message: String) : AdminAuthResult()
}

class UnconfiguredAdminAuthenticator : AdminAuthenticator {
    override fun authenticate(username: String, password: String, callback: (AdminAuthResult) -> Unit) {
        callback(AdminAuthResult.Unavailable)
    }
}
