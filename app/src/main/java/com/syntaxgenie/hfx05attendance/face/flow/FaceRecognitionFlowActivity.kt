package com.syntaxgenie.hfx05attendance.face.flow

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.syntaxgenie.hfx05attendance.R
import com.syntaxgenie.hfx05attendance.attendance.AttendanceRecordOutcome
import com.syntaxgenie.hfx05attendance.attendance.AttendanceRecordStatus
import com.syntaxgenie.hfx05attendance.attendance.AttendanceBusinessRejection
import com.syntaxgenie.hfx05attendance.attendance.AttendanceService
import com.syntaxgenie.hfx05attendance.attendance.PendingAttendanceSyncScheduler
import com.syntaxgenie.hfx05attendance.attendance.local.LocalAttendanceRepository
import com.syntaxgenie.hfx05attendance.backend.BackendApiError
import com.syntaxgenie.hfx05attendance.backend.FingerprintApiClient
import com.syntaxgenie.hfx05attendance.backend.config.BackendEnvironmentConfig
import com.syntaxgenie.hfx05attendance.backend.config.DeviceConfigurationRepository
import com.syntaxgenie.hfx05attendance.BuildConfig
import com.syntaxgenie.hfx05attendance.employee.local.EmployeeDirectoryDatabase
import com.syntaxgenie.hfx05attendance.employee.local.LocalEmployeeDirectory
import com.syntaxgenie.hfx05attendance.face.calibration.FaceCalibrationFirestoreRepository
import com.syntaxgenie.hfx05attendance.face.calibration.FaceCalibrationSelectionType
import com.syntaxgenie.hfx05attendance.face.calibration.FaceRecognitionConfigMode
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import com.syntaxgenie.hfx05attendance.ui.AppSoundManager
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicBoolean

/** UI-only attendance navigation. Choosing an alternate profile never proves identity. */
internal class FaceAttendanceSubmissionGate {
    private val busy = AtomicBoolean(false)
    fun tryAcquire() = busy.compareAndSet(false, true)
    fun release() = busy.set(false)
}

class FaceRecognitionFlowActivity : AppCompatActivity() {
    private lateinit var content: FrameLayout
    private var candidates = emptyList<FaceCandidate>()
    private var selected: FaceCandidate? = null
    private var currentState = FaceFlowState.PROCESSING
    private var calibrationEventRecorded = false
    private val candidateSelectionPolicy = FaceCandidateSelectionPolicy()
    private var productionDecision: FaceRecognitionDecision? = null
    private var productionAmbiguousSeeds = emptyList<FaceCandidateSeed>()
    private val attendanceSubmitting = FaceAttendanceSubmissionGate()
    private val soundManager by lazy { AppSoundManager(applicationContext) }
    private var recognitionSoundPlayed = false
    private val employeeDatabase by lazy { EmployeeDirectoryDatabase.create(applicationContext) }
    private val deviceConfiguration by lazy { DeviceConfigurationRepository(this) }
    private val attendanceService by lazy {
        val environment = BackendEnvironmentConfig()
        AttendanceService(FingerprintApiClient(environment).create(), environment, deviceConfiguration::deviceId,
            LocalAttendanceRepository(employeeDatabase.attendanceDao()), ::networkAvailable,
            pendingSyncScheduler = { PendingAttendanceSyncScheduler.enqueue(applicationContext) })
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        calibrationEventRecorded = state?.getBoolean(STATE_CALIBRATION_EVENT_RECORDED, false) ?: false
        recognitionSoundPlayed = state?.getBoolean(STATE_RECOGNITION_SOUND_PLAYED, false) ?: false
        setContentView(R.layout.activity_face_recognition_flow)
        KioskWindowInsets.apply(this, findViewById(R.id.faceFlowRoot))
        content = findViewById(R.id.faceFlowContent)
        val ids = intent.getStringArrayListExtra(EXTRA_CANDIDATE_IDS).orEmpty().take(3)
        val scores = intent.getDoubleArrayExtra(EXTRA_CANDIDATE_SCORES) ?: doubleArrayOf()
        productionDecision = intent.getStringExtra(EXTRA_PRODUCTION_DECISION)?.let { runCatching { FaceRecognitionDecision.valueOf(it) }.getOrNull() }
        productionAmbiguousSeeds = intent.getStringArrayListExtra(EXTRA_AMBIGUOUS_CANDIDATE_IDS).orEmpty()
            .mapIndexed { index, employeeId -> FaceCandidateSeed(employeeId, intent.getDoubleArrayExtra(EXTRA_AMBIGUOUS_CANDIDATE_SCORES)?.getOrElse(index) { Double.NEGATIVE_INFINITY } ?: Double.NEGATIVE_INFINITY) }
        if (productionDecision != null) {
            loadProductionResult(intent.getStringExtra(EXTRA_PRODUCTION_EMPLOYEE_ID))
            return
        }
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

    private fun loadProductionResult(employeeId: String?) {
        render(FaceFlowState.PROCESSING)
        Thread {
            val employees = LocalEmployeeDirectory(EmployeeDirectoryDatabase.create(applicationContext).employeeDao()).getAll()
            val employee = employeeId?.let { id -> employees.firstOrNull { row -> row.employeeId == id } }
            val ambiguous = productionAmbiguousSeeds.mapNotNull { seed -> employees.firstOrNull { it.employeeId == seed.employeeId }
                ?.let { FaceCandidate(it.employeeId, it.displayName, seed.score) } }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val decision = productionDecision ?: FaceRecognitionDecision.UNKNOWN
                if (decision == FaceRecognitionDecision.MATCHED && employee != null) {
                    candidates = listOf(FaceCandidate(employee.employeeId, employee.displayName, 0.0))
                    playRecognitionSoundOnce(AppSoundManager.Event.SUCCESS)
                    render(FaceFlowState.BEST_CANDIDATE)
                } else {
                    candidates = ambiguous
                    renderProductionResult(decision)
                }
            }
        }.apply { name = "face-production-result-load" }.start()
    }

    private fun renderProductionResult(decision: FaceRecognitionDecision) {
        content.removeAllViews()
        when (decision) {
            FaceRecognitionDecision.MATCHED -> render(FaceFlowState.ERROR)
            FaceRecognitionDecision.UNKNOWN -> {
                playRecognitionSoundOnce(AppSoundManager.Event.ERROR)
                renderRetryResult("Face Not Recognized", "We couldn't identify you.\nPlease try again.")
            }
            FaceRecognitionDecision.AMBIGUOUS -> {
                playRecognitionSoundOnce(AppSoundManager.Event.WARNING)
                if (productionAmbiguousSeeds.size == AMBIGUOUS_SELECTION_COUNT &&
                    candidates.size == AMBIGUOUS_SELECTION_COUNT) renderAmbiguousCandidates()
                else renderRetryResult("Couldn't Confirm Identity", "More than one possible match was found.\nPlease look at the camera and try again.")
            }
        }
    }

    private fun renderRetryResult(title: String, message: String) {
        renderMessage(title, message)
        content.addView(actionButton("TRY AGAIN", R.drawable.face_action_not_you, 64) { returnHome() }, frameParams(Gravity.BOTTOM, bottomMargin = 8))
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
        val match = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(matchedEmployeeCard(employee) {
                selected = employee
                if (productionDecision == null) recordCalibrationSelection(FaceCalibrationSelectionType.TOP_MATCH_ACCEPTED, employee)
                render(FaceFlowState.ATTENDANCE_ACTION)
            })
            addView(TextView(this@FaceRecognitionFlowActivity).apply {
                text = "Tap your card to continue"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.attendance_scanning))
                setPadding(0, dp(16), 0, 0)
            })
        }
        content.addView(match, frameParams(Gravity.CENTER))
        content.addView(matchedSecondaryAction("NOT YOU?") {
            render(FaceFlowState.CANDIDATE_LIST)
        }, frameParams(Gravity.BOTTOM, bottomMargin = 8))
    }

    private fun matchedEmployeeCard(employee: FaceCandidate, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(156)
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundResource(R.drawable.face_profile_card_green)
            isClickable = true
            isFocusable = true
            contentDescription = "Tap employee card for ${employee.employeeId} to continue"
            setOnClickListener { action() }
            addView(TextView(this@FaceRecognitionFlowActivity).apply {
                text = employee.displayName
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.attendance_text))
            })
            addView(TextView(this@FaceRecognitionFlowActivity).apply {
                text = employee.employeeId
                textSize = 20f
                setTextColor(getColor(R.color.attendance_text_secondary))
                setPadding(0, dp(10), 0, 0)
            })
        }

    private fun matchedSecondaryAction(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        minimumHeight = dp(56)
        setTextColor(getColor(R.color.attendance_text_secondary))
        setBackgroundResource(R.drawable.biometric_surface_card)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
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
        lateinit var checkIn: TextView
        lateinit var checkOut: TextView
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            checkIn = actionButton("CHECK IN", R.drawable.face_action_check_in, 72) { attendanceAction(AttendanceAction.CHECK_IN, checkIn, checkOut) }
            checkOut = actionButton("CHECK OUT", R.drawable.face_action_check_out, 72) { attendanceAction(AttendanceAction.CHECK_OUT, checkIn, checkOut) }
            addView(checkIn, linearParams())
            addView(checkOut, linearParams(topMargin = 16))
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

    private fun renderAmbiguousCandidates() {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title("Couldn't Confirm Identity"))
            addView(TextView(this@FaceRecognitionFlowActivity).apply {
                text = "Select your profile"
                textSize = 20f
                setTextColor(getColor(R.color.attendance_text))
                setPadding(0, 0, 0, dp(16))
            })
            candidates.forEachIndexed { index, employee ->
                addView(matchedEmployeeCard(employee) {
                    selected = employee
                    render(FaceFlowState.ATTENDANCE_ACTION)
                }, linearParams(topMargin = if (index == 0) 0 else 16))
            }
        }
        content.addView(list, frameParams(Gravity.TOP))
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

    private fun networkAvailable(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun attendanceAction(action: AttendanceAction, checkIn: TextView, checkOut: TextView) {
        val employee = selected ?: return
        if (!attendanceSubmitting.tryAcquire()) return
        checkIn.isEnabled = false; checkOut.isEnabled = false
        Thread {
            val record = LocalEmployeeDirectory(employeeDatabase.employeeDao()).getAll().firstOrNull { it.employeeId == employee.employeeId }
            val outcome = record?.let { attendanceService.record(it.userId, it.employeeId, action.name, "FACE") }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                attendanceSubmitting.release()
                when {
                    outcome?.status == AttendanceRecordStatus.SYNCED ->
                        renderAttendanceSuccess(employee, action, outcome.event.serverTimestamp ?: outcome.event.deviceTimestamp)
                    outcome.isOfflinePending() -> renderAttendanceOfflineSaved(action)
                    outcome?.status == AttendanceRecordStatus.REJECTED ->
                        renderAttendanceRejected(outcome.businessRejection)
                    else -> renderAttendanceFailure(employee, action, checkIn, checkOut)
                }
            }
        }.apply { name = "face-attendance-record" }.start()
    }

    private fun renderAttendanceSuccess(employee: FaceCandidate, action: AttendanceAction, time: String) {
        soundManager.play(AppSoundManager.Event.SUCCESS)
        content.removeAllViews()
        content.addView(title(if (action == AttendanceAction.CHECK_IN) "CHECK IN SUCCESSFUL" else "CHECK OUT SUCCESSFUL"), frameParams(Gravity.TOP))
        content.addView(employeeCard(employee, R.drawable.face_profile_card_blue, showScore = false) {}, frameParams(Gravity.CENTER))
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) returnHome() }, ATTENDANCE_RESULT_DURATION_MS)
    }

    private fun renderAttendanceOfflineSaved(action: AttendanceAction) {
        content.removeAllViews()
        val message = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(title(if (action == AttendanceAction.CHECK_IN) "CHECK IN SAVED" else "CHECK OUT SAVED").apply {
                setTextColor(getColor(R.color.attendance_warning))
            }, linearParams())
            addView(TextView(this@FaceRecognitionFlowActivity).apply {
                text = "No network connection.\nAttendance will sync automatically."
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.attendance_text))
            }, linearParams(topMargin = 8))
        }
        content.addView(message, frameParams(Gravity.CENTER))
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) returnHome() }, ATTENDANCE_RESULT_DURATION_MS)
    }

    private fun renderAttendanceFailure(employee: FaceCandidate, action: AttendanceAction, checkIn: TextView, checkOut: TextView) {
        soundManager.play(AppSoundManager.Event.ERROR)
        content.removeAllViews()
        renderMessage("Attendance could not be recorded.", "Please try again.")
        checkIn.isEnabled = true; checkOut.isEnabled = true
        content.addView(actionButton("TRY AGAIN", R.drawable.face_action_not_you, 64) { render(FaceFlowState.ATTENDANCE_ACTION) }, frameParams(Gravity.BOTTOM, bottomMargin = 8))
    }

    private fun renderAttendanceRejected(rejection: AttendanceBusinessRejection?) {
        soundManager.play(AppSoundManager.Event.ERROR)
        content.removeAllViews()
        if (rejection == AttendanceBusinessRejection.NO_OPEN_SESSION) {
            renderMessage("Cannot check out", "No active check-in found. Please check in first.")
        } else {
            renderMessage("Attendance could not be recorded.", "Attendance could not be recorded.")
        }
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) returnHome() }, ATTENDANCE_RESULT_DURATION_MS)
    }

    private fun AttendanceRecordOutcome?.isOfflinePending(): Boolean =
        this?.status == AttendanceRecordStatus.PENDING &&
            (error == BackendApiError.NETWORK_UNAVAILABLE || error == BackendApiError.NETWORK_FAILURE)

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
        outState.putBoolean(STATE_RECOGNITION_SOUND_PLAYED, recognitionSoundPlayed)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        soundManager.release()
        super.onDestroy()
    }

    private fun playRecognitionSoundOnce(event: AppSoundManager.Event) {
        if (recognitionSoundPlayed) return
        recognitionSoundPlayed = true
        soundManager.play(event)
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
                if (productionDecision == null) recordCalibrationSelection(FaceCalibrationSelectionType.CANCELLED, null)
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
        private const val STATE_RECOGNITION_SOUND_PLAYED = "faceRecognitionSoundPlayed"
        private const val EXTRA_PRODUCTION_DECISION = "faceProductionDecision"
        private const val EXTRA_PRODUCTION_EMPLOYEE_ID = "faceProductionEmployeeId"
        private const val EXTRA_AMBIGUOUS_CANDIDATE_IDS = "faceAmbiguousCandidateIds"
        private const val EXTRA_AMBIGUOUS_CANDIDATE_SCORES = "faceAmbiguousCandidateScores"
        private const val AMBIGUOUS_SELECTION_COUNT = 2
        private const val ATTENDANCE_RESULT_DURATION_MS = 3_000L
        private val PROFILE_BACKGROUNDS = intArrayOf(
            R.drawable.face_profile_card_blue,
            R.drawable.face_profile_card_teal,
            R.drawable.face_profile_card_purple,
        )

        fun previewIntent(context: Context, candidates: List<FaceCandidateSeed>) = Intent(context, FaceRecognitionFlowActivity::class.java)
            .putStringArrayListExtra(EXTRA_CANDIDATE_IDS, ArrayList(candidates.take(3).map { it.employeeId }))
            .putExtra(EXTRA_CANDIDATE_SCORES, candidates.take(3).map { it.score }.toDoubleArray())

        fun productionIntent(context: Context, result: FaceRecognitionDecisionResult) = Intent(context, FaceRecognitionFlowActivity::class.java)
            .putExtra(EXTRA_PRODUCTION_DECISION, result.decision.name)
            .putExtra(EXTRA_PRODUCTION_EMPLOYEE_ID, result.employeeId)
            .putStringArrayListExtra(EXTRA_AMBIGUOUS_CANDIDATE_IDS, ArrayList(result.ambiguousStrongCandidates.map { it.employeeId }))
            .putExtra(EXTRA_AMBIGUOUS_CANDIDATE_SCORES, result.ambiguousStrongCandidates.map { it.score }.toDoubleArray())
    }
}
