package com.syntaxgenie.hfx05attendance.face.scan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.syntaxgenie.hfx05attendance.face.detection.FaceRect

/** Draws the screen-space target and detection result; camera-space coordinates are transformed here. */
class FaceScanOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 210, 220, 238); style = Paint.Style.STROKE; strokeWidth = 4f }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(73, 222, 128); style = Paint.Style.STROKE; strokeWidth = 6f }
    private var face: FaceRect? = null
    private var frameWidth = 1280
    private var frameHeight = 720
    private var displayRotation = 90
    private var mirror = false

    fun show(face: FaceRect?, frameWidth: Int, frameHeight: Int, displayRotation: Int, mirror: Boolean) {
        this.face = face; this.frameWidth = frameWidth; this.frameHeight = frameHeight
        this.displayRotation = displayRotation; this.mirror = mirror; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val target = RectF(width * 0.20f, height * 0.14f, width * 0.80f, height * 0.86f)
        canvas.drawRoundRect(target, minOf(width, height) * .08f, minOf(width, height) * .08f, guidePaint)
        face?.let { canvas.drawRect(mapRect(it), facePaint) }
    }

    private fun mapRect(source: FaceRect): RectF {
        val points = listOf(point(source.left, source.top), point(source.right, source.top), point(source.left, source.bottom), point(source.right, source.bottom))
        return RectF(points.minOf { it.first }, points.minOf { it.second }, points.maxOf { it.first }, points.maxOf { it.second })
    }

    private fun point(x: Float, y: Float): Pair<Float, Float> {
        var px: Float; var py: Float; val orientedWidth: Float; val orientedHeight: Float
        when (displayRotation) {
            90 -> { px = y; py = frameWidth - x; orientedWidth = frameHeight.toFloat(); orientedHeight = frameWidth.toFloat() }
            180 -> { px = frameWidth - x; py = frameHeight - y; orientedWidth = frameWidth.toFloat(); orientedHeight = frameHeight.toFloat() }
            270 -> { px = frameHeight - y; py = x; orientedWidth = frameHeight.toFloat(); orientedHeight = frameWidth.toFloat() }
            else -> { px = x; py = y; orientedWidth = frameWidth.toFloat(); orientedHeight = frameHeight.toFloat() }
        }
        if (mirror) px = orientedWidth - px
        val scale = maxOf(width / orientedWidth, height / orientedHeight)
        return Pair((px * scale + (width - orientedWidth * scale) / 2), (py * scale + (height - orientedHeight * scale) / 2))
    }
}
