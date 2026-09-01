package com.syntaxgenie.hfx05attendance

import android.content.Context
import java.util.Locale

/** Records an administrator-confirmed deletion while backend user-sync metadata catches up. */
class ExplicitFingerprintDeletionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun record(employeeId: String, enrollmentId: String) {
        if (employeeId.isNotBlank() && enrollmentId.isNotBlank()) {
            preferences.edit().putString(key(employeeId), enrollmentId).apply()
        }
    }

    fun matches(employeeId: String, backendEnrollmentId: String?): Boolean {
        val deletedEnrollmentId = preferences.getString(key(employeeId), null) ?: return false
        return backendEnrollmentId.isNullOrBlank() || deletedEnrollmentId == backendEnrollmentId
    }

    fun clear(employeeId: String) {
        preferences.edit().remove(key(employeeId)).apply()
    }

    private fun key(employeeId: String) = employeeId.trim().lowercase(Locale.ROOT)

    private companion object {
        const val PREFERENCES = "explicit_fingerprint_deletions"
    }
}
