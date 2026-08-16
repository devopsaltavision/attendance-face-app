package com.syntaxgenie.hfx05attendance.ui

data class RegistrationEmployeeUiModel(val employeeId: String, val displayName: String?) {
    init { require(employeeId.isNotBlank()) }
    val isPreselected: Boolean get() = !displayName.isNullOrBlank()
}
