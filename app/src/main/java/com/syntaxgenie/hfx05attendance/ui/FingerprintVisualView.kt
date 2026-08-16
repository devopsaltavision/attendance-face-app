package com.syntaxgenie.hfx05attendance.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.syntaxgenie.hfx05attendance.R

enum class FingerprintZoneState { PENDING, ACTIVE, COMPLETE }

class FingerprintVisualView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    enum class State { READY, SCANNING, SUCCESS, ERROR, WARNING }
    enum class Zone { CENTER, TOP, LEFT, RIGHT, BOTTOM }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val guide = Path().apply {
        moveTo(50f, 120f); cubicTo(50f, 50f, 150f, 50f, 150f, 120f)
        cubicTo(150f, 190f, 130f, 210f, 100f, 210f)
        cubicTo(70f, 210f, 50f, 190f, 50f, 120f); close()
    }
    private val paths = linkedMapOf(
        Zone.CENTER to Path().apply {
            moveTo(90f,120f); cubicTo(90f,105f,110f,105f,110f,120f); cubicTo(110f,135f,95f,140f,95f,150f)
            moveTo(78f,115f); cubicTo(78f,90f,122f,90f,122f,115f); cubicTo(122f,135f,108f,145f,108f,160f)
        },
        Zone.TOP to Path().apply {
            moveTo(75f,55f); cubicTo(88f,43f,112f,43f,125f,55f)
            moveTo(65f,70f); cubicTo(85f,50f,115f,50f,135f,70f)
        },
        Zone.LEFT to Path().apply {
            moveTo(45f,105f); cubicTo(45f,85f,55f,75f,65f,70f)
            moveTo(40f,130f); cubicTo(40f,100f,50f,85f,60f,80f)
            moveTo(45f,155f); cubicTo(42f,140f,42f,120f,52f,105f)
        },
        Zone.RIGHT to Path().apply {
            moveTo(135f,70f); cubicTo(145f,75f,155f,85f,155f,105f)
            moveTo(140f,80f); cubicTo(150f,85f,160f,100f,160f,130f)
            moveTo(148f,105f); cubicTo(158f,120f,158f,140f,155f,155f)
        },
        Zone.BOTTOM to Path().apply {
            moveTo(60f,170f); cubicTo(75f,185f,125f,185f,140f,170f)
            moveTo(70f,190f); cubicTo(85f,200f,115f,200f,130f,190f)
        },
    )
    private var zoneStates = Zone.entries.associateWith { FingerprintZoneState.PENDING }
    private var wholeColor: Int? = null
    private var pulseAlpha = 1f
    private var pulse: ValueAnimator? = null

    init { render(State.READY) }

    fun render(state: State) {
        stopPulse()
        val zoneState = when (state) {
            State.READY -> FingerprintZoneState.PENDING
            State.SCANNING -> FingerprintZoneState.ACTIVE
            State.SUCCESS -> FingerprintZoneState.COMPLETE
            State.ERROR, State.WARNING -> FingerprintZoneState.ACTIVE
        }
        wholeColor = ContextCompat.getColor(context, when (state) {
            State.READY -> R.color.attendance_ready_visual
            State.SCANNING -> R.color.attendance_scanning
            State.SUCCESS -> R.color.attendance_success_foreground
            State.ERROR -> R.color.attendance_failure
            State.WARNING -> R.color.attendance_warning
        })
        zoneStates = Zone.entries.associateWith { zoneState }
        if (state == State.SCANNING) startPulse()
        invalidate()
    }

    /** Visual progress only. Every step still captures one complete fingerprint image. */
    fun renderEnrollmentProgress(completedCaptures: Int, requiredCaptures: Int = 5) {
        stopPulse()
        wholeColor = null
        val states = EnrollmentVisualProgress.zoneStates(completedCaptures, requiredCaptures)
        zoneStates = Zone.entries.mapIndexed { index, zone -> zone to states[index] }.toMap()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 200f, height / 240f)
        canvas.save()
        canvas.translate((width - 200f * scale) / 2f, (height - 240f * scale) / 2f)
        canvas.scale(scale, scale)
        paint.strokeWidth = 4f
        paint.color = ContextCompat.getColor(context, R.color.fingerprint_guide)
        paint.alpha = 255
        canvas.drawPath(guide, paint)
        paint.strokeWidth = 6f
        paths.forEach { (zone, path) ->
            paint.color = wholeColor ?: ContextCompat.getColor(context, when (zoneStates.getValue(zone)) {
                FingerprintZoneState.PENDING -> R.color.fingerprint_zone_pending
                FingerprintZoneState.ACTIVE -> R.color.fingerprint_zone_active
                FingerprintZoneState.COMPLETE -> R.color.fingerprint_zone_complete
            })
            paint.alpha = if (zoneStates.getValue(zone) == FingerprintZoneState.ACTIVE) (255 * pulseAlpha).toInt() else 255
            canvas.drawPath(path, paint)
        }
        canvas.restore()
    }

    private fun startPulse() {
        pulse = ValueAnimator.ofFloat(1f, .55f, 1f).apply {
            duration = 1_300L; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { pulseAlpha = it.animatedValue as Float; invalidate() }; start()
        }
    }

    private fun stopPulse() { pulse?.cancel(); pulse = null; pulseAlpha = 1f }
    override fun onDetachedFromWindow() { stopPulse(); super.onDetachedFromWindow() }
}
