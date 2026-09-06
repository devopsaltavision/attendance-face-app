package com.syntaxgenie.hfx05attendance.backup

import android.content.Context
import com.syntaxgenie.hfx05attendance.employee.local.EmployeeDirectoryDatabase
import com.syntaxgenie.hfx05attendance.employee.local.LocalEmployeeDirectory
import com.syntaxgenie.hfx05attendance.face.backup.FaceCloudBackupService
import com.syntaxgenie.hfx05attendance.face.repository.AndroidKeystoreFaceTemplateProtector
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentStatus
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateCompatibility
import com.syntaxgenie.hfx05attendance.face.repository.local.FaceEnrollmentDatabase
import com.syntaxgenie.hfx05attendance.face.repository.local.LocalFaceEnrollmentRepository
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceFeatureCodec
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceModelConfiguration
import com.syntaxgenie.hfx05attendance.fingerprint.backup.FingerprintBackupService
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.sourceafis.SourceAfisFingerprintMatcher
import com.syntaxgenie.hfx05attendance.fingerprint.repository.local.BiometricDatabase
import com.syntaxgenie.hfx05attendance.fingerprint.repository.local.LocalBiometricRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class DeviceBackupOverall { SUCCESS, PARTIAL, FAILED }
data class DeviceBackupSection(val success: Boolean, val count: Int, val attemptedCount: Int = count, val failedCount: Int = 0)
data class DeviceBackupSummary(val fingerprint: DeviceBackupSection, val face: DeviceBackupSection, val overall: DeviceBackupOverall)

/** One admin recovery workflow over the established Fingerprint and Face Firebase Storage backups. */
class DeviceBackupCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val matcher = SourceAfisFingerprintMatcher()
    private val fingerprintRepository = LocalBiometricRepository(BiometricDatabase.create(appContext).biometricTemplateDao(), matcher.metadata)
    private val fingerprintBackup = FingerprintBackupService(appContext, fingerprintRepository, matcher.metadata)
    private val faceBackup = FaceCloudBackupService(appContext)
    private val faceRepository = LocalFaceEnrollmentRepository(FaceEnrollmentDatabase.create(appContext).faceEnrollmentDao(), AndroidKeystoreFaceTemplateProtector())
    private val preferences = appContext.getSharedPreferences("device_backup", Context.MODE_PRIVATE)

    fun lastSuccessfulBackupMillis(): Long = preferences.getLong("last_successful_backup", 0L)

    fun backupAll(callback: (DeviceBackupSummary) -> Unit) = Thread {
        val fingerprint = synchronizeFingerprint()
        val face = awaitFace { faceBackup.backup(it) }
        val overall = overall(fingerprint, face)
        if (overall == DeviceBackupOverall.SUCCESS) preferences.edit().putLong("last_successful_backup", System.currentTimeMillis()).apply()
        callback(DeviceBackupSummary(fingerprint, face, overall))
    }.apply { name = "device-backup" }.start()

    fun restoreAll(callback: (DeviceBackupSummary) -> Unit) = Thread {
        val fingerprint = synchronizeFingerprint()
        val face = awaitFace { faceBackup.restore(it) }
        callback(DeviceBackupSummary(fingerprint, face, overall(fingerprint, face)))
    }.apply { name = "device-restore" }.start()

    fun readCloudStatus(callback: (Result<DeviceCloudBackupStatus>) -> Unit) = Thread {
        val fingerprint = awaitMetadata { fingerprintBackup.readCloudMetadata(it) }
        val face = awaitMetadata { faceBackup.readCloudMetadata(it) }
        if (fingerprint == null || face == null) callback(Result.failure(IllegalStateException("Cloud backup status could not be read.")))
        else {
            val lastComplete = when {
                fingerprint.exists && face.exists -> listOfNotNull(fingerprint.backupAtEpochMillis, face.backupAtEpochMillis).minOrNull()
                fingerprint.exists && !face.exists && localFaceCount() == 0 -> fingerprint.backupAtEpochMillis
                face.exists && !fingerprint.exists && localFingerprintCount() == 0 -> face.backupAtEpochMillis
                else -> null
            }
            callback(Result.success(DeviceCloudBackupStatus(fingerprint, face, lastComplete)))
        }
    }.apply { name = "device-backup-status" }.start()

    private fun synchronizeFingerprint(): DeviceBackupSection {
        val latch = CountDownLatch(1)
        var success = false; var count = 0
        fingerprintBackup.synchronize { result ->
            result.onSuccess { count = it.localEnrollmentCount; success = true }
            latch.countDown()
        }
        if (!latch.await(90, TimeUnit.SECONDS)) return DeviceBackupSection(false, 0, 0, 1)
        if (!success) return DeviceBackupSection(false, 0, 0, 1)
        return DeviceBackupSection(true, count)
    }

    fun deleteCloudBackups(callback: (DeviceBackupOverall) -> Unit) = Thread {
        val fingerprint = awaitUnit { fingerprintBackup.deleteCloudBackup(it) }
        val face = awaitUnit { faceBackup.deleteCloudBackup(it) }
        callback(when { fingerprint && face -> DeviceBackupOverall.SUCCESS; !fingerprint && !face -> DeviceBackupOverall.FAILED; else -> DeviceBackupOverall.PARTIAL })
    }.apply { name = "device-backup-delete" }.start()

    private fun awaitFace(start: ((Result<com.syntaxgenie.hfx05attendance.face.backup.FaceCloudBackupSummary>) -> Unit) -> Unit): DeviceBackupSection {
        val latch = CountDownLatch(1); var result: Result<com.syntaxgenie.hfx05attendance.face.backup.FaceCloudBackupSummary>? = null
        start { result = it; latch.countDown() }
        val summary = if (latch.await(90, TimeUnit.SECONDS)) result?.getOrNull() else null
        return if (summary == null) DeviceBackupSection(false, 0, 0, 1) else DeviceBackupSection(summary.success, summary.count, summary.attemptedCount, summary.failedCount)
    }

    private fun awaitUnit(start: ((Result<Unit>) -> Unit) -> Unit): Boolean {
        val latch = CountDownLatch(1); var success = false
        start { success = it.isSuccess; latch.countDown() }
        return latch.await(90, TimeUnit.SECONDS) && success
    }

    private fun awaitMetadata(start: ((Result<CloudBackupMetadata>) -> Unit) -> Unit): CloudBackupMetadata? {
        val latch = CountDownLatch(1); var result: CloudBackupMetadata? = null
        start { result = it.getOrNull(); latch.countDown() }
        return if (latch.await(30, TimeUnit.SECONDS)) result else null
    }

    private fun localFingerprintCount() = (fingerprintRepository.getAll() as? com.syntaxgenie.hfx05attendance.fingerprint.repository.RepositoryResult.Success)
        ?.value?.groupBy { it.enrollmentId }?.size ?: -1
    private fun localFaceCount(): Int = faceRepository.listCompatible(FaceTemplateCompatibility(SFaceModelConfiguration.ENGINE_ID,
        SFaceModelConfiguration.MODEL_ID, SFaceModelConfiguration.MODEL_VERSION, SFaceFeatureCodec.FORMAT_ID))
        .filter { it.status == FaceEnrollmentStatus.ACTIVE }.groupBy { it.employeeId }.size

    private fun overall(fingerprint: DeviceBackupSection, face: DeviceBackupSection) = when {
        fingerprint.success && face.success -> DeviceBackupOverall.SUCCESS
        !fingerprint.success && !face.success -> DeviceBackupOverall.FAILED
        else -> DeviceBackupOverall.PARTIAL
    }

    private companion object { const val TAG = "DeviceBackup" }
}
