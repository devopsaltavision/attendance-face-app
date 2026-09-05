package com.syntaxgenie.hfx05attendance.face.backup

import android.content.Context
import android.util.Base64
import android.util.Log
import com.syntaxgenie.hfx05attendance.backend.FingerprintApiClient
import com.syntaxgenie.hfx05attendance.backend.config.BackendEnvironmentConfig
import com.syntaxgenie.hfx05attendance.backend.config.DeviceConfigurationRepository
import com.syntaxgenie.hfx05attendance.backend.dto.FaceEnrollmentRemoteDto
import com.syntaxgenie.hfx05attendance.backend.dto.FaceEnrollmentRequestDto
import com.syntaxgenie.hfx05attendance.backend.dto.FaceEnrollmentTemplateDto
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceFeatureCodec
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceModelConfiguration
import com.syntaxgenie.hfx05attendance.face.feature.FaceFeature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Device-API backup/restore boundary. It only handles portable SFace bytes, never Room ciphertext. */
class FaceEnrollmentRemoteRepository(context: Context, private val config: BackendEnvironmentConfig = BackendEnvironmentConfig()) {
    private val appContext = context.applicationContext
    private val api = FingerprintApiClient(config).create()
    private val deviceId get() = DeviceConfigurationRepository(appContext).deviceId().trim()

    fun backup(enrollmentId: String, userId: String, employeeId: String, templates: List<ByteArray>): Result<String> {
        if (!config.apiKeyConfigured || deviceId.isBlank() || userId.isBlank() || employeeId.isBlank()) return Result.failure(IllegalStateException("configuration"))
        if (!validTemplates(templates)) return Result.failure(IllegalArgumentException("invalid_templates"))
        val request = FaceEnrollmentRequestDto(enrollmentId, deviceId, userId, employeeId, engineId = SFaceModelConfiguration.ENGINE_ID,
            modelId = SFaceModelConfiguration.MODEL_ID, modelVersion = SFaceModelConfiguration.MODEL_VERSION,
            templateFormat = SFaceFeatureCodec.FORMAT_ID, enrolledAtDevice = isoNow(),
            templates = templates.mapIndexed { index, bytes -> FaceEnrollmentTemplateDto(index + 1, Base64.encodeToString(bytes, Base64.NO_WRAP)) })
        return try {
            val response = api.recordFaceEnrollment(request).execute()
            val body = response.body()
            if (!response.isSuccessful || body == null || !body.success || body.enrollmentId != enrollmentId ||
                body.biometricType != "FACE" || body.templateCount != 3 || body.status !in setOf("RECORDED", "ALREADY_RECORDED")) {
                Result.failure(IllegalStateException("response"))
            } else Result.success(body.status)
        } catch (error: Throwable) { Result.failure(error) }
    }

    fun get(userId: String): Result<FaceEnrollmentRemoteDto?> {
        if (!config.apiKeyConfigured || deviceId.isBlank() || userId.isBlank()) return Result.failure(IllegalStateException("configuration"))
        return try {
        val response = api.getFaceEnrollments(deviceId, userId).execute()
        if (!response.isSuccessful || response.body() == null) Result.failure(IllegalStateException("response")) else Result.success(response.body()!!.enrollment)
        } catch (error: Throwable) { Result.failure(error) }
    }

    companion object {
        const val TAG = "FaceEnrollmentRemote"
        fun validTemplates(templates: List<ByteArray>): Boolean = templates.size == 3 && templates.all {
            runCatching { SFaceFeatureCodec.decode(it).size == 128 }.isSuccess
        }
        fun decodeAndValidate(remote: FaceEnrollmentRemoteDto): List<ByteArray>? = runCatching {
            require(remote.biometricType == "FACE" && remote.engineId == SFaceModelConfiguration.ENGINE_ID &&
                remote.modelId == SFaceModelConfiguration.MODEL_ID && remote.modelVersion == SFaceModelConfiguration.MODEL_VERSION &&
                remote.templateFormat == SFaceFeatureCodec.FORMAT_ID)
            require(remote.templates.map { it.templateSlot }.toSet() == setOf(1, 2, 3) && remote.templates.size == 3)
            remote.templates.sortedBy { it.templateSlot }.map { Base64.decode(it.templateDataBase64, Base64.NO_WRAP) }.also { require(validTemplates(it)) }
        }.getOrNull()
        private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
    }
}

/** Durable retry metadata only; no biometric data is stored here. */
class FaceBackupSyncStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("face_backup_sync", Context.MODE_PRIVATE)
    data class Pending(val enrollmentId: String, val userId: String, val employeeId: String)
    fun put(pending: Pending) {
        prefs.edit().putString(pending.employeeId, "${pending.enrollmentId}|${pending.userId}")
            .putStringSet("pending_employees", (prefs.getStringSet("pending_employees", emptySet()).orEmpty() + pending.employeeId)).apply()
    }
    fun get(employeeId: String): Pending? = prefs.getString(employeeId, null)?.split('|')?.takeIf { it.size == 2 }?.let { Pending(it[0], it[1], employeeId) }
    fun markSynced(employeeId: String) = prefs.edit().remove(employeeId)
        .putStringSet("pending_employees", prefs.getStringSet("pending_employees", emptySet()).orEmpty() - employeeId).apply()
    fun pending(): List<Pending> = prefs.getStringSet("pending_employees", emptySet()).orEmpty().mapNotNull(::get)
}
