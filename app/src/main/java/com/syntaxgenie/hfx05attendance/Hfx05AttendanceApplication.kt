package com.syntaxgenie.hfx05attendance

import android.app.Application
import com.syntaxgenie.hfx05attendance.face.calibration.FaceCalibrationFirestoreRepository
import com.syntaxgenie.hfx05attendance.attendance.PendingAttendanceSyncScheduler
import com.syntaxgenie.hfx05attendance.face.index.FaceTemplateIndexManager
import com.syntaxgenie.hfx05attendance.face.backup.FaceEnrollmentSyncService
import com.syntaxgenie.hfx05attendance.employee.local.EmployeeDirectoryDatabase
import com.syntaxgenie.hfx05attendance.employee.local.LocalEmployeeDirectory

class Hfx05AttendanceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PendingAttendanceSyncScheduler.enqueueIfPending(this)
        FaceTemplateIndexManager.get(this).warmUp()
        FaceCalibrationFirestoreRepository.get(this).refreshThresholdConfig()
        FaceEnrollmentSyncService(this).retryPendingAsync()
        Thread {
            runCatching { LocalEmployeeDirectory(EmployeeDirectoryDatabase.create(this).employeeDao()).getAll() }
                .getOrDefault(emptyList()).filter { it.faceEnrolled }.forEach { FaceEnrollmentSyncService(this).restoreIfMissingAsync(it) }
        }.apply { name = "face-restore-dispatch" }.start()
    }
}
