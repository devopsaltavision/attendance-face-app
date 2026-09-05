package com.syntaxgenie.hfx05attendance.face.flow

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.syntaxgenie.hfx05attendance.BuildConfig
import com.syntaxgenie.hfx05attendance.R
import com.syntaxgenie.hfx05attendance.face.camera.FaceDetectionCamera
import com.syntaxgenie.hfx05attendance.face.camera.FaceDetectionCameraMetrics
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetectionInput
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetectionOutcome
import com.syntaxgenie.hfx05attendance.face.engine.opencv.OpenCvEngineLifecycle
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceAlignmentMapper
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceFeatureExtractorAdapter
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceModelConfiguration
import com.syntaxgenie.hfx05attendance.face.engine.opencv.YuNetFaceDetectorAdapter
import com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics.SFaceAggregationMethod
import com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics.SFaceDebugCandidate
import com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics.SFaceDebugIdentification
import com.syntaxgenie.hfx05attendance.face.feature.FaceFeatureExtractionInput
import com.syntaxgenie.hfx05attendance.face.feature.FaceFeatureExtractionOutcome
import com.syntaxgenie.hfx05attendance.face.index.FaceTemplateIndexManager
import com.syntaxgenie.hfx05attendance.face.scan.FaceScanController
import com.syntaxgenie.hfx05attendance.face.scan.FaceScanOverlayView
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Alternate-profile camera step. It produces a corrected-geometry selected-user diagnostic score,
 * but deliberately never approves or rejects a person until calibration supplies a policy.
 */
class FaceVerificationActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var camera: FaceDetectionCamera
    private lateinit var preview: SurfaceView
    private lateinit var overlay: FaceScanOverlayView
    private lateinit var status: TextView
    private lateinit var employeeId: String
    private var selectedTemplates = emptyList<ByteArray>()
    private var controller: FaceScanController? = null
    private var extractor: SFaceFeatureExtractorAdapter? = null
    private var surfaceReady = false
    private var latestMetrics: FaceDetectionCameraMetrics? = null
    private val startupExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "selected-face-startup") }
    private val featureExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "selected-face-feature") }
    private val startupInFlight = AtomicBoolean(false)
    private val featureInFlight = AtomicBoolean(false)
    @Volatile private var sessionGeneration = 0L
    private var lastFeatureNanos = 0L
    private val queries = mutableListOf<ByteArray>()
    private var scoringComplete = false
    private var verificationResult: FaceVerificationResult = FaceVerificationResult.CalibrationRequired

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_face_verification)
        KioskWindowInsets.apply(this, findViewById(R.id.faceVerifyRoot))
        employeeId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val employeeName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        preview = findViewById(R.id.faceVerifyPreview)
        overlay = findViewById(R.id.faceVerifyOverlay)
        status = findViewById(R.id.faceVerifyStatus)
        findViewById<TextView>(R.id.faceVerifyEmployee).text = "$employeeName\n$employeeId"
        findViewById<View>(R.id.faceVerifyBack).setOnClickListener { finish() }
        camera = FaceDetectionCamera(
            onDetectionFrame = { frame, rotation, mirrored -> controller?.submit(FaceDetectionInput(frame, rotation, mirrored)) },
            onMetrics = { latestMetrics = it },
            onError = { runOnUiThread { status.text = "Please try again" } },
        )
        preview.holder.addCallback(this)
        loadSelectedEmployeeTemplates()
    }

    override fun onResume() {
        super.onResume()
        prepareNewCheckSession()
        startIfReady()
    }

    override fun onPause() {
        releaseHardware()
        super.onPause()
    }

    override fun onDestroy() {
        preview.holder.removeCallback(this)
        releaseHardware()
        startupExecutor.shutdownNow()
        featureExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startIfReady()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        releaseHardware()
    }

    private fun loadSelectedEmployeeTemplates() {
        if (employeeId.isBlank()) {
            status.text = "Face not available"
            return
        }
        FaceTemplateIndexManager.get(applicationContext).ensureReady { result ->
            result.onSuccess { snapshot ->
                selectedTemplates = snapshot.templatesForEmployee(employeeId).orEmpty()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (selectedTemplates.size != 3) {
                        status.text = "Face not available"
                    } else {
                        status.text = "Checking..."
                        startIfReady()
                    }
                }
            }.onFailure {
                runOnUiThread { if (!isFinishing && !isDestroyed) status.text = "Face not available" }
            }
        }
    }

    private fun startIfReady() {
        if (!surfaceReady || selectedTemplates.size != 3 || camera.isOpen() || !startupInFlight.compareAndSet(false, true)) return
        val generation = ++sessionGeneration
        startupExecutor.execute {
            try {
                val lifecycle = OpenCvEngineLifecycle(applicationContext)
                val detector = YuNetFaceDetectorAdapter(lifecycle.prepareYuNetModel().absolutePath)
                val localExtractor = SFaceFeatureExtractorAdapter(lifecycle.prepareSFaceModel().absolutePath)
                runOnUiThread {
                    if (generation != sessionGeneration || !surfaceReady || isFinishing || isDestroyed) {
                        detector.close()
                        localExtractor.close()
                        startupInFlight.set(false)
                        return@runOnUiThread
                    }
                    extractor = localExtractor
                    controller = FaceScanController(
                        detector = detector,
                        onPresentation = { presentation ->
                            runOnUiThread {
                                val metrics = latestMetrics ?: return@runOnUiThread
                                overlay.show(
                                    presentation.face?.boundingBox,
                                    metrics.frame.width,
                                    metrics.frame.height,
                                    metrics.rotationDegrees,
                                    metrics.mirrorHorizontally,
                                )
                            }
                        },
                        onDetectionOutcome = { input, outcome ->
                            if (outcome is FaceDetectionOutcome.Detected && outcome.faces.size == 1 &&
                                SFaceAlignmentMapper.validate(outcome.faces.single(), input.frame.width, input.frame.height) == null
                            ) {
                                requestCorrectedExtraction(
                                    FaceFeatureExtractionInput(input.frame, outcome.faces.single(), input.rotationDegrees, input.mirrorHorizontally),
                                )
                            }
                        },
                    ).also { it.starting() }
                    camera.open(0, preview.holder, windowManager.defaultDisplay.rotation, 90)
                    status.text = "Checking..."
                    startupInFlight.set(false)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    startupInFlight.set(false)
                    if (!isFinishing && !isDestroyed) status.text = "Please try again"
                }
            }
        }
    }

    private fun requestCorrectedExtraction(input: FaceFeatureExtractionInput) {
        if (scoringComplete || System.nanoTime() - lastFeatureNanos < QUERY_INTERVAL_NANOS || !featureInFlight.compareAndSet(false, true)) return
        lastFeatureNanos = System.nanoTime()
        val generation = sessionGeneration
        featureExecutor.execute {
            extractCorrected(input) { result ->
                if (generation == sessionGeneration && !scoringComplete) {
                    val extraction = result.getOrNull()
                    if (extraction is FaceFeatureExtractionOutcome.Success) {
                        queries += extraction.feature.copyPayload()
                        if (queries.size == REQUIRED_QUERIES) {
                            scoringComplete = true
                            logSelectedEmployeeScore()
                            verificationResult = FaceVerificationResult.CalibrationRequired
                            completeAfterScoring(generation)
                        }
                    }
                }
                if (generation == sessionGeneration) featureInFlight.set(false)
            }
        }
    }

    private fun extractCorrected(
        input: FaceFeatureExtractionInput,
        callback: (Result<FaceFeatureExtractionOutcome>) -> Unit,
    ) {
        val localExtractor = extractor ?: run {
            callback(Result.failure(IllegalStateException("Face feature extractor is unavailable")))
            return
        }
        val extraction: suspend () -> FaceFeatureExtractionOutcome = { localExtractor.extract(input) }
        extraction.startCoroutine(Continuation(EmptyCoroutineContext, callback))
    }

    private fun logSelectedEmployeeScore() {
        if (!BuildConfig.DEBUG) return
        val candidate = SFaceDebugCandidate(employeeId, selectedTemplates)
        val mean3 = SFaceDebugIdentification.rank(queries, listOf(candidate), SFaceAggregationMethod.MEAN_3)?.score
        val median3 = SFaceDebugIdentification.rank(queries, listOf(candidate), SFaceAggregationMethod.MEDIAN_3)?.score
        val top2mean = SFaceDebugIdentification.rank(queries, listOf(candidate), SFaceAggregationMethod.TOP_2_MEAN)?.score
        Log.i(LOG_TAG, "SELECTED_FACE_CHECK employeeId=$employeeId mean3=$mean3 median3=$median3 top2mean=$top2mean")
    }

    /** Debug navigation only; the domain result remains CalibrationRequired in every build. */
    private fun completeAfterScoring(generation: Long) {
        runOnUiThread {
            if (generation != sessionGeneration || isFinishing || isDestroyed) return@runOnUiThread
            if (BuildConfig.DEBUG) {
                releaseHardware()
                setResult(RESULT_DEBUG_CHECK_COMPLETE)
                finish()
            } else {
                status.text = "Please try again"
            }
        }
    }

    private fun prepareNewCheckSession() {
        queries.clear()
        scoringComplete = false
        lastFeatureNanos = 0L
        featureInFlight.set(false)
        verificationResult = FaceVerificationResult.CalibrationRequired
    }

    private fun releaseHardware() {
        sessionGeneration++
        camera.close()
        controller?.close()
        controller = null
        extractor?.close()
        extractor = null
        startupInFlight.set(false)
        featureInFlight.set(false)
        if (::overlay.isInitialized) overlay.show(null, 1280, 720, 90, false)
    }

    companion object {
        private const val LOG_TAG = "FaceVerify"
        private const val REQUIRED_QUERIES = 3
        private const val QUERY_INTERVAL_NANOS = 1_000_000_000L
        const val EXTRA_ID = "verifyEmployeeId"
        const val EXTRA_NAME = "verifyEmployeeName"
        /** Returned only after a real DEBUG three-query score; never indicates production verification. */
        const val RESULT_DEBUG_CHECK_COMPLETE = 9411

        fun intent(context: Context, employee: FaceCandidate) = Intent(context, FaceVerificationActivity::class.java)
            .putExtra(EXTRA_ID, employee.employeeId)
            .putExtra(EXTRA_NAME, employee.displayName)
    }
}
