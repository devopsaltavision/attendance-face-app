package com.syntaxgenie.hfx05attendance.admin

interface AdminAuthenticator {
    fun authenticate(username: String, password: String): AdminAuthResult
}

sealed class AdminAuthResult {
    data object Unavailable : AdminAuthResult()
    data object Success : AdminAuthResult()
    data class Failure(val message: String) : AdminAuthResult()
}

class UnconfiguredAdminAuthenticator : AdminAuthenticator {
    override fun authenticate(username: String, password: String) = AdminAuthResult.Unavailable
}
