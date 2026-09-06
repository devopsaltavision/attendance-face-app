package com.syntaxgenie.hfx05attendance.face.backup

import android.content.Context
import android.util.Log
import com.syntaxgenie.hfx05attendance.employee.EmployeeRecord
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceFeatureCodec
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceModelConfiguration
import com.syntaxgenie.hfx05attendance.face.index.FaceTemplateIndexManager
import com.syntaxgenie.hfx05attendance.face.repository.AndroidKeystoreFaceTemplateProtector
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentId
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentRecord
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentStatus
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateMetadata
import com.syntaxgenie.hfx05attendance.face.repository.local.FaceEnrollmentDatabase
import com.syntaxgenie.hfx05attendance.face.repository.local.LocalFaceEnrollmentRepository

/** Background, local-first backend synchronization. Remote incompatibility never changes local records. */
class FaceEnrollmentSyncService(private val context: Context) {
    private val appContext = context.applicationContext
    private val store = FaceBackupSyncStore(appContext)

    fun retryPendingAsync() = Thread {
        store.pending().forEach { pending ->
            val templates = runCatching { repository().listByEmployee(pending.employeeId).filter { it.status == FaceEnrollmentStatus.ACTIVE }.map { it.templatePayload() } }.getOrNull()
            if (templates == null || !FaceEnrollmentRemoteRepository.validTemplates(templates)) return@forEach
            FaceEnrollmentRemoteRepository(appContext).backup(pending.enrollmentId, pending.userId, pending.employeeId, templates)
                .onSuccess { status -> store.markSynced(pending.employeeId); Log.i(TAG, "FACE_BACKUP_SUCCESS employeeId=${pending.employeeId} status=$status") }
                .onFailure { error -> Log.i(TAG, "FACE_BACKUP_PENDING employeeId=${pending.employeeId} reason=${error.javaClass.simpleName}") }
        }
    }.apply { name = "face-backup-retry" }.start()

    enum class RestoreResult { RESTORED, SKIPPED_COMPLETE, SKIPPED_NOT_ENROLLED, MISSING_REMOTE, FAILED }

    fun restoreIfMissingAsync(employee: EmployeeRecord) = Thread {
        restoreIfMissing(employee)
    }.apply { name = "face-restore-${employee.employeeId}" }.start()

    /** Existing portable FACE GET restore, exposed synchronously for the unified admin workflow. */
    fun restoreIfMissing(employee: EmployeeRecord): RestoreResult {
        val local = repository().listByEmployee(employee.employeeId).filter { it.status == FaceEnrollmentStatus.ACTIVE }
        val completeCompatible = local.size == 3 && local.all {
            it.metadata.engineId == SFaceModelConfiguration.ENGINE_ID &&
                it.metadata.modelId == SFaceModelConfiguration.MODEL_ID &&
                it.metadata.modelVersion == SFaceModelConfiguration.MODEL_VERSION &&
                it.metadata.templateFormatVersion == SFaceFeatureCodec.FORMAT_ID &&
                it.metadata.enrollmentSampleCount == 3 &&
                runCatching { SFaceFeatureCodec.decode(it.templatePayload()).size == 128 }.isSuccess
        }
        if (completeCompatible) { Log.i(TAG, "FACE_RESTORE_SKIPPED employeeId=${employee.employeeId} reason=local_complete"); return RestoreResult.SKIPPED_COMPLETE }
        if (!employee.faceEnrolled) return RestoreResult.SKIPPED_NOT_ENROLLED
        Log.i(TAG, "FACE_RESTORE_START employeeId=${employee.employeeId}")
        val remote = FaceEnrollmentRemoteRepository(appContext).get(employee.userId).getOrElse { error -> Log.i(TAG, "FACE_RESTORE_FAILED employeeId=${employee.employeeId} reason=${error.javaClass.simpleName}"); return RestoreResult.FAILED }
        val enrollment = remote ?: run { Log.i(TAG, "FACE_RESTORE_SKIPPED employeeId=${employee.employeeId} reason=remote_missing"); return RestoreResult.MISSING_REMOTE }
        if (enrollment.employeeId != employee.employeeId || enrollment.userId != employee.userId ||
            (employee.faceEnrollmentId != null && employee.faceEnrollmentId != enrollment.enrollmentId)) {
            Log.i(TAG, "FACE_RESTORE_FAILED employeeId=${employee.employeeId} reason=identity_mismatch"); return RestoreResult.FAILED
        }
        val templates = FaceEnrollmentRemoteRepository.decodeAndValidate(enrollment) ?: run { Log.i(TAG, "FACE_RESTORE_FAILED employeeId=${employee.employeeId} reason=invalid_response"); return RestoreResult.FAILED }
        val now = System.currentTimeMillis()
        runCatching {
            // Replace only incomplete local sets, then persist the complete validated trio.
            repository().replaceAllForEmployee(employee.employeeId, templates.mapIndexed { index, bytes -> FaceEnrollmentRecord(FaceEnrollmentId("${enrollment.enrollmentId}-${index + 1}"), employee.employeeId, bytes,
                FaceTemplateMetadata(SFaceModelConfiguration.ENGINE_ID, SFaceModelConfiguration.MODEL_ID, SFaceModelConfiguration.MODEL_VERSION, SFaceFeatureCodec.FORMAT_ID, 1, enrollmentSampleCount = 3), now, now) })
        }.onSuccess {
            FaceTemplateIndexManager.get(appContext).addOrReplaceEmployee(employee.employeeId, templates)
            Log.i(TAG, "FACE_RESTORE_SUCCESS employeeId=${employee.employeeId}")
            return RestoreResult.RESTORED
        }.onFailure { error -> Log.i(TAG, "FACE_RESTORE_FAILED employeeId=${employee.employeeId} reason=${error.javaClass.simpleName}") }
        return RestoreResult.FAILED
    }

    private fun repository() = LocalFaceEnrollmentRepository(FaceEnrollmentDatabase.create(appContext).faceEnrollmentDao(), AndroidKeystoreFaceTemplateProtector())
    private companion object { const val TAG = "FaceEnrollmentSync" }
}
