package com.syntaxgenie.hfx05attendance.ui

data class RegistrationEmployeeUiModel(
    val employeeId: String,
    val displayName: String,
    val userId: String,
) {
    init {
        require(employeeId.isNotBlank())
        require(displayName.isNotBlank())
        require(userId.isNotBlank())
    }

    companion object {
        fun from(employeeId: String?, displayName: String?, userId: String?): RegistrationEmployeeUiModel? {
            val id = employeeId?.trim().orEmpty()
            val name = displayName?.trim().orEmpty()
            val epf = userId?.trim().orEmpty()
            return if (id.isEmpty() || name.isEmpty() || epf.isEmpty()) null else RegistrationEmployeeUiModel(id, name, epf)
        }
    }
}
