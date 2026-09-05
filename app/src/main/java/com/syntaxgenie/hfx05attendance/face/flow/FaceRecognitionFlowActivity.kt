package com.syntaxgenie.hfx05attendance.face.flow

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.syntaxgenie.hfx05attendance.R
import com.syntaxgenie.hfx05attendance.BuildConfig
import com.syntaxgenie.hfx05attendance.employee.local.EmployeeDirectoryDatabase
import com.syntaxgenie.hfx05attendance.employee.local.LocalEmployeeDirectory
import com.syntaxgenie.hfx05attendance.face.calibration.FaceCalibrationFirestoreRepository
import com.syntaxgenie.hfx05attendance.face.calibration.FaceCalibrationSelectionType
import com.syntaxgenie.hfx05attendance.face.calibration.FaceRecognitionConfigMode
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import kotlin.math.roundToInt

/** UI-only attendance navigation. Choosing an alternate profile never proves identity. */
class FaceRecognitionFlowActivity : AppCompatActivity() {
    private lateinit var content: FrameLayout
    private var candidates = emptyList<FaceCandidate>()
    private var selected: FaceCandidate? = null
    private var currentState = FaceFlowState.PROCESSING
    private var calibrationEventRecorded = false
    private val candidateSelectionPolicy = FaceCandidateSelectionPolicy()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        calibrationEventRecorded = state?.getBoolean(STATE_CALIBRATION_EVENT_RECORDED, false) ?: false
        setContentView(R.layout.activity_face_recognition_flow)
        KioskWindowInsets.apply(this, findViewById(R.id.faceFlowRoot))
        content = findViewById(R.id.faceFlowContent)
        val ids = intent.getStringArrayListExtra(EXTRA_CANDIDATE_IDS).orEmpty().take(3)
        val scores = intent.getDoubleArrayExtra(EXTRA_CANDIDATE_SCORES) ?: doubleArrayOf()
        val seeds = ids.mapIndexed { index, employeeId -> FaceCandidateSeed(employeeId, scores.getOrElse(index) { 0.0 }) }
        render(FaceFlowState.PROCESSING)
        Thread {
            val loaded = runCatching {
                val employees = LocalEmployeeDirectory(
                    EmployeeDirectoryDatabase.create(applicationContext).employeeDao(),
                ).getAll().associateBy { it.employeeId }
                seeds.mapNotNull { seed -> employees[seed.employeeId]?.let { FaceCandidate(it.employeeId, it.displayName, seed.score) } }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loaded.onSuccess { resolved ->
                    candidates = resolved
                    render(if (candidates.isEmpty()) FaceFlowState.NO_TEMPLATES else FaceFlowState.BEST_CANDIDATE)
                }.onFailure { render(FaceFlowState.ERROR) }
            }
        }.apply { name = "face-flow-candidate-load" }.start()
    }

    private fun render(state: FaceFlowState) {
        currentState = state
        content.removeAllViews()
        when (state) {
            FaceFlowState.BEST_CANDIDATE -> renderBestCandidate()
            FaceFlowState.CANDIDATE_LIST -> renderCandidateList()
            FaceFlowState.ATTENDANCE_ACTION -> renderAttendanceAction()
            FaceFlowState.UNKNOWN -> renderMessage("Face Not Recognized", "Please look at the camera and try again.")
            FaceFlowState.AMBIGUOUS -> renderAmbiguous()
            FaceFlowState.NO_TEMPLATES -> renderMessage("No Face Registrations", "Please contact an administrator.")
            FaceFlowState.ERROR -> renderMessage("Face Recognition Unavailable", "Please try again or contact an administrator.")
            else -> renderMessage("", "Please wait...")
        }
    }

    private fun renderBestCandidate() {
        val employee = candidates.first()
        content.addView(employeeCard(employee, R.drawable.face_profile_card_blue, showScore = false) {
            selected = employee
            recordCalibrationSelection(FaceCalibrationSelectionType.TOP_MATCH_ACCEPTED, employee)
            render(FaceFlowState.ATTENDANCE_ACTION)
        }, frameParams(Gravity.CENTER))
        content.addView(actionButton("NOT YOU?", R.drawable.face_action_not_you, 64) {
            render(FaceFlowState.CANDIDATE_LIST)
        }, frameParams(Gravity.BOTTOM, bottomMargin = 8))
    }

    private fun renderCandidateList() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(title("Select Your Employee Number"))
        val config = FaceCalibrationFirestoreRepository.get(applicationContext).currentConfig()
        val displayedCandidates = if (BuildConfig.DEBUG || config.mode == FaceRecognitionConfigMode.CALIBRATION) {
            candidateSelectionPolicy.selectForDebugPreview(candidates)
        } else {
            val minimum = config.candidateMinimumScore
            val maximumGap = config.candidateMaximumGap
            if (minimum != null && maximumGap != null) {
                candidateSelectionPolicy.selectForProduction(
                    candidates,
                    FaceCandidateSelectionPolicy.CalibratedRequirements(minimum, maximumGap),
                )
            } else {
                emptyList()
            }
        }
        displayedCandidates.forEachIndexed { index, employee ->
            list.addView(employeeCard(employee, PROFILE_BACKGROUNDS[index], showScore = true) {
                selected = employee
                recordCalibrationSelection(FaceCalibrationSelectionType.ALTERNATE_SELECTED, employee)
                render(FaceFlowState.ATTENDANCE_ACTION)
            }, linearParams(topMargin = if (index == 0) 8 else 16))
        }
        if (displayedCandidates.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Please try again"
                textSize = 20f
                setTextColor(getColor(R.color.attendance_text))
            }, linearParams(topMargin = 12))
        }
        content.addView(list, frameParams(Gravity.TOP))
    }

    private fun renderAttendanceAction() {
        val employee = selected ?: candidates.firstOrNull()
        if (employee == null) {
            render(FaceFlowState.ERROR)
            return
        }
        content.addView(employeeCard(employee, R.drawable.face_profile_card_blue, showScore = false) {}, frameParams(Gravity.TOP))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(actionButton("CHECK IN", R.drawable.face_action_check_in, 72) { attendanceAction(AttendanceAction.CHECK_IN) }, linearParams())
            addView(actionButton("CHECK OUT", R.drawable.face_action_check_out, 72) { attendanceAction(AttendanceAction.CHECK_OUT) }, linearParams(topMargin = 16))
        }
        content.addView(actions, frameParams(Gravity.CENTER))
        content.addView(actionButton("CANCEL", R.drawable.face_action_cancel, 64) { returnHome() }, frameParams(Gravity.BOTTOM, bottomMargin = 8))
    }

    private fun renderAmbiguous() {
        renderMessage("Unable to Identify", "Please try again.")
        content.addView(actionButton("SELECT YOUR EMPLOYEE NUMBER", R.drawable.face_action_not_you, 64) {
            render(FaceFlowState.CANDIDATE_LIST)
        }, frameParams(Gravity.BOTTOM, bottomMargin = 8))
    }

    private fun renderMessage(title: String, body: String) {
        val message = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            if (title.isNotBlank()) addView(title(title), linearParams())
            addView(TextView(this@FaceRecognitionFlowActivity).apply {
                text = body
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.attendance_text))
            }, linearParams(topMargin = 8))
        }
        content.addView(message, frameParams(Gravity.CENTER))
    }

    private fun employeeCard(employee: FaceCandidate, background: Int, showScore: Boolean, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(164)
            setPadding(dp(24), dp(18), dp(24), dp(18))
            setBackgroundResource(background)
            isClickable = true
            isFocusable = true
            contentDescription = "Select employee number ${employee.employeeId}"
            setOnClickListener { action() }
            addView(TextView(this@FaceRecognitionFlowActivity).apply {
                text = employee.displayName
                textSize = 22f
                setTextColor(getColor(R.color.attendance_text))
            })
            addView(LinearLayout(this@FaceRecognitionFlowActivity).apply {
                gravity = Gravity.BOTTOM
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(this@FaceRecognitionFlowActivity).apply {
                    text = employee.employeeId
                    textSize = 36f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(getColor(R.color.attendance_text))
                    setPadding(0, dp(14), 0, 0)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (showScore) addView(TextView(this@FaceRecognitionFlowActivity).apply {
                    text = "${displayPercent(employee.score)}%"
                    textSize = 20f
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.END or Gravity.BOTTOM
                    setTextColor(getColor(R.color.attendance_text))
                    setPadding(dp(8), dp(18), 0, 0)
                })
            })
        }

    private fun displayPercent(score: Double): Int = (score.coerceIn(0.0, 1.0) * 100.0).roundToInt()

    private fun actionButton(label: String, background: Int, minimumHeightDp: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minimumHeight = dp(minimumHeightDp)
            setTextColor(getColor(R.color.white))
            setBackgroundResource(background)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun title(value: String) = TextView(this).apply {
        text = value
        textSize = 24f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(getColor(R.color.attendance_text))
        setPadding(0, dp(4), 0, dp(12))
    }

    private fun frameParams(gravity: Int, bottomMargin: Int = 0) = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        gravity,
    ).apply { this.bottomMargin = dp(bottomMargin) }

    private fun linearParams(topMargin: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { this.topMargin = dp(topMargin) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun attendanceAction(action: AttendanceAction) {
        Toast.makeText(this, "${if (action == AttendanceAction.CHECK_IN) "Check In" else "Check Out"} UI ready.", Toast.LENGTH_LONG).show()
        // TODO: Connect only after live multi-person face calibration is approved.
    }

    private fun recordCalibrationSelection(type: FaceCalibrationSelectionType, selectedEmployee: FaceCandidate?) {
        if (calibrationEventRecorded) return
        val top = candidates.firstOrNull() ?: return
        val second = candidates.getOrNull(1)
        calibrationEventRecorded = true
        val repository = FaceCalibrationFirestoreRepository.get(applicationContext)
        repository.recordSelection(
            repository.newEvent(
                topEmployeeId = top.employeeId,
                topScore = top.score,
                secondEmployeeId = second?.employeeId,
                secondScore = second?.score,
                selectedEmployeeId = selectedEmployee?.employeeId,
                selectionType = type,
                candidateCount = candidates.size,
            ),
        )
    }

    private fun returnHome() {
        setResult(RESULT_RETURN_HOME)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_CALIBRATION_EVENT_RECORDED, calibrationEventRecorded)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (currentState) {
            FaceFlowState.ATTENDANCE_ACTION -> returnHome()
            FaceFlowState.CANDIDATE_LIST -> {
                selected = null
                render(FaceFlowState.BEST_CANDIDATE)
            }
            FaceFlowState.BEST_CANDIDATE -> {
                recordCalibrationSelection(FaceCalibrationSelectionType.CANCELLED, null)
                super.onBackPressed()
            }
            else -> super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_CANDIDATE_IDS = "faceFlowCandidateIds"
        const val EXTRA_CANDIDATE_SCORES = "faceFlowCandidateScores"
        const val RESULT_RETURN_HOME = 9401
        private const val STATE_CALIBRATION_EVENT_RECORDED = "faceCalibrationEventRecorded"
        private val PROFILE_BACKGROUNDS = intArrayOf(
            R.drawable.face_profile_card_blue,
            R.drawable.face_profile_card_teal,
            R.drawable.face_profile_card_purple,
        )

        fun previewIntent(context: Context, candidates: List<FaceCandidateSeed>) = Intent(context, FaceRecognitionFlowActivity::class.java)
            .putStringArrayListExtra(EXTRA_CANDIDATE_IDS, ArrayList(candidates.take(3).map { it.employeeId }))
            .putExtra(EXTRA_CANDIDATE_SCORES, candidates.take(3).map { it.score }.toDoubleArray())
    }
}
