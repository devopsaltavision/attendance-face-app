package com.syntaxgenie.hfx05attendance.backend.dto

data class SyncUsersRequestDto(val deviceId: String, val updatedAfter: String? = null)
