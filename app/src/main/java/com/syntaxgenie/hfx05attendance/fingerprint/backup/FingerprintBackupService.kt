package com.syntaxgenie.hfx05attendance.fingerprint.backup

import android.content.Context
import android.util.Base64
import com.syntaxgenie.hfx05attendance.backup.PortableBackupCrypto
import com.syntaxgenie.hfx05attendance.backup.CloudBackupMetadata
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageException
import com.syntaxgenie.hfx05attendance.BuildConfig
import com.syntaxgenie.hfx05attendance.backend.config.DeviceConfigurationRepository
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.FingerprintTemplate
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherMetadata
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRecord
import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition
import com.syntaxgenie.hfx05attendance.fingerprint.repository.RepositoryResult
import com.syntaxgenie.hfx05attendance.fingerprint.repository.local.LocalBiometricRepository
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class FingerprintBackupService(
    context: Context,
    private val repository: LocalBiometricRepository,
    private val activeMatcherMetadata: MatcherMetadata,
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val deviceConfiguration = DeviceConfigurationRepository(context.applicationContext)

    fun synchronize(callback: (Result<FingerprintSyncSummary>) -> Unit) {
        val configuration = configuration(callback) ?: return
        Thread {
            val localResult = runCatching { loadAndValidateLocal() }
            if (localResult.isFailure) {
                callback(Result.failure(localResult.exceptionOrNull()!!))
                return@Thread
            }
            val local = localResult.getOrThrow()
            downloadRemote(configuration) { remoteResult ->
                if (remoteResult.isFailure) {
                    callback(Result.failure(remoteResult.exceptionOrNull()!!))
                    return@downloadRemote
                }
                val remote = remoteResult.getOrThrow()
                val planResult = runCatching {
                    FingerprintSyncPlanner.plan(local, remote.records).also {
                        FingerprintBackupCodec.validateRecords(
                            it.remoteMerged, activeMatcherMetadata, now(), allowEmpty = true,
                        )
                    }
                }
                if (planResult.isFailure) {
                    callback(Result.failure(planResult.exceptionOrNull()!!))
                    return@downloadRemote
                }
                val plan = planResult.getOrThrow()
                if (!remote.exists && plan.remoteMerged.isEmpty()) {
                    callback(Result.success(FingerprintSyncSummary(0, 0, 0)))
                    return@downloadRemote
                }
                upload(configuration, plan.remoteMerged) { uploadResult ->
                    if (uploadResult.isFailure) {
                        callback(Result.failure(uploadResult.exceptionOrNull()!!))
                        return@upload
                    }
                    insertLocalAdditions(plan.localAdditions) { insertResult ->
                        callback(insertResult.map {
                            FingerprintSyncSummary(
                                localEnrollmentCount = plan.remoteMerged.groupBy { record -> record.enrollmentId }.size,
                                uploadedEnrollmentCount = plan.localOnlyEnrollmentCount,
                                downloadedEnrollmentCount = plan.localAdditions.groupBy { record -> record.enrollmentId }.size,
                            )
                        })
                    }
                }
            }
        }.apply { name = "fingerprint-sync-local-read" }.start()
    }

    fun deleteEnrollment(enrollmentId: String, callback: (Result<Unit>) -> Unit) {
        if (enrollmentId.isBlank()) {
            callback(Result.failure(BackupFailure("Fingerprint deletion failed.")))
            return
        }
        val configuration = configuration(callback) ?: return
        downloadRemote(configuration) { remoteResult ->
            if (remoteResult.isFailure) {
                callback(Result.failure(remoteResult.exceptionOrNull()!!))
                return@downloadRemote
            }
            val remote = remoteResult.getOrThrow()
            val remaining = remote.records.filterNot { it.enrollmentId == enrollmentId }
            val finishLocalDelete = {
                Thread {
                    when (repository.deleteByEnrollmentId(enrollmentId)) {
                        is RepositoryResult.Success -> callback(Result.success(Unit))
                        is RepositoryResult.Error -> callback(Result.failure(BackupFailure("Fingerprint deletion failed.")))
                    }
                }.apply { name = "fingerprint-delete-local" }.start()
            }
            if (!remote.exists || remaining.size == remote.records.size) {
                finishLocalDelete()
            } else {
                upload(configuration, remaining) { uploadResult ->
                    if (uploadResult.isSuccess) finishLocalDelete()
                    else callback(Result.failure(BackupFailure(
                        "Fingerprint deletion failed because Firebase could not be updated.",
                    )))
                }
            }
        }
    }

    /** Deletes only the device recovery object; local fingerprint records remain untouched. */
    fun deleteCloudBackup(callback: (Result<Unit>) -> Unit) {
        val configuration = configuration(callback) ?: return
        storage.reference.child(storagePath(configuration.deviceId)).delete()
            .addOnSuccessListener { callback(Result.success(Unit)) }
            .addOnFailureListener { error ->
                if (error is StorageException && error.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) callback(Result.success(Unit))
                else callback(Result.failure(BackupFailure("Fingerprint cloud backup could not be deleted.")))
            }
    }

    fun readCloudMetadata(callback: (Result<CloudBackupMetadata>) -> Unit) {
        val configuration = configuration(callback) ?: return
        storage.reference.child(storagePath(configuration.deviceId)).metadata.addOnSuccessListener { metadata ->
            callback(Result.success(CloudBackupMetadata(true, metadata.getCustomMetadata("recordCount")?.toIntOrNull(),
                metadata.getCustomMetadata("backupAtEpochMillis")?.toLongOrNull())))
        }.addOnFailureListener { error -> if (error is StorageException && error.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND)
            callback(Result.success(CloudBackupMetadata(false))) else callback(Result.failure(BackupFailure("Fingerprint cloud backup status could not be read."))) }
    }

    private fun loadAndValidateLocal(): List<BiometricRecord> {
        val records = when (val result = repository.getAll()) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Error -> throw BackupFailure("Local fingerprints could not be read.")
        }
        FingerprintBackupCodec.validateRecords(records, activeMatcherMetadata, now(), allowEmpty = true)
        return records
    }

    private fun downloadRemote(
        configuration: BackupConfiguration,
        callback: (Result<RemoteBackup>) -> Unit,
    ) {
        storage.reference.child(storagePath(configuration.deviceId)).getBytes(MAX_BACKUP_BYTES)
            .addOnSuccessListener { encrypted ->
                Thread {
                    callback(runCatching {
                        val backup = FingerprintBackupCodec.deserialize(
                            FingerprintBackupCrypto.decrypt(encrypted, configuration.key),
                        )
                        if (backup.deviceId != configuration.deviceId) {
                            throw BackupFailure("This fingerprint backup belongs to a different device.")
                        }
                        FingerprintBackupCodec.validateRecords(
                            backup.records, activeMatcherMetadata, now(), backup.createdAtEpochMillis, allowEmpty = true,
                        )
                        RemoteBackup(exists = true, records = backup.records)
                    })
                }.apply { name = "fingerprint-sync-decrypt" }.start()
            }
            .addOnFailureListener { error ->
                if (error is StorageException && error.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) {
                    callback(Result.success(RemoteBackup(exists = false, records = emptyList())))
                } else callback(Result.failure(BackupFailure("Fingerprint synchronization failed.")))
            }
    }

    private fun upload(
        configuration: BackupConfiguration,
        records: List<BiometricRecord>,
        callback: (Result<Unit>) -> Unit,
    ) {
        Thread {
            val prepared = runCatching {
                FingerprintBackupCodec.validateRecords(records, activeMatcherMetadata, now(), allowEmpty = true)
                val backupAt = now()
                FingerprintBackupCrypto.encrypt(FingerprintBackupCodec.serialize(configuration.deviceId, backupAt, records), configuration.key) to backupAt
            }
            if (prepared.isFailure) {
                callback(Result.failure(prepared.exceptionOrNull()!!))
                return@Thread
            }
            val (encrypted, backupAt) = prepared.getOrThrow()
            val metadata = StorageMetadata.Builder().setCustomMetadata("backupType", "FINGERPRINT")
                .setCustomMetadata("schemaVersion", BACKUP_SCHEMA_VERSION.toString())
                .setCustomMetadata("recordCount", records.groupBy { it.enrollmentId }.size.toString())
                .setCustomMetadata("backupAtEpochMillis", backupAt.toString()).build()
            storage.reference.child(storagePath(configuration.deviceId)).putBytes(encrypted, metadata)
                .addOnSuccessListener { callback(Result.success(Unit)) }
                .addOnFailureListener { callback(Result.failure(BackupFailure("Fingerprint synchronization failed."))) }
        }.apply { name = "fingerprint-sync-encrypt" }.start()
    }

    private fun insertLocalAdditions(records: List<BiometricRecord>, callback: (Result<Unit>) -> Unit) {
        if (records.isEmpty()) {
            callback(Result.success(Unit))
            return
        }
        Thread {
            when (repository.insertMissingEnrollments(records)) {
                is RepositoryResult.Success -> callback(Result.success(Unit))
                is RepositoryResult.Error -> callback(Result.failure(BackupFailure(
                    "Remote fingerprints are safe, but local synchronization failed. Please retry.",
                )))
            }
        }.apply { name = "fingerprint-sync-local-insert" }.start()
    }

    private fun <T> configuration(callback: (Result<T>) -> Unit): BackupConfiguration? {
        if (auth.currentUser == null) {
            callback(Result.failure(BackupFailure("Administrator authentication is required.")))
            return null
        }
        val deviceId = deviceConfiguration.deviceId().trim()
        if (deviceId.isBlank() || deviceId.contains('/') || deviceId.contains('\\') || deviceId in setOf(".", "..")) {
            callback(Result.failure(BackupFailure("A valid device ID is required for fingerprint synchronization.")))
            return null
        }
        val key = try {
            PortableBackupCrypto.configuredKey()
        } catch (_: Exception) {
            null
        }
        if (key == null) {
            callback(Result.failure(BackupFailure("Fingerprint synchronization encryption is not configured correctly.")))
            return null
        }
        return BackupConfiguration(deviceId, key)
    }

    private data class BackupConfiguration(val deviceId: String, val key: ByteArray)
    private data class RemoteBackup(val exists: Boolean, val records: List<BiometricRecord>)

    companion object {
        const val BACKUP_SCHEMA_VERSION = 1
        private const val MAX_BACKUP_BYTES = 32L * 1024L * 1024L
        fun storagePath(deviceId: String) = "fingerprint-backups/$deviceId/latest.fpbackup"
    }
}

data class FingerprintSyncSummary(
    val localEnrollmentCount: Int,
    val uploadedEnrollmentCount: Int,
    val downloadedEnrollmentCount: Int,
)

open class BackupFailure(message: String) : Exception(message)
class FingerprintSynchronizationConflict : BackupFailure("Synchronization conflict detected.")

internal data class FingerprintSyncPlan(
    val remoteMerged: List<BiometricRecord>,
    val localAdditions: List<BiometricRecord>,
    val localOnlyEnrollmentCount: Int,
)

internal object FingerprintSyncPlanner {
    fun plan(local: List<BiometricRecord>, remote: List<BiometricRecord>): FingerprintSyncPlan {
        val localByEnrollment = local.groupBy { it.enrollmentId }
        val remoteByEnrollment = remote.groupBy { it.enrollmentId }
        localByEnrollment.keys.intersect(remoteByEnrollment.keys).forEach { enrollmentId ->
            if (!identical(localByEnrollment.getValue(enrollmentId), remoteByEnrollment.getValue(enrollmentId))) {
                throw FingerprintSynchronizationConflict()
            }
        }
        val localOnlyIds = localByEnrollment.keys - remoteByEnrollment.keys
        val remoteOnlyIds = remoteByEnrollment.keys - localByEnrollment.keys
        return FingerprintSyncPlan(
            remoteMerged = (remote + local.filter { it.enrollmentId in localOnlyIds }).sortedWith(recordOrder),
            localAdditions = remote.filter { it.enrollmentId in remoteOnlyIds }.sortedWith(recordOrder),
            localOnlyEnrollmentCount = localOnlyIds.size,
        )
    }

    private fun identical(left: List<BiometricRecord>, right: List<BiometricRecord>): Boolean {
        val first = left.sortedBy { it.templateSlot }
        val second = right.sortedBy { it.templateSlot }
        return first.size == second.size && first.indices.all { index -> identical(first[index], second[index]) }
    }

    private fun identical(left: BiometricRecord, right: BiometricRecord): Boolean =
        left.recordId == right.recordId && left.enrollmentId == right.enrollmentId &&
            left.employeeId == right.employeeId && left.fingerPosition == right.fingerPosition &&
            left.templateSlot == right.templateSlot && left.template.metadata == right.template.metadata &&
            left.template.bytes().contentEquals(right.template.bytes()) &&
            left.createdAtEpochMillis == right.createdAtEpochMillis &&
            left.updatedAtEpochMillis == right.updatedAtEpochMillis

    private val recordOrder = compareBy<BiometricRecord>({ it.enrollmentId }, { it.templateSlot })
}

private data class FingerprintBackup(
    val deviceId: String,
    val createdAtEpochMillis: Long,
    val records: List<BiometricRecord>,
)

private object FingerprintBackupCodec {
    private const val MAX_CLOCK_SKEW_MILLIS = 24L * 60L * 60L * 1000L

    fun serialize(deviceId: String, createdAtEpochMillis: Long, records: List<BiometricRecord>): ByteArray {
        val root = JSONObject()
            .put("backupSchemaVersion", FingerprintBackupService.BACKUP_SCHEMA_VERSION)
            .put("originatingAppVersionCode", BuildConfig.VERSION_CODE)
            .put("originatingAppVersionName", BuildConfig.VERSION_NAME)
            .put("deviceId", deviceId)
            .put("createdAtEpochMillis", createdAtEpochMillis)
        val serializedRecords = JSONArray()
        records.forEach { record ->
            val metadata = record.template.metadata
            serializedRecords.put(JSONObject()
                .put("id", record.recordId)
                .put("enrollmentId", record.enrollmentId)
                .put("employeeId", record.employeeId)
                .put("fingerPosition", record.fingerPosition.persistedValue)
                .put("templateSlot", record.templateSlot)
                .put("matcherEngine", metadata.engine)
                .put("matcherImplementationVersion", metadata.implementationVersion)
                .put("templateFormat", metadata.templateFormat)
                .put("templateFormatVersion", metadata.templateFormatVersion)
                .put("templateBytes", Base64.encodeToString(record.template.bytes(), Base64.NO_WRAP))
                .put("createdAtEpochMillis", record.createdAtEpochMillis)
                .put("updatedAtEpochMillis", record.updatedAtEpochMillis))
        }
        root.put("records", serializedRecords)
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun deserialize(plaintext: ByteArray): FingerprintBackup {
        try {
            val root = JSONObject(String(plaintext, StandardCharsets.UTF_8))
            if (root.getInt("backupSchemaVersion") != FingerprintBackupService.BACKUP_SCHEMA_VERSION) {
                throw BackupFailure("This fingerprint backup format is not supported.")
            }
            if (root.getLong("originatingAppVersionCode") <= 0 || root.getString("originatingAppVersionName").isBlank()) {
                throw BackupFailure("The fingerprint backup metadata is invalid.")
            }
            val values = root.getJSONArray("records")
            val records = ArrayList<BiometricRecord>(values.length())
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                val fingerPosition = FingerPosition.fromPersistedValue(value.getString("fingerPosition"))
                    ?: throw BackupFailure("The fingerprint backup contains an unknown finger position.")
                val bytes = try {
                    Base64.decode(value.getString("templateBytes"), Base64.DEFAULT)
                } catch (_: IllegalArgumentException) {
                    throw BackupFailure("The fingerprint backup contains invalid template data.")
                }
                records += BiometricRecord(
                    recordId = value.getString("id"),
                    enrollmentId = value.getString("enrollmentId"),
                    employeeId = value.getString("employeeId"),
                    fingerPosition = fingerPosition,
                    templateSlot = value.getInt("templateSlot"),
                    template = FingerprintTemplate(MatcherMetadata(
                        value.getString("matcherEngine"), value.getString("matcherImplementationVersion"),
                        value.getString("templateFormat"), value.getInt("templateFormatVersion"),
                    ), bytes),
                    createdAtEpochMillis = value.getLong("createdAtEpochMillis"),
                    updatedAtEpochMillis = value.getLong("updatedAtEpochMillis"),
                )
            }
            return FingerprintBackup(root.getString("deviceId"), root.getLong("createdAtEpochMillis"), records)
        } catch (error: BackupFailure) {
            throw error
        } catch (_: Exception) {
            throw BackupFailure("The fingerprint backup data is invalid.")
        }
    }

    fun validateRecords(
        records: List<BiometricRecord>,
        activeMetadata: MatcherMetadata,
        currentTimeMillis: Long,
        backupCreatedAtEpochMillis: Long = currentTimeMillis,
        allowEmpty: Boolean = false,
    ) {
        if (backupCreatedAtEpochMillis <= 0 || backupCreatedAtEpochMillis > currentTimeMillis + MAX_CLOCK_SKEW_MILLIS) {
            throw BackupFailure("The fingerprint backup timestamp is invalid.")
        }
        if (records.isEmpty()) {
            if (allowEmpty) return else throw BackupFailure("The fingerprint backup contains no fingerprints.")
        }
        if (records.any {
                it.recordId.isBlank() || it.enrollmentId.isBlank() || it.employeeId.isBlank() ||
                    it.templateSlot !in BiometricRecord.VALID_TEMPLATE_SLOTS || it.template.byteCount == 0 ||
                    it.createdAtEpochMillis <= 0 || it.updatedAtEpochMillis < it.createdAtEpochMillis ||
                    it.createdAtEpochMillis > backupCreatedAtEpochMillis + MAX_CLOCK_SKEW_MILLIS ||
                    it.updatedAtEpochMillis > backupCreatedAtEpochMillis + MAX_CLOCK_SKEW_MILLIS
            }) throw BackupFailure("The fingerprint backup contains invalid records.")
        if (records.any { it.template.metadata != activeMetadata }) {
            throw BackupFailure("The fingerprint backup is incompatible with this app version.")
        }
        if (records.map { it.recordId }.distinct().size != records.size ||
            records.map { it.enrollmentId to it.templateSlot }.distinct().size != records.size ||
            records.map { Triple(it.employeeId, it.fingerPosition, it.templateSlot) }.distinct().size != records.size) {
            throw FingerprintSynchronizationConflict()
        }
        records.groupBy { it.enrollmentId }.values.forEach { enrollment ->
            if (enrollment.size != 5 || enrollment.map { it.templateSlot }.toSet() != (1..5).toSet() ||
                enrollment.map { it.employeeId }.distinct().size != 1 ||
                enrollment.map { it.fingerPosition }.distinct().size != 1) {
                throw BackupFailure("The fingerprint backup contains an incomplete enrollment.")
            }
        }
    }
}

private object FingerprintBackupCrypto {
    private val magic = byteArrayOf('F'.code.toByte(), 'P'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    fun encrypt(plaintext: ByteArray, key: ByteArray) = PortableBackupCrypto.encrypt(plaintext, key, magic)
    fun decrypt(envelope: ByteArray, key: ByteArray) = try { PortableBackupCrypto.decrypt(envelope, key, magic) }
    catch (_: Exception) { throw BackupFailure("Fingerprint backup authentication or decryption failed.") }
}
