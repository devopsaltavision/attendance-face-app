package com.syntaxgenie.hfx05attendance.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class ApplicationRelease(
    val versionCode: Int,
    val versionName: String,
    val apkPath: String,
    val sha256: String,
    val mandatory: Boolean,
    val releaseNotes: String?,
)

class ApplicationUpdateService(context: Context) {
    private val appContext = context.applicationContext
    private val storage = FirebaseStorage.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun checkForUpdate(callback: (Result<ApplicationRelease>) -> Unit) {
        if (auth.currentUser == null) {
            callback(Result.failure(IllegalStateException("Administrator authentication is required.")))
            return
        }

        storage.reference.child(METADATA_PATH).getBytes(MAX_METADATA_BYTES)
            .addOnSuccessListener { bytes ->
                callback(runCatching { parseRelease(bytes.toString(Charsets.UTF_8)) })
            }
            .addOnFailureListener { error -> callback(Result.failure(error)) }
    }

    fun downloadAndVerify(
        release: ApplicationRelease,
        callback: (Result<File>) -> Unit,
    ) {
        if (auth.currentUser == null) {
            callback(Result.failure(IllegalStateException("Administrator authentication is required.")))
            return
        }

        val updateDirectory = File(appContext.cacheDir, UPDATE_CACHE_DIRECTORY).apply { mkdirs() }
        val destination = File(updateDirectory, "hfx05-update-${release.versionCode}.apk")
        destination.delete()

        storage.reference.child(release.apkPath).getFile(destination)
            .addOnSuccessListener {
                Thread {
                    val result = runCatching {
                        val actualChecksum = destination.inputStream().use { input ->
                            val digest = MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                digest.update(buffer, 0, count)
                            }
                            digest.digest().joinToString("") { byte ->
                                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                            }
                        }
                        check(actualChecksum.equals(release.sha256, ignoreCase = true)) {
                            "Downloaded update checksum does not match metadata."
                        }
                        destination
                    }
                    if (result.isFailure) destination.delete()
                    mainHandler.post { callback(result) }
                }.start()
            }
            .addOnFailureListener { error ->
                destination.delete()
                callback(Result.failure(error))
            }
    }

    private fun parseRelease(json: String): ApplicationRelease {
        val metadata = JSONObject(json)
        val versionCode = metadata.getInt("versionCode")
        val versionName = metadata.getString("versionName").trim()
        val apkPath = metadata.getString("apkPath").trim()
        val sha256 = metadata.getString("sha256").trim()
        val releaseNotes = metadata.optString("releaseNotes").trim().ifEmpty { null }

        require(versionCode > 0) { "Invalid update versionCode." }
        require(versionName.isNotEmpty()) { "Invalid update versionName." }
        require(
            apkPath.startsWith(APK_DIRECTORY) &&
                apkPath.endsWith(".apk", ignoreCase = true) &&
                !apkPath.contains("..") &&
                !apkPath.contains("://"),
        ) { "Invalid update APK path." }
        require(SHA_256_PATTERN.matches(sha256)) { "Invalid update checksum." }

        return ApplicationRelease(
            versionCode = versionCode,
            versionName = versionName,
            apkPath = apkPath,
            sha256 = sha256,
            mandatory = metadata.optBoolean("mandatory", false),
            releaseNotes = releaseNotes,
        )
    }

    companion object {
        const val METADATA_PATH = "app-releases/hfx05/latest.json"
        const val UPDATE_CACHE_DIRECTORY = "application_updates"
        private const val APK_DIRECTORY = "app-releases/hfx05/"
        private const val MAX_METADATA_BYTES = 256L * 1024L
        private val SHA_256_PATTERN = Regex("^[A-Fa-f0-9]{64}$")
    }
}
