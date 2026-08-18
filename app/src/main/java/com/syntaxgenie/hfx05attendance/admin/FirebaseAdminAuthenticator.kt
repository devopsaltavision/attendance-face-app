package com.syntaxgenie.hfx05attendance.admin

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class FirebaseAdminAuthenticator(
    private val firebaseAuth: FirebaseAuth,
    private val adminApi: AdminAccessApiService,
) : AdminAuthenticator {
    override fun authenticate(username: String, password: String, callback: (AdminAuthResult) -> Unit) {
        val email = username.trim()
        if (email.isBlank() || password.isBlank()) {
            callback(AdminAuthResult.InvalidCredentials)
            return
        }

        firebaseAuth.signInWithEmailAndPassword(email, password).addOnCompleteListener { signIn ->
            if (!signIn.isSuccessful) {
                callback(mapFirebaseFailure(signIn.exception))
                return@addOnCompleteListener
            }

            val user = firebaseAuth.currentUser
            if (user == null) {
                firebaseAuth.signOut()
                callback(AdminAuthResult.SessionInvalid)
                return@addOnCompleteListener
            }

            user.getIdToken(true).addOnCompleteListener { tokenTask ->
                val token = tokenTask.result?.token
                if (!tokenTask.isSuccessful || token.isNullOrBlank()) {
                    firebaseAuth.signOut()
                    callback(mapTokenFailure(tokenTask.exception))
                    return@addOnCompleteListener
                }
                checkBackendAccess(token, callback)
            }
        }
    }

    private fun checkBackendAccess(token: String, callback: (AdminAuthResult) -> Unit) {
        adminApi.checkAccess("Bearer $token").enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                val result = AdminAccessResultMapper.fromHttpStatus(response.code())
                if (result != AdminAuthResult.Success) firebaseAuth.signOut()
                callback(result)
            }

            override fun onFailure(call: Call<Void>, error: Throwable) {
                firebaseAuth.signOut()
                callback(AdminAuthResult.NetworkUnavailable)
            }
        })
    }

    private fun mapFirebaseFailure(error: Exception?): AdminAuthResult = when (error) {
        is FirebaseNetworkException -> AdminAuthResult.NetworkUnavailable
        is FirebaseAuthInvalidUserException -> if (error.errorCode == "ERROR_USER_DISABLED") {
            AdminAuthResult.AccountDisabled
        } else {
            AdminAuthResult.InvalidCredentials
        }
        is FirebaseAuthInvalidCredentialsException -> AdminAuthResult.InvalidCredentials
        else -> AdminAuthResult.Failure("Admin authentication failed")
    }

    private fun mapTokenFailure(error: Exception?): AdminAuthResult =
        if (error is FirebaseNetworkException) AdminAuthResult.NetworkUnavailable else AdminAuthResult.SessionInvalid
}
