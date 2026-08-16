package com.syntaxgenie.hfx05attendance

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.syntaxgenie.hfx05attendance.attendance.AttendanceHomeModel
import com.syntaxgenie.hfx05attendance.attendance.AttendanceHomeState
import com.syntaxgenie.hfx05attendance.ui.FingerprintVisualView
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: android.view.View
    private lateinit var timeText: TextView
    private lateinit var dateText: TextView
    private lateinit var visual: FingerprintVisualView
    private lateinit var titleText: TextView
    private lateinit var instructionText: TextView
    private lateinit var detailText: TextView
    private lateinit var syncStatusText: TextView
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, MILLIS_PER_MINUTE)
        }
    }
    private val resetReady = Runnable { render(defaultReadyModel()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.attendanceHomeRoot)
        KioskWindowInsets.apply(this, root)
        timeText = findViewById(R.id.currentTime)
        dateText = findViewById(R.id.currentDate)
        visual = findViewById(R.id.fingerprintVisual)
        titleText = findViewById(R.id.attendanceStatusTitle)
        instructionText = findViewById(R.id.attendanceInstruction)
        detailText = findViewById(R.id.attendanceDetail)
        syncStatusText = findViewById(R.id.homeSyncStatus)
        findViewById<ImageButton>(R.id.adminButton).setOnClickListener {
            startActivity(Intent(this, AdminLoginActivity::class.java))
        }
        render(defaultReadyModel())
    }

    override fun onStart() {
        super.onStart()
        handler.post(clockTick)
    }

    override fun onStop() {
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(resetReady)
        super.onStop()
    }

    fun render(model: AttendanceHomeModel) {
        handler.removeCallbacks(resetReady)
        val background = when (model.state) {
            AttendanceHomeState.SUCCESS -> R.color.attendance_success
            else -> R.color.attendance_ready_background
        }
        root.setBackgroundColor(ContextCompat.getColor(this, background))
        visual.render(when (model.state) {
            AttendanceHomeState.READY -> FingerprintVisualView.State.READY
            AttendanceHomeState.SCANNING -> FingerprintVisualView.State.SCANNING
            AttendanceHomeState.SUCCESS -> FingerprintVisualView.State.SUCCESS
            AttendanceHomeState.FAILURE -> FingerprintVisualView.State.ERROR
            AttendanceHomeState.WARNING -> FingerprintVisualView.State.WARNING
        })
        titleText.text = model.title
        instructionText.text = model.instruction.orEmpty()
        detailText.text = listOfNotNull(model.employeeName, model.employeeId,
            model.attendanceActionLabel, model.attendanceTimeLabel).joinToString("\n")
        syncStatusText.visibility = if (model.state == AttendanceHomeState.SUCCESS) android.view.View.INVISIBLE
            else android.view.View.VISIBLE
        val textColor = ContextCompat.getColor(this,
            if (model.state == AttendanceHomeState.SUCCESS) R.color.white else R.color.attendance_text)
        listOf(timeText, dateText, titleText, instructionText, detailText).forEach { it.setTextColor(textColor) }
        if (model.state == AttendanceHomeState.SUCCESS) handler.postDelayed(resetReady, SUCCESS_DURATION_MS)
    }

    private fun defaultReadyModel() = AttendanceHomeModel(
        state = AttendanceHomeState.READY,
        title = getString(R.string.ready_to_scan),
        instruction = getString(R.string.place_finger_on_sensor),
    )

    private fun updateClock() {
        val now = Date()
        timeText.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
        dateText.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val SUCCESS_DURATION_MS = 2_000L
    }
}
