package com.syntaxgenie.hfx05attendance.backend

import java.io.IOException

object BackendErrorMapper {
    fun fromHttp(status: Int, backendCode: String?): BackendApiError = when {
        status == 401 || backendCode == "FPA-401" -> BackendApiError.UNAUTHORIZED
        status == 404 || backendCode == "FPA-001" -> BackendApiError.DEVICE_NOT_FOUND
        status == 403 || backendCode == "FPA-002" -> BackendApiError.DEVICE_DISABLED
        status == 400 && backendCode == "FPA-301" -> BackendApiError.INVALID_SYNC_CURSOR
        status == 500 || status == 503 -> BackendApiError.SERVER_FAILURE
        else -> BackendApiError.UNKNOWN
    }

    fun fromThrowable(error: Throwable): BackendApiError =
        if (error is IOException) BackendApiError.NETWORK_FAILURE else BackendApiError.UNKNOWN
}
