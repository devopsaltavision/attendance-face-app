package com.syntaxgenie.hfx05attendance.face.engine.opencv

import android.content.Context
import org.opencv.android.OpenCVLoader
import java.io.File
import java.security.MessageDigest
import java.io.BufferedInputStream

/** Keeps OpenCV startup and model-file preparation inside the engine boundary. */
class OpenCvEngineLifecycle(private val context: Context) {
    fun prepareYuNetModel(): File {
        check(OpenCVLoader.initLocal()) { "OpenCV native runtime could not be loaded." }
        val modelDirectory = File(context.filesDir, "opencv-models").apply { mkdirs() }
        val model = File(modelDirectory, OpenCvEngineConfiguration.YUNET_FILENAME)
        if (!model.isFile || sha256(model) != OpenCvEngineConfiguration.YUNET_SHA256) {
            context.assets.open(OpenCvEngineConfiguration.YUNET_ASSET_PATH).use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
        }
        check(sha256(model) == OpenCvEngineConfiguration.YUNET_SHA256) { "YuNet model checksum verification failed." }
        return model
    }

    fun prepareSFaceModel(): File {
        check(OpenCVLoader.initLocal()) { "OpenCV native runtime could not be loaded." }
        val directory = File(context.filesDir, "opencv-models").apply { mkdirs() }
        val model = File(directory, OpenCvEngineConfiguration.SFACE_FILENAME)
        if (!model.isFile || sha256(model) != OpenCvEngineConfiguration.SFACE_SHA256) {
            context.assets.open(OpenCvEngineConfiguration.SFACE_ASSET_PATH).use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
        }
        check(sha256(model) == OpenCvEngineConfiguration.SFACE_SHA256) { "SFace model checksum verification failed." }
        return model
    }

    fun prepareAuraFaceModel(): File {
        check(OpenCVLoader.initLocal()) { "OpenCV native runtime could not be loaded." }
        val directory = File(context.filesDir, "opencv-models").apply { mkdirs() }
        val model = File(directory, OpenCvEngineConfiguration.AURAFACE_FILENAME)
        if (!model.isFile || sha256(model) != OpenCvEngineConfiguration.AURAFACE_SHA256) {
            context.assets.open(OpenCvEngineConfiguration.AURAFACE_ASSET_PATH).use { input -> model.outputStream().use { output -> input.copyTo(output) } }
        }
        check(sha256(model) == OpenCvEngineConfiguration.AURAFACE_SHA256) { "AuraFace model checksum verification failed." }
        return model
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(file.inputStream()).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
