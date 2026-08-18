package com.syntaxgenie.hfx05attendance.backend

import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import java.io.IOException

object BackendErrorMapper {
    fun fromHttp(status: Int, backendCode: String?): BackendApiError = when {
        status == 401 || backendCode == "FPA-401" -> BackendApiError.UNAUTHORIZED
        status == 404 || backendCode == "FPA-001" -> BackendApiError.DEVICE_NOT_FOUND
        status == 403 || backendCode == "FPA-002" -> BackendApiError.DEVICE_DISABLED
        status == 400 && backendCode == "FPA-301" -> BackendApiError.INVALID_SYNC_CURSOR
        status in 500..599 -> BackendApiError.SERVER_FAILURE
        else -> BackendApiError.UNKNOWN
    }

    fun fromThrowable(error: Throwable): BackendApiError =
        when (error) {
            is JsonParseException, is MalformedJsonException -> BackendApiError.INVALID_RESPONSE
            is IOException -> BackendApiError.NETWORK_FAILURE
            else -> BackendApiError.UNKNOWN
        }
}
