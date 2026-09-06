package com.syntaxgenie.hfx05attendance.face.backup

import android.content.Context
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageException
import com.syntaxgenie.hfx05attendance.backup.PortableBackupCrypto
import com.syntaxgenie.hfx05attendance.backup.CloudBackupMetadata
import com.syntaxgenie.hfx05attendance.backend.config.DeviceConfigurationRepository
import com.syntaxgenie.hfx05attendance.employee.local.EmployeeDirectoryDatabase
import com.syntaxgenie.hfx05attendance.employee.local.LocalEmployeeDirectory
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceFeatureCodec
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceModelConfiguration
import com.syntaxgenie.hfx05attendance.face.index.FaceTemplateIndexManager
import com.syntaxgenie.hfx05attendance.face.repository.AndroidKeystoreFaceTemplateProtector
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentId
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentRecord
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentStatus
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateCompatibility
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateMetadata
import com.syntaxgenie.hfx05attendance.face.repository.local.FaceEnrollmentDatabase
import com.syntaxgenie.hfx05attendance.face.repository.local.LocalFaceEnrollmentRepository
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

data class FaceCloudBackupSummary(val success: Boolean, val count: Int, val attemptedCount: Int = count, val failedCount: Int = 0)

/** Firebase Storage disaster recovery for portable SFace templates only. */
class FaceCloudBackupService(
    context: Context,
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    private val appContext = context.applicationContext
    private val repository = LocalFaceEnrollmentRepository(FaceEnrollmentDatabase.create(appContext).faceEnrollmentDao(), AndroidKeystoreFaceTemplateProtector())
    private val employees = LocalEmployeeDirectory(EmployeeDirectoryDatabase.create(appContext).employeeDao())

    fun backup(callback: (Result<FaceCloudBackupSummary>) -> Unit) {
        val config = configuration(callback) ?: return
        Thread {
            val prepared = runCatching { export(config.deviceId) }
            if (prepared.isFailure) { callback(Result.failure(prepared.exceptionOrNull()!!)); return@Thread }
            val preparedBackup = prepared.getOrThrow()
            val encrypted = PortableBackupCrypto.encrypt(preparedBackup.bytes, config.key, MAGIC)
            val count = preparedBackup.count
            val metadata = StorageMetadata.Builder().setCustomMetadata("backupType", "FACE")
                .setCustomMetadata("schemaVersion", SCHEMA.toString()).setCustomMetadata("recordCount", count.toString())
                .setCustomMetadata("backupAtEpochMillis", preparedBackup.createdAt.toString()).build()
            storage.reference.child(storagePath(config.deviceId)).putBytes(encrypted, metadata)
                .addOnSuccessListener { callback(Result.success(FaceCloudBackupSummary(true, count))) }
                .addOnFailureListener { callback(Result.failure(IllegalStateException("Face backup failed."))) }
        }.apply { name = "face-cloud-backup" }.start()
    }

    fun restore(callback: (Result<FaceCloudBackupSummary>) -> Unit) {
        val config = configuration(callback) ?: return
        storage.reference.child(storagePath(config.deviceId)).getBytes(MAX_BYTES)
            .addOnSuccessListener { encrypted -> Thread {
                val result = runCatching {
                    val records = decode(PortableBackupCrypto.decrypt(encrypted, config.key, MAGIC), config.deviceId)
                    // Decode/validate every record before changing Room.
                    records.forEach { it.validate() }
                    records.forEach { it.restore(repository) }
                    FaceTemplateIndexManager.get(appContext).refresh()
                    FaceCloudBackupSummary(true, records.size)
                }
                callback(result)
            }.apply { name = "face-cloud-restore" }.start() }
            .addOnFailureListener { error -> callback(Result.failure(if (error is StorageException && error.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND)
                IllegalStateException("Face cloud backup was not found.") else IllegalStateException("Face restore failed."))) }
    }

    fun deleteCloudBackup(callback: (Result<Unit>) -> Unit) {
        val config = configuration(callback) ?: return
        storage.reference.child(storagePath(config.deviceId)).delete().addOnSuccessListener { callback(Result.success(Unit)) }
            .addOnFailureListener { error -> if (error is StorageException && error.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) callback(Result.success(Unit))
            else callback(Result.failure(IllegalStateException("Face cloud backup could not be deleted."))) }
    }

    fun readCloudMetadata(callback: (Result<CloudBackupMetadata>) -> Unit) {
        val config = configuration(callback) ?: return
        storage.reference.child(storagePath(config.deviceId)).metadata.addOnSuccessListener { metadata ->
            callback(Result.success(CloudBackupMetadata(true, metadata.getCustomMetadata("recordCount")?.toIntOrNull(),
                metadata.getCustomMetadata("backupAtEpochMillis")?.toLongOrNull())))
        }.addOnFailureListener { error -> if (error is StorageException && error.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND)
            callback(Result.success(CloudBackupMetadata(false))) else callback(Result.failure(IllegalStateException("Face cloud backup status could not be read."))) }
    }

    private fun export(deviceId: String): PreparedBackup {
        val employeesById = employees.getAll().associateBy { it.employeeId }
        val compatibility = FaceTemplateCompatibility(SFaceModelConfiguration.ENGINE_ID, SFaceModelConfiguration.MODEL_ID, SFaceModelConfiguration.MODEL_VERSION, SFaceFeatureCodec.FORMAT_ID)
        val rows = repository.listCompatible(compatibility).filter { it.status == FaceEnrollmentStatus.ACTIVE }.groupBy { it.employeeId }
        val array = JSONArray()
        rows.forEach { (employeeId, values) ->
            val employee = requireNotNull(employeesById[employeeId]) { "Face employee is unavailable." }
            val enrollmentId = values.first().id.value.substringBeforeLast("-")
            require(values.size == 3 && values.map { it.id.value }.toSet() == setOf("$enrollmentId-1", "$enrollmentId-2", "$enrollmentId-3"))
            val templates = values.sortedBy { it.id.value }.map { it.templatePayload() }
            require(validTemplates(templates))
            array.put(JSONObject().put("employeeId", employeeId).put("userId", employee.userId).put("enrollmentId", enrollmentId)
                .put("engineId", SFaceModelConfiguration.ENGINE_ID).put("modelId", SFaceModelConfiguration.MODEL_ID)
                .put("modelVersion", SFaceModelConfiguration.MODEL_VERSION).put("templateFormat", SFaceFeatureCodec.FORMAT_ID)
                .put("templates", JSONArray().apply { templates.forEachIndexed { index, bytes -> put(JSONObject().put("slot", index + 1).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))) } }))
        }
        val createdAt = System.currentTimeMillis()
        return PreparedBackup(JSONObject().put("schemaVersion", SCHEMA).put("deviceId", deviceId).put("createdAt", createdAt).put("faces", array)
            .toString().toByteArray(StandardCharsets.UTF_8), array.length(), createdAt)
    }

    private fun decode(plaintext: ByteArray, expectedDeviceId: String): List<CloudFace> {
        val root = JSONObject(String(plaintext, StandardCharsets.UTF_8))
        require(root.getInt("schemaVersion") == SCHEMA && root.getString("deviceId") == expectedDeviceId && root.getLong("createdAt") > 0)
        val faces = root.getJSONArray("faces")
        return List(faces.length()) { CloudFace.from(faces.getJSONObject(it)) }.also { records ->
            require(records.map { it.employeeId }.distinct().size == records.size)
        }
    }

    private fun <T> configuration(callback: (Result<T>) -> Unit): Config? {
        if (auth.currentUser == null) { callback(Result.failure(IllegalStateException("Administrator authentication is required."))); return null }
        val deviceId = DeviceConfigurationRepository(appContext).deviceId().trim()
        if (deviceId.isBlank() || deviceId.contains('/') || deviceId.contains('\\')) { callback(Result.failure(IllegalStateException("A valid device ID is required."))); return null }
        val key = runCatching { PortableBackupCrypto.configuredKey() }.getOrElse { callback(Result.failure(it)); return null }
        return Config(deviceId, key)
    }

    private data class Config(val deviceId: String, val key: ByteArray)
    private data class PreparedBackup(val bytes: ByteArray, val count: Int, val createdAt: Long)
    private fun validTemplates(templates: List<ByteArray>) = templates.size == 3 && templates.all {
        runCatching { SFaceFeatureCodec.decode(it).size == 128 }.isSuccess
    }
    private data class CloudFace(val employeeId: String, val enrollmentId: String, val templates: List<ByteArray>) {
        fun validate() {
            require(employeeId.isNotBlank() && enrollmentId.isNotBlank() && templates.size == 3)
            require(templates.size == 3 && templates.all { runCatching { SFaceFeatureCodec.decode(it).size == 128 }.isSuccess })
        }
        fun restore(repository: LocalFaceEnrollmentRepository) {
            val now = System.currentTimeMillis()
            repository.replaceAllForEmployee(employeeId, templates.mapIndexed { index, bytes -> FaceEnrollmentRecord(FaceEnrollmentId("$enrollmentId-${index + 1}"), employeeId, bytes,
                FaceTemplateMetadata(SFaceModelConfiguration.ENGINE_ID, SFaceModelConfiguration.MODEL_ID, SFaceModelConfiguration.MODEL_VERSION, SFaceFeatureCodec.FORMAT_ID, 1, enrollmentSampleCount = 3), now, now) })
        }
        companion object {
            fun from(value: JSONObject): CloudFace {
                require(value.getString("employeeId").isNotBlank() && value.getString("userId").isNotBlank() && value.getString("enrollmentId").isNotBlank())
                require(value.getString("engineId") == SFaceModelConfiguration.ENGINE_ID && value.getString("modelId") == SFaceModelConfiguration.MODEL_ID &&
                    value.getString("modelVersion") == SFaceModelConfiguration.MODEL_VERSION && value.getString("templateFormat") == SFaceFeatureCodec.FORMAT_ID)
                val templates = value.getJSONArray("templates")
                require(templates.length() == 3)
                return CloudFace(value.getString("employeeId"), value.getString("enrollmentId"), List(3) { index ->
                    val template = templates.getJSONObject(index); require(template.getInt("slot") == index + 1)
                    Base64.decode(template.getString("data"), Base64.NO_WRAP)
                })
            }
        }
    }

    companion object {
        private const val SCHEMA = 1; private const val MAX_BYTES = 32L * 1024L * 1024L
        private val MAGIC = byteArrayOf('F'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
        fun storagePath(deviceId: String) = "face-backups/$deviceId/latest.facebackup"
    }
}
