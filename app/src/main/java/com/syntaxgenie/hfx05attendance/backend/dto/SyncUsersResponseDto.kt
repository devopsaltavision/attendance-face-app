package com.syntaxgenie.hfx05attendance.backend.dto

data class SyncUsersResponseDto(
    val users: List<AttendanceDeviceUserDto>,
    val serverTime: String,
    val nextUpdatedAfter: String,
)
