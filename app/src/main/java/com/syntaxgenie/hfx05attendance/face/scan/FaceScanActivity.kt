@file:Suppress("DEPRECATION")

package com.syntaxgenie.hfx05attendance.face.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.syntaxgenie.hfx05attendance.R
import com.syntaxgenie.hfx05attendance.BuildConfig
import com.syntaxgenie.hfx05attendance.face.camera.FaceDetectionCamera
import com.syntaxgenie.hfx05attendance.face.camera.FaceDetectionCameraMetrics
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetectionInput
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetectionOutcome
import com.syntaxgenie.hfx05attendance.face.engine.opencv.OpenCvEngineLifecycle
import com.syntaxgenie.hfx05attendance.face.engine.opencv.YuNetFaceDetectorAdapter
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceFeatureExtractorAdapter
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceFeatureCodec
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceModelConfiguration
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceAlignmentMapper
import com.syntaxgenie.hfx05attendance.face.feature.*
import kotlin.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Handler
import android.os.Looper
import com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics.*
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentStatus
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateCompatibility
import com.syntaxgenie.hfx05attendance.face.repository.AndroidKeystoreFaceTemplateProtector
import com.syntaxgenie.hfx05attendance.face.repository.local.FaceEnrollmentDatabase
import com.syntaxgenie.hfx05attendance.face.repository.local.LocalFaceEnrollmentRepository
import com.syntaxgenie.hfx05attendance.face.flow.FaceRecognitionFlowActivity
import com.syntaxgenie.hfx05attendance.face.flow.FaceCandidateSeed
import com.syntaxgenie.hfx05attendance.face.flow.FaceRecognitionDecisionPolicy
import com.syntaxgenie.hfx05attendance.face.calibration.FaceRecognitionTelemetryEvent
import com.syntaxgenie.hfx05attendance.face.calibration.FaceCalibrationFirestoreRepository
import com.syntaxgenie.hfx05attendance.face.calibration.FaceRecognitionConfigMode
import com.syntaxgenie.hfx05attendance.face.index.FaceTemplateIndexManager
import com.syntaxgenie.hfx05attendance.face.index.FaceTemplateIndexSnapshot
import com.syntaxgenie.hfx05attendance.face.liveness.PassiveSpoofDetector
import com.syntaxgenie.hfx05attendance.face.liveness.PassiveSpoofMode
import com.syntaxgenie.hfx05attendance.face.liveness.PassiveSpoofPolicy
import com.syntaxgenie.hfx05attendance.face.liveness.PassiveSpoofAssessment
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets

class FaceScanActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var preview: SurfaceView
    private lateinit var overlay: FaceScanOverlayView
    private lateinit var status: TextView
    private lateinit var camera: FaceDetectionCamera
    private var controller: FaceScanController? = null
    private var surfaceReady = false
    private var latestMetrics: FaceDetectionCameraMetrics? = null
    private var sface: SFaceFeatureExtractorAdapter? = null
    private var lastSfaceNanos = 0L
    private val sfaceExecutor = Executors.newSingleThreadExecutor()
    private val startupExecutor = Executors.newSingleThreadExecutor()
    private val startupInFlight = AtomicBoolean(false)
    @Volatile private var sessionGeneration = 0L
    private val sfaceInFlight = AtomicBoolean(false)
    private val validation = AuraFaceValidationSession()
    private val passiveSpoofDetector = PassiveSpoofDetector()
    private val passiveSpoofMode = PassiveSpoofMode.OBSERVE_ONLY
    private var lastPassiveSpoofNanos = 0L
    private var latestPassiveSpoofAssessment: PassiveSpoofAssessment? = null
    private var employeeValidation: SFaceEmployeeValidationSession? = null
    private var debugCandidates: List<SFaceDebugCandidate> = emptyList()
    private val debugQueries = mutableListOf<ByteArray>()
    private val legacyDebugQueries = mutableListOf<ByteArray>()
    private var debugResultShown = false
    private var debugFlowOpened = false
    private var waitingForPersonB = false
    private var validationFinished = false
    @Volatile private var latestCandidate: FaceFeatureExtractionInput? = null
    private var lastLoggedState: FaceScanState? = null
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        if (!camera.isOpen()) return@Runnable
        status.text = "Position your face inside the frame"
        return@Runnable
        /* Legacy diagnostic timeout behavior intentionally disabled for normal scanning.
        if (BuildConfig.DEBUG) {
            status.text = "Validation paused — press START PERSON B to continue"
            releaseHardware()
        } else {
            status.text = "Face scan timed out"
            stop(resetValidation = true)
            timeoutHandler.postDelayed({ if (!isFinishing) finish() }, 800)
        }
        */
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) start() else fail("Camera permission denied.") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_face_scan)
        KioskWindowInsets.apply(this, findViewById(R.id.faceScanRoot))
        preview = findViewById(R.id.faceScanPreview); overlay = findViewById(R.id.faceScanOverlay); status = findViewById(R.id.faceScanStatus)
        loadEmployeeValidationTarget()
        preview.holder.addCallback(this)
        findViewById<android.view.View>(R.id.faceScanCancel).setOnClickListener { finish() }
        camera = FaceDetectionCamera(
            onDetectionFrame = { frame, rotation, mirrored -> controller?.submit(FaceDetectionInput(frame, rotation, mirrored)) },
            onMetrics = { metrics -> latestMetrics = metrics }, onError = ::fail,
        )
    }

    private fun loadEmployeeValidationTarget() {
        if (!BuildConfig.DEBUG) return
        val id = intent.getStringExtra(EXTRA_VALIDATION_EMPLOYEE_ID).orEmpty()
        if (id.isBlank()) return
        startupExecutor.execute {
            try {
                val repo = LocalFaceEnrollmentRepository(FaceEnrollmentDatabase.create(applicationContext).faceEnrollmentDao(), AndroidKeystoreFaceTemplateProtector())
                val records = repo.listByEmployee(id).filter { it.status == FaceEnrollmentStatus.ACTIVE }
                val compatible = records.filter { it.metadata.engineId == SFaceModelConfiguration.ENGINE_ID && it.metadata.modelId == SFaceModelConfiguration.MODEL_ID && it.metadata.modelVersion == SFaceModelConfiguration.MODEL_VERSION && it.metadata.templateFormatVersion == SFaceFeatureCodec.FORMAT_ID && it.metadata.enrollmentSampleCount == 3 }
                if (compatible.size != 3) throw IllegalStateException("Expected exactly 3 compatible active templates; found ${compatible.size}")
                val target = SFaceEmployeeValidationSession(id, compatible.map { it.templatePayload() })
                runOnUiThread { employeeValidation = target; updateValidationStatus() }
            } catch (e: Exception) { Log.w(LOG_TAG, "Employee validation target unavailable", e) }
        }
    }

    override fun onResume() {
        super.onResume()
        prepareNewRecognitionSession()
        if (surfaceReady) requestStart()
    }
    override fun onPause() { stop(resetValidation = true); super.onPause() }
    override fun onDestroy() { preview.holder.removeCallback(this); stop(resetValidation = true); sfaceExecutor.shutdownNow(); startupExecutor.shutdownNow(); super.onDestroy() }
    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; requestStart() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; stop(resetValidation = true) }

    private fun requestStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) start() else permission.launch(Manifest.permission.CAMERA)
    }
    private fun start() {
        if (!surfaceReady || camera.isOpen() || !startupInFlight.compareAndSet(false, true)) return
        val generation = ++sessionGeneration
        runOnUiThread { status.text = "Preparing face recognition..." }
        FaceTemplateIndexManager.get(applicationContext).ensureReady { indexResult ->
            indexResult.onSuccess { index ->
                startupExecutor.execute { startWithReadyIndex(generation, index) }
            }.onFailure { error ->
                runOnUiThread {
                    if (generation == sessionGeneration && !isFinishing && !isDestroyed) {
                        startupInFlight.set(false)
                        fail("Face Recognition Unavailable")
                    }
                }
            }
        }
    }

    private fun startWithReadyIndex(generation: Long, index: FaceTemplateIndexSnapshot) {
            if (index.employeeCount == 0) {
                runOnUiThread {
                    if (generation == sessionGeneration && !isFinishing && !isDestroyed) {
                        startupInFlight.set(false)
                        status.text = "No Face Registrations"
                    }
                }
                return
            }
            try {
                val lifecycle = OpenCvEngineLifecycle(applicationContext)
                val model = lifecycle.prepareYuNetModel()
                val sfaceModel = lifecycle.prepareSFaceModel()
                val detector = YuNetFaceDetectorAdapter(model.absolutePath)
                val extractor = SFaceFeatureExtractorAdapter(sfaceModel.absolutePath)
                // Ranking is required in both CALIBRATION and PRODUCTION. Only its
                // post-ranking UI differs by mode.
                debugCandidates = index.candidates()
                Log.i(LOG_TAG, "FACE_INDEX_READY_FOR_SCAN employees=${index.employeeCount} templates=${index.templateCount}")
                runOnUiThread {
                    if (generation != sessionGeneration || !surfaceReady || isFinishing || isDestroyed) {
                        detector.close(); extractor.close(); startupInFlight.set(false); return@runOnUiThread
                    }
                    sface = extractor
                    controller = FaceScanController(
                detector,
                onPresentation = { presentation -> runOnUiThread { render(presentation) } },
                onPerformance = { performance ->
                    if (performance.completedInvocations % 10L == 0L) Log.i(LOG_TAG,
                        "preview=${latestMetrics?.framesPerSecond ?: 0.0}fps detect=${performance.detectionFramesPerSecond}fps " +
                            "latency=${performance.averageLatencyMillis}/${performance.p95LatencyMillis}/${performance.maximumLatencyMillis}ms")
                },
                onDetectionOutcome = { input, outcome ->
                    val spoofAssessment = if (outcome is FaceDetectionOutcome.Detected && outcome.faces.size == 1 &&
                        System.nanoTime() - lastPassiveSpoofNanos > PASSIVE_SPOOF_INTERVAL_NANOS) {
                        lastPassiveSpoofNanos = System.nanoTime()
                        passiveSpoofDetector.analyze(input.frame, outcome.faces.single())
                    } else null
                    if (spoofAssessment != null) latestPassiveSpoofAssessment = spoofAssessment
                    val allowRecognition = latestPassiveSpoofAssessment?.let { PassiveSpoofPolicy.allowsRecognition(passiveSpoofMode, it.result) } ?: true
                    if (!allowRecognition) runOnUiThread { status.text = "Couldn't verify live face. Please look directly at the camera and try again." }
                    if (outcome is FaceDetectionOutcome.Detected && outcome.faces.size == 1 && allowRecognition && SFaceAlignmentMapper.validate(outcome.faces.single(), input.frame.width, input.frame.height) == null) {
                        latestCandidate = FaceFeatureExtractionInput(input.frame, outcome.faces.single(), input.rotationDegrees, input.mirrorHorizontally)
                    }
                    if (outcome is FaceDetectionOutcome.Detected && outcome.faces.size == 1 &&
                        allowRecognition && (debugCandidates.isNotEmpty() || FaceScanState.HoldStill == currentScanState) && System.nanoTime() - lastSfaceNanos > 1_000_000_000L) {
                        lastSfaceNanos = System.nanoTime()
                        val face = outcome.faces.single()
                        val extractionGeneration = sessionGeneration
                        if (sfaceInFlight.compareAndSet(false, true)) sfaceExecutor.execute { extractDiagnosticComparison(FaceFeatureExtractionInput(input.frame, face, input.rotationDegrees, input.mirrorHorizontally)) { result ->
                                if (extractionGeneration != sessionGeneration) return@extractDiagnosticComparison
                                result.getOrNull()?.let { comparison ->
                                    val extraction = comparison.reference
                                    if (extraction is FaceFeatureExtractionOutcome.Success) {
                                        val elements = SFaceFeatureCodec.decode(extraction.feature.copyPayload()).size
                                        var accepted = false
                                        if (debugCandidates.isNotEmpty()) {
                                            debugQueries += extraction.feature.copyPayload()
                                            (comparison.legacy as? FaceFeatureExtractionOutcome.Success)?.let { legacyDebugQueries += it.feature.copyPayload() }
                                            if (debugQueries.size >= 3 && !debugResultShown) {
                                                debugResultShown = true
                                                Log.i(LOG_TAG, "FACESCAN_RANKING_GEOMETRY=CORRECTED")
                                                val matchStarted = System.nanoTime()
                                                val result = SFaceDebugIdentification.rank(debugQueries.take(3), debugCandidates, SFaceAggregationMethod.MEAN_3)
                                                val topCandidates = SFaceDebugIdentification.rankAll(debugQueries.take(3), debugCandidates, SFaceAggregationMethod.MEAN_3)
                                                    .take(3)
                                                    .map { FaceCandidateSeed(it.employeeId, it.score) }
                                                val median = SFaceDebugIdentification.rank(debugQueries.take(3), debugCandidates, SFaceAggregationMethod.MEDIAN_3)
                                                val top2 = SFaceDebugIdentification.rank(debugQueries.take(3), debugCandidates, SFaceAggregationMethod.TOP_2_MEAN)
                                                Log.d(LOG_TAG, "FACE_MATCH employees=${index.employeeCount} templates=${index.templateCount} matchMillis=${(System.nanoTime() - matchStarted) / 1_000_000}")
                                                logAlignmentMatrices(legacyDebugQueries.take(3), debugQueries.take(3))
                                                releaseHardware()
                                                Log.i(LOG_TAG, "DEBUG_RANKING mean=${result?.score} median=${median?.score} top2=${top2?.score} second=${result?.secondBestScore} margin=${result?.margin}")
                                                val repository = FaceCalibrationFirestoreRepository.get(applicationContext)
                                                val config = repository.currentConfig()
                                                val configMode = config.mode
                                                if (topCandidates.isNotEmpty() && !debugFlowOpened && isManualCandidateFlowEnabled()) {
                                                    debugFlowOpened = true
                                                    releaseHardware()
                                                    Log.i(LOG_TAG, "FACESCAN_FLOW_NAVIGATION_REQUESTED mode=$configMode candidates=${topCandidates.size}")
                                                    runOnUiThread {
                                                        startActivityForResult(
                                                            FaceRecognitionFlowActivity.previewIntent(this@FaceScanActivity, topCandidates),
                                                            REQUEST_FACE_FLOW,
                                                        )
                                                        Log.i(LOG_TAG, "FACESCAN_FLOW_ACTIVITY_STARTED mode=$configMode")
                                                    }
                                                } else if (topCandidates.isNotEmpty() && !debugFlowOpened) {
                                                    debugFlowOpened = true
                                                    val decision = FaceRecognitionDecisionPolicy.decide(topCandidates, config)
                                                    val top = decision.top; val second = decision.second
                                                    val margin = if (top != null && second != null) top.score - second.score else null
                                                    Log.i(LOG_TAG, "FACE_DECISION_${decision.decision} topScore=${top?.score} secondScore=${second?.score} margin=$margin threshold=${config.matchThreshold} minMargin=${config.minMatchMargin} configVersion=${config.configVersion}")
                                                    repository.recordLiveRecognition(FaceRecognitionTelemetryEvent(
                                                        decision.decision.name, top?.employeeId, top?.score, second?.employeeId, second?.score, margin,
                                                        config.matchThreshold ?: Double.NaN, config.minMatchMargin ?: Double.NaN, config.configVersion,
                                                    ))
                                                    releaseHardware()
                                                    runOnUiThread { startActivityForResult(FaceRecognitionFlowActivity.productionIntent(this@FaceScanActivity, decision), REQUEST_FACE_FLOW) }
                                                } else if (!BuildConfig.DEBUG) runOnUiThread { status.text = "Face recognition calibration required." }
                                        } else {
                                        accepted = employeeValidation?.addQuery(validation.activeGroup, extraction.feature)
                                            ?: validation.add(extraction.feature, extraction.latencyNanos, System.nanoTime())
                                        Log.i(LOG_TAG, "SFACE_DIAGNOSTIC success group=${validation.activeGroup} accepted=$accepted countA=${validation.count(SFaceValidationGroup.PERSON_A)} countB=${validation.count(SFaceValidationGroup.PERSON_B)} latency=${extraction.latencyNanos / 1_000_000}ms elements=$elements format=${extraction.feature.metadata.templateFormatVersion} javaHeap=${Debug.getNativeHeapSize() - Debug.getNativeHeapFreeSize()} nativeHeap=${Debug.getNativeHeapAllocatedSize()}")
                                        if (employeeValidation == null && (accepted && validation.count(SFaceValidationGroup.PERSON_A) == 10 || accepted && validation.count(SFaceValidationGroup.PERSON_B) == 5)) {
                                            Log.i(LOG_TAG, "SFACE_DIAGNOSTIC_STATS same=${validation.samePerson()} cross=${validation.crossPerson()} result=${validation.result()} latency=${validation.extractionLatencyStatistics()}")
                                        }
                                        }
                                        runOnUiThread {
                                            updateValidationStatus()
                                            if (accepted && queryCount(SFaceValidationGroup.PERSON_A) == 10 && !waitingForPersonB) {
                                                waitingForPersonB = true
                                                timeoutHandler.removeCallbacks(timeoutRunnable)
                                                releaseHardware()
                                                status.text = "Person A complete. Place a different person in front of the device and press START PERSON B."
                                            } else if (accepted && queryCount(SFaceValidationGroup.PERSON_B) == 10 && !validationFinished) {
                                                validationFinished = true
                                                timeoutHandler.removeCallbacks(timeoutRunnable)
                                                releaseHardware()
                                                status.text = "SFace RGB validation complete"
                                            }
                                        }
                                        }
                                    } else Log.w(LOG_TAG, "AURAFACE_DIAGNOSTIC outcome=$extraction")
                                }
                                if (extractionGeneration == sessionGeneration) sfaceInFlight.set(false)
                        } } 
                    }
                    if (outcome is FaceDetectionOutcome.Detected) {
                        val first = outcome.faces.firstOrNull()
                        Log.i(LOG_TAG, "camera=${input.frame.cameraId} faces=${outcome.faces.size} " +
                            "confidence=${first?.quality?.confidence} box=${first?.boundingBox} " +
                            "landmarks=${first?.landmarks?.size} Y=${input.frame.diagnostics.averageY} " +
                            "U=${input.frame.diagnostics.averageU} V=${input.frame.diagnostics.averageV} " +
                            "chromaStd=${input.frame.diagnostics.chromaStandardDeviation}")
                    } else if (outcome is FaceDetectionOutcome.NoFace && outcome.latencyNanos > 0) {
                        Log.d(LOG_TAG, "camera=${input.frame.cameraId} no-face Y=${input.frame.diagnostics.averageY} " +
                            "U=${input.frame.diagnostics.averageU} V=${input.frame.diagnostics.averageV} " +
                            "chromaStd=${input.frame.diagnostics.chromaStandardDeviation}")
                    }
                },
                    ).also { it.starting() }
                    camera.open(
                FaceScanCameraConfiguration.USER_FACING_CAMERA_ID,
                preview.holder,
                windowManager.defaultDisplay.rotation,
                FaceScanCameraConfiguration.USER_FACING_DISPLAY_ORIENTATION,
            )
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    timeoutHandler.postDelayed(timeoutRunnable, SCAN_TIMEOUT_MS)
                    startupInFlight.set(false)
                }
            } catch (error: Exception) {
                runOnUiThread { startupInFlight.set(false); fail(error.message ?: "Could not start face scan.") }
            }
    }
    private var currentScanState: FaceScanState = FaceScanState.Idle
    private fun render(presentation: FaceScanPresentation) {
        currentScanState = presentation.state
        if (lastLoggedState != presentation.state) { lastLoggedState = presentation.state; Log.i(LOG_TAG, "SCAN_STATE state=${presentation.state} status=${presentation.status}") }
        status.text = presentation.status
        val metrics = latestMetrics ?: return
        overlay.show(presentation.face?.boundingBox, metrics.frame.width, metrics.frame.height, metrics.rotationDegrees, metrics.mirrorHorizontally)
    }
    private fun fail(message: String) = runOnUiThread { status.text = message; stop(resetValidation = true) }
    private fun extractDiagnostic(input: FaceFeatureExtractionInput, callback: (Result<FaceFeatureExtractionOutcome>) -> Unit) {
        extractDiagnosticComparison(input) { callback(it.map { comparison -> comparison.legacy }) }
    }

    private data class SFaceDiagnosticComparison(
        val legacy: FaceFeatureExtractionOutcome,
        val reference: FaceFeatureExtractionOutcome,
    )

    private fun extractDiagnosticComparison(input: FaceFeatureExtractionInput, callback: (Result<SFaceDiagnosticComparison>) -> Unit) {
        val extractor = sface ?: return
        val block: suspend () -> SFaceDiagnosticComparison = {
            val legacy = extractor.extractLegacy(input)
            val reference = extractor.extract(input)
            if (legacy is FaceFeatureExtractionOutcome.Success && reference is FaceFeatureExtractionOutcome.Success) {
                Log.i(LOG_TAG, "SFACE_ALIGNMENT_COMPARE legacy_vs_reference_cosine=${"%.6f".format(SFaceValidationSession.cosine(legacy.feature.copyPayload(), reference.feature.copyPayload()))} rotation=${input.rotationDegrees} mirror=${input.mirrorHorizontally}")
            } else {
                Log.w(LOG_TAG, "SFACE_ALIGNMENT_COMPARE unavailable legacy=${legacy.javaClass.simpleName} reference=${reference.javaClass.simpleName}")
            }
            SFaceDiagnosticComparison(legacy, reference)
        }
        block.startCoroutine(Continuation(EmptyCoroutineContext, callback))
    }

    private fun logAlignmentMatrices(legacyQueries: List<ByteArray>, referenceQueries: List<ByteArray>) {
        if (legacyQueries.size != 3 || referenceQueries.size != 3) return
        debugCandidates.forEach { candidate ->
            Log.i(LOG_TAG, "SFACE_3X3 legacy_query_vs_legacy_enrollment employee=${candidate.employeeId} scores=${matrix(legacyQueries, candidate.templates)}")
            Log.i(LOG_TAG, "SFACE_3X3 reference_query_vs_legacy_enrollment employee=${candidate.employeeId} scores=${matrix(referenceQueries, candidate.templates)}")
        }
    }

    private fun matrix(queries: List<ByteArray>, templates: List<ByteArray>): String =
        queries.joinToString(";") { query -> templates.joinToString(",") { template -> "%.6f".format(SFaceValidationSession.cosine(query, template)) } }
    /**
     * CALIBRATION deliberately permits a ranked, manual profile-selection flow in release.
     * It does not apply a matcher threshold or establish a production biometric acceptance.
     */
    private fun isManualCandidateFlowEnabled(): Boolean =
        BuildConfig.DEBUG ||
            FaceCalibrationFirestoreRepository.get(applicationContext).currentConfig().mode == FaceRecognitionConfigMode.CALIBRATION
    private fun requestSFaceExtraction(input: FaceFeatureExtractionInput) {
        if (!BuildConfig.DEBUG || !sfaceInFlight.compareAndSet(false, true)) return
        sfaceExecutor.execute { extractDiagnostic(input) { result ->
            result.getOrNull()?.let { extraction ->
                if (extraction is FaceFeatureExtractionOutcome.Success) {
                    val elements = SFaceFeatureCodec.decode(extraction.feature.copyPayload()).size
                    val accepted = employeeValidation?.addQuery(validation.activeGroup, extraction.feature)
                        ?: validation.add(extraction.feature, extraction.latencyNanos, System.nanoTime())
                    Log.i(LOG_TAG, "SFACE_DIAGNOSTIC success group=${validation.activeGroup} accepted=$accepted countA=${validation.count(SFaceValidationGroup.PERSON_A)} countB=${validation.count(SFaceValidationGroup.PERSON_B)} latency=${extraction.latencyNanos / 1_000_000}ms elements=$elements format=${extraction.feature.metadata.templateFormatVersion} javaHeap=${Debug.getNativeHeapSize() - Debug.getNativeHeapFreeSize()} nativeHeap=${Debug.getNativeHeapAllocatedSize()}")
                    runOnUiThread { updateValidationStatus() }
                } else Log.w(LOG_TAG, "AURAFACE_DIAGNOSTIC outcome=$extraction")
            }
            sfaceInFlight.set(false)
        } }
    }
    private fun queryCount(group: SFaceValidationGroup) = employeeValidation?.queryCount(group) ?: validation.count(group)
    private fun updateValidationStatus() { findViewById<TextView>(R.id.faceScanValidationStatus)?.text = employeeValidation?.let { "Person A employee: ${it.employeeId}\nEnrollment templates: ${it.templatesCount()}\nPerson A queries: ${it.queryCount(SFaceValidationGroup.PERSON_A)} / 10    Person B queries: ${it.queryCount(SFaceValidationGroup.PERSON_B)} / 10" } ?: "Person A: ${validation.count(SFaceValidationGroup.PERSON_A)} / 10    Person B: ${validation.count(SFaceValidationGroup.PERSON_B)} / 5" }
    private fun releaseHardware() {
        sessionGeneration++
        timeoutHandler.removeCallbacks(timeoutRunnable)
        camera.close()
        controller?.close(); controller = null
        sface?.close(); sface = null
        sfaceInFlight.set(false); startupInFlight.set(false); latestCandidate = null
        overlay.show(null, 1280, 720, 90, false)
    }
    private fun resetValidationSession() {
        validation.reset(); waitingForPersonB = false; validationFinished = false
    }
    /** Resets per-scan state when this activity becomes active again; the RAM template index remains intact. */
    private fun prepareNewRecognitionSession() {
        debugQueries.clear()
        legacyDebugQueries.clear()
        debugResultShown = false
        debugFlowOpened = false
        lastSfaceNanos = 0L
        lastPassiveSpoofNanos = 0L
        latestPassiveSpoofAssessment = null
        passiveSpoofDetector.reset()
        latestCandidate = null
        lastLoggedState = null
        sfaceInFlight.set(false)
        resetValidationSession()
    }
    private fun stop(resetValidation: Boolean = false) {
        releaseHardware()
        if (resetValidation) resetValidationSession()
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FACE_FLOW && resultCode == FaceRecognitionFlowActivity.RESULT_RETURN_HOME) finish()
    }
    companion object {
        private const val LOG_TAG = "FaceScan"
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val PASSIVE_SPOOF_INTERVAL_NANOS = 150_000_000L
        private const val REQUEST_FACE_FLOW = 3002
        const val EXTRA_VALIDATION_EMPLOYEE_ID = "validationEmployeeId"
        const val EXTRA_DEBUG_IDENTIFICATION = "debugIdentification"
    }
}
