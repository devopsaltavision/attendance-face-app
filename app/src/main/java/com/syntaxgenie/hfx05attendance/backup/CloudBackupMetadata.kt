package com.syntaxgenie.hfx05attendance.backup

/** Safe Firebase object metadata only; never contains biometric data. */
data class CloudBackupMetadata(val exists: Boolean, val recordCount: Int? = null, val backupAtEpochMillis: Long? = null)
data class DeviceCloudBackupStatus(val fingerprint: CloudBackupMetadata, val face: CloudBackupMetadata, val lastCompleteBackupEpochMillis: Long?)
