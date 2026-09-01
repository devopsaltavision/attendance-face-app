@file:Suppress("DEPRECATION")

package com.syntaxgenie.hfx05attendance.face.camera

import android.graphics.ImageFormat
import android.hardware.Camera

data class FaceCameraInfo(
    val id: Int,
    val facing: Int,
    val orientation: Int,
    val previewSizes: List<String>,
    val previewFormats: List<Int>,
) {
    fun usefulReport(): String = buildString {
        appendLine("Camera $id")
        appendLine("Facing: ${facingName(facing)} ($facing)")
        appendLine("Orientation: $orientation degrees")
        appendLine("Preview sizes: ${previewSizes.joinToString().ifEmpty { "unavailable" }}")
        append("Preview formats: ${previewFormats.joinToString { formatName(it) }.ifEmpty { "unavailable" }}")
    }

    companion object {
        fun facingName(value: Int): String = when (value) {
            Camera.CameraInfo.CAMERA_FACING_BACK -> "BACK"
            Camera.CameraInfo.CAMERA_FACING_FRONT -> "FRONT"
            else -> "UNKNOWN"
        }

        fun formatName(value: Int): String = when (value) {
            ImageFormat.NV21 -> "NV21 ($value)"
            ImageFormat.YV12 -> "YV12 ($value)"
            ImageFormat.JPEG -> "JPEG ($value)"
            ImageFormat.YUY2 -> "YUY2 ($value)"
            else -> "format $value"
        }
    }
}
