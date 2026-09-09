package com.syntaxgenie.hfx05attendance.attendance

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.syntaxgenie.hfx05attendance.attendance.local.LocalAttendanceRepository
import com.syntaxgenie.hfx05attendance.backend.BackendApiError
import com.syntaxgenie.hfx05attendance.backend.FingerprintApiClient
import com.syntaxgenie.hfx05attendance.backend.config.BackendEnvironmentConfig
import com.syntaxgenie.hfx05attendance.backend.config.DeviceConfigurationRepository
import com.syntaxgenie.hfx05attendance.employee.local.EmployeeDirectoryDatabase

class PendingAttendanceSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val database = EmployeeDirectoryDatabase.create(applicationContext)
        val environment = BackendEnvironmentConfig()
        val service = AttendanceService(
            FingerprintApiClient(environment).create(),
            environment,
            DeviceConfigurationRepository(applicationContext)::deviceId,
            LocalAttendanceRepository(database.attendanceDao()),
            ::networkAvailable,
        )
        while (true) {
            val summary = service.syncPendingAttendance()
            when (summary.error) {
                BackendApiError.NETWORK_UNAVAILABLE,
                BackendApiError.NETWORK_FAILURE -> return Result.retry()
                null -> {
                    // Continue only after a completely successful FIFO batch. A remaining
                    // rejected event is deliberately left pending without network retry.
                    if (summary.attempted == 0 || summary.synced != summary.attempted) return Result.success()
                }
                else -> return Result.success()
            }
        }
    }

    private fun networkAvailable(): Boolean {
        val manager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}

object PendingAttendanceSyncScheduler {
    const val UNIQUE_WORK_NAME = "pending-attendance-sync"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<PendingAttendanceSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueIfPending(context: Context) {
        Thread {
            val database = EmployeeDirectoryDatabase.create(context.applicationContext)
            if (LocalAttendanceRepository(database.attendanceDao()).pending(1).isNotEmpty()) {
                enqueue(context)
            }
        }.apply { name = "pending-attendance-schedule" }.start()
    }
}
