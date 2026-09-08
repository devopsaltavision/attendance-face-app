@file:Suppress("DEPRECATION")
package com.syntaxgenie.hfx05attendance.face.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import android.util.Log
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.syntaxgenie.hfx05attendance.R
import com.syntaxgenie.hfx05attendance.face.camera.FaceDetectionCamera
import com.syntaxgenie.hfx05attendance.face.detection.*
import com.syntaxgenie.hfx05attendance.face.engine.opencv.*
import com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics.SFaceDuplicateEnrollmentDiagnostics
import com.syntaxgenie.hfx05attendance.face.feature.*
import com.syntaxgenie.hfx05attendance.face.index.FaceTemplateIndexManager
import com.syntaxgenie.hfx05attendance.face.repository.*
import com.syntaxgenie.hfx05attendance.face.repository.local.*
import com.syntaxgenie.hfx05attendance.face.backup.FaceBackupSyncStore
import com.syntaxgenie.hfx05attendance.face.backup.FaceEnrollmentRemoteRepository
import com.syntaxgenie.hfx05attendance.employee.local.EmployeeDirectoryDatabase
import com.syntaxgenie.hfx05attendance.employee.local.LocalEmployeeDirectory
import com.syntaxgenie.hfx05attendance.ui.AppSoundManager
import com.syntaxgenie.hfx05attendance.ui.BiometricConfirmationDialog
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.*

class FaceRegistrationActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private enum class RegistrationPresentation { LIVE_CAPTURE, PHOTO_REVIEW, FINAL_REVIEW }

    private lateinit var preview: SurfaceView; private lateinit var previewContainer: View; private lateinit var overlay: FaceScanOverlayView; private lateinit var status: TextView; private lateinit var progress: TextView; private lateinit var instruction: TextView; private lateinit var captured: ImageView; private lateinit var review: LinearLayout; private lateinit var add: Button; private lateinit var confirm: Button; private lateinit var retry: Button; private lateinit var camera: FaceDetectionCamera
    private var controller: FaceScanController? = null; private var extractor: SFaceFeatureExtractorAdapter? = null; private var surfaceReady = false; private var latestInput: FaceFeatureExtractionInput? = null; private var pendingFeature: FaceFeature? = null; private var pendingBitmap: Bitmap? = null; private val features = mutableListOf<FaceFeature>(); private val thumbnails = mutableListOf<Bitmap>(); private var generation = 0L; private var committed = false; private var finalReview = false; private var saveConfirmationVisible = false; private var presentation = RegistrationPresentation.LIVE_CAPTURE; private var receivedFrames = 0L
    private val startup = Executors.newSingleThreadExecutor(); private val worker = Executors.newSingleThreadExecutor(); private val busy = AtomicBoolean(false); private val startupInFlight = AtomicBoolean(false); private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { if (camera.isOpen()) status.text = "Position your face inside the frame" }
    private val soundManager by lazy { AppSoundManager(applicationContext) }
    private val employeeId get() = intent.getStringExtra(EXTRA_EMPLOYEE_ID).orEmpty(); private val employeeName get() = intent.getStringExtra(EXTRA_EMPLOYEE_NAME).orEmpty()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startCamera() else finish() }
    override fun onCreate(state: Bundle?) { super.onCreate(state); Log.d("FaceRegistration", "LIFECYCLE onCreate"); setContentView(R.layout.activity_face_registration); preview=findViewById(R.id.registrationPreview); preview.scaleX = -1f; previewContainer=findViewById(R.id.registrationPreviewContainer); overlay=findViewById(R.id.registrationOverlay); status=findViewById(R.id.registrationStatus); progress=findViewById(R.id.registrationProgress); instruction=findViewById(R.id.registrationInstruction); captured=findViewById(R.id.registrationCaptured); captured.scaleX = -1f; review=findViewById(R.id.registrationReview); add=findViewById(R.id.registrationAddSample); confirm=findViewById(R.id.registrationConfirm); retry=findViewById(R.id.registrationRetry); findViewById<TextView>(R.id.registrationEmployee).text=employeeName; findViewById<TextView>(R.id.registrationEmployeeNumber).text="Employee: $employeeId"; findViewById<View>(R.id.registrationBack).setOnClickListener { if (!committed) { discardRegistration(); finish() } }; add.isClickable=true; add.isFocusable=true; add.setOnClickListener { Log.d("FaceRegistration", "CAPTURE_LISTENER_FIRED"); setCaptureStatus("Capturing...", R.color.attendance_text_secondary); captureCurrentFrame() }; confirm.setOnClickListener { confirmSample() }; retry.setOnClickListener { if(finalReview) startAgain() else { discardPending(); startCamera() } }; preview.holder.addCallback(this); camera=FaceDetectionCamera({ f,r,m -> receivedFrames++; if (receivedFrames == 1L || receivedFrames % 20L == 0L) Log.d("FaceRegistration", "REG_FRAME_RECEIVED count=$receivedFrames"); if (controller != null) { Log.d("FaceRegistration", "REG_CONTROLLER_SUBMIT count=$receivedFrames"); controller?.submit(FaceDetectionInput(f,r,m)) } }, {}, { text -> Log.d("FaceRegistration", "REG_CAMERA_ERROR $text"); runOnUiThread { setCaptureStatus(text, R.color.attendance_failure) } }); updateUi() }
    override fun onBackPressed() { if (!committed) super.onBackPressed() }
    override fun onStart() { super.onStart(); Log.d("FaceRegistration", "LIFECYCLE onStart") }; override fun onResume() { super.onResume(); Log.d("FaceRegistration", "LIFECYCLE onResume"); if (surfaceReady && presentation == RegistrationPresentation.LIVE_CAPTURE) startCamera() }; override fun onPause() { Log.d("FaceRegistration", "LIFECYCLE onPause"); releaseHardware(); super.onPause() }; override fun onStop() { Log.d("FaceRegistration", "LIFECYCLE onStop"); super.onStop() }; override fun onDestroy() { Log.d("FaceRegistration", "LIFECYCLE onDestroy"); discardRegistration(); releaseHardware(); soundManager.release(); handler.removeCallbacksAndMessages(null); startup.shutdownNow(); worker.shutdownNow(); super.onDestroy() }; override fun surfaceCreated(h: SurfaceHolder) { surfaceReady=true; if (presentation == RegistrationPresentation.LIVE_CAPTURE) startCamera() }; override fun surfaceChanged(h: SurfaceHolder,f:Int,w:Int,x:Int)=Unit; override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady=false; releaseHardware() }
    private fun startCamera() {
        if (!surfaceReady || presentation != RegistrationPresentation.LIVE_CAPTURE || camera.isOpen() || employeeId.isBlank() || committed || !startupInFlight.compareAndSet(false, true)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            startupInFlight.set(false); permission.launch(Manifest.permission.CAMERA); return
        }
        val token = ++generation
        startup.execute {
            try {
                val life = OpenCvEngineLifecycle(applicationContext)
                val y = life.prepareYuNetModel(); val s = life.prepareSFaceModel()
                val d = YuNetFaceDetectorAdapter(y.absolutePath); val e = SFaceFeatureExtractorAdapter(s.absolutePath)
                runOnUiThread {
                    if (token != generation || !surfaceReady || isFinishing || committed || camera.isOpen()) {
                        d.close(); e.close(); startupInFlight.set(false); return@runOnUiThread
                    }
                    extractor = e
                    controller = FaceScanController(d, { p -> runOnUiThread { setCaptureStatus(statusText(p), statusColor(p.state)) } }, {}, { i, o ->
                        val detected = o as? FaceDetectionOutcome.Detected; val face = detected?.faces?.firstOrNull()
                        Log.d("FaceRegistration", "REG_DETECTION_CALLBACK outcome=${o::class.simpleName} REG_FACE_COUNT=${detected?.faces?.size ?: 0} REG_LANDMARK_COUNT=${face?.landmarks?.size ?: 0}")
                        runOnUiThread { overlay.show(face?.boundingBox, i.frame.width, i.frame.height, i.rotationDegrees, true); Log.d("FaceRegistration", "REG_OVERLAY_UPDATE hasFace=${face != null}"); onDetection(i, o) }
                    }).also { it.starting() }
                    Log.d("FaceRegistration", "REG_CAMERA_OPEN camera=${FaceScanCameraConfiguration.USER_FACING_CAMERA_ID}")
                    camera.open(FaceScanCameraConfiguration.USER_FACING_CAMERA_ID, preview.holder, windowManager.defaultDisplay.rotation, FaceScanCameraConfiguration.USER_FACING_DISPLAY_ORIENTATION)
                    Log.d("FaceRegistration", "REG_CAMERA_OPEN_RESULT open=${camera.isOpen()}")
                    retry.isEnabled = false; handler.removeCallbacks(timeout); handler.postDelayed(timeout, REGISTRATION_TIMEOUT_MS); updateUi(); startupInFlight.set(false)
                }
            } catch (error: Exception) {
                Log.e("FaceRegistration", "REG_CAMERA_START_FAILED", error)
                runOnUiThread { startupInFlight.set(false); status.text = "Please try again" }
            }
        }
    }
    private fun onDetection(i:FaceDetectionInput,o:FaceDetectionOutcome) { if(presentation != RegistrationPresentation.LIVE_CAPTURE || o !is FaceDetectionOutcome.Detected||o.faces.size!=1||pendingFeature!=null||finalReview) return; val f=o.faces.single(); val reason=SFaceAlignmentMapper.validate(f,i.frame.width,i.frame.height); val required=setOf(FaceLandmarkType.LEFT_EYE,FaceLandmarkType.RIGHT_EYE,FaceLandmarkType.NOSE_BASE,FaceLandmarkType.MOUTH_LEFT,FaceLandmarkType.MOUTH_RIGHT); val usable=required.all { t -> f.landmarks.any { it.type==t && it.position.x.isFinite() && it.position.y.isFinite() } }; Log.d("FaceRegistration", "REGISTRATION_FACE_VALIDATION result=${reason ?: if (usable) "usable" else "five landmarks required"}"); if(!usable) return; latestInput=FaceFeatureExtractionInput(i.frame,f,i.rotationDegrees,i.mirrorHorizontally); runOnUiThread { add.isEnabled=true; add.isClickable=true; Log.d("FaceRegistration", "ADD_SAMPLE_STATE enabled=${add.isEnabled} clickable=${add.isClickable} visibility=${add.visibility} shown=${add.isShown}") } }
    private fun captureCurrentFrame() {
        val input = latestInput
        Log.d("FaceRegistration", "ADD_SAMPLE_CLICK latestInput=${if (input != null) "YES" else "NO"} extractor=${if (extractor != null) "YES" else "NO"} camera=${if (camera.isOpen()) "YES" else "NO"} busy=${busy.get()}")
        if (presentation != RegistrationPresentation.LIVE_CAPTURE || input == null) { status.text = "Position your face inside the frame"; return }
        if (!busy.compareAndSet(false, true)) return
        add.isEnabled = false
        Log.d("FaceRegistration", "ADD_SAMPLE_STATE enabled=${add.isEnabled} clickable=${add.isClickable} visibility=${add.visibility} shown=${add.isShown}")
        status.text = "Capturing photo..."
        worker.execute {
            try {
                val ex = extractor
                if (ex == null) {
                    Log.d("FaceRegistration", "CAPTURE_EXTRACTION_FAILED: extractor_unavailable")
                    runOnUiThread { showLiveCapture("Please try again") }
                    return@execute
                }
                Log.d("FaceRegistration", "CAPTURE_EXTRACTION_START")
                val result = runExtractionBlocking(ex, input)
                val success = result as? FaceFeatureExtractionOutcome.Success
                if (success == null) {
                    Log.d("FaceRegistration", "CAPTURE_EXTRACTION_FAILED: ${result::class.simpleName}")
                    runOnUiThread { showLiveCapture("Please try again") }
                    return@execute
                }
                Log.d("FaceRegistration", "CAPTURE_EXTRACTION_SUCCESS")
                val bitmap = makeThumbnail(input)
                if (bitmap == null) {
                    Log.d("FaceRegistration", "THUMBNAIL_FAILED")
                    runOnUiThread { pendingFeature = null; pendingBitmap = null; showLiveCapture("Please try again") }
                    return@execute
                }
                Log.d("FaceRegistration", "THUMBNAIL_SUCCESS")
                soundManager.play(AppSoundManager.Event.CAPTURE_ACCEPTED)
                runOnUiThread {
                    pendingFeature = success.feature
                    pendingBitmap = bitmap
                    captured.setImageBitmap(bitmap)
                    releaseHardware()
                    showPhotoReview()
                }
            } finally { busy.set(false) }
        }
    }

    private fun runExtractionBlocking(ex: SFaceFeatureExtractorAdapter, input: FaceFeatureExtractionInput): FaceFeatureExtractionOutcome {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Result<FaceFeatureExtractionOutcome>? = null
        val block: suspend () -> FaceFeatureExtractionOutcome = { ex.extract(input) }
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it; latch.countDown() })
        latch.await()
        return result?.getOrThrow() ?: FaceFeatureExtractionOutcome.ExtractionFailed("no extraction result", 0)
    }
    private fun confirmSample() {
        if (finalReview) {
            if (features.size == MAX_SAMPLES && thumbnails.size == MAX_SAMPLES) showSaveConfirmation()
            return
        }
        val feature = pendingFeature ?: return
        features += feature
        pendingFeature = null
        pendingBitmap?.let { thumbnails += it }
        pendingBitmap = null
        captured.setImageDrawable(null)
        captured.visibility = View.GONE
        if (features.size == MAX_SAMPLES) {
            finalReview = true
            presentation = RegistrationPresentation.FINAL_REVIEW
            releaseHardware()
            previewContainer.visibility = View.GONE
            progress.visibility = View.GONE
            review.removeAllViews()
            thumbnails.forEachIndexed { index, bitmap -> review.addView(reviewItem(SAMPLE_LABELS[index], bitmap)) }
            review.visibility = View.VISIBLE
            confirm.text = "SAVE FACE"
            retry.text = "START AGAIN"
            status.visibility = View.GONE
        } else {
            handler.removeCallbacks(timeout)
            showLiveCapture("Position your face inside the frame")
            if (surfaceReady) startCamera()
        }
        updateUi()
    }

    private fun reviewItem(label: String, bitmap: Bitmap) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = 6 }
        addView(TextView(this@FaceRegistrationActivity).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
        })
        addView(ImageView(this@FaceRegistrationActivity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            scaleX = -1f
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 260))
    }

    private fun showSaveConfirmation() {
        if (committed || saveConfirmationVisible) return
        saveConfirmationVisible = true
        BiometricConfirmationDialog.show(this, "Save face registration?", employeeId,
            "Save these 3 photos as this employee's face registration?", "SAVE FACE",
            onCancel = { saveConfirmationVisible = false },
            onConfirm = { saveConfirmationVisible = false; commit() })
    }

    private fun startAgain() {
        features.clear()
        finalReview = false
        clearImages()
        showLiveCapture("Position your face inside the frame")
        if (surfaceReady) startCamera()
    }

    private fun discardPending() {
        pendingFeature = null
        pendingBitmap?.recycle()
        pendingBitmap = null
        captured.setImageDrawable(null)
        captured.visibility = View.GONE
        showLiveCapture("Position your face inside the frame")
    }

    private fun commit() {
        if (committed || features.size != MAX_SAMPLES || thumbnails.size != MAX_SAMPLES) return
        committed = true; confirm.isEnabled = false; retry.isEnabled = false; status.visibility = View.VISIBLE; status.text = "Saving registration..."; releaseHardware()
        worker.execute {
            val written = mutableListOf<FaceEnrollmentId>()
            try {
                val repo = LocalFaceEnrollmentRepository(FaceEnrollmentDatabase.create(applicationContext).faceEnrollmentDao(), AndroidKeystoreFaceTemplateProtector())
                val portableTemplates = features.map { it.copyPayload() }
                val comparison = SFaceDuplicateEnrollmentDiagnostics.compare(employeeId, portableTemplates, repo.listCompatible(FaceTemplateCompatibility(SFaceModelConfiguration.ENGINE_ID, SFaceModelConfiguration.MODEL_ID, SFaceModelConfiguration.MODEL_VERSION, SFaceFeatureCodec.FORMAT_ID)))
                Log.i("FaceRegistration", "DUPLICATE_ENROLLMENT_DIAGNOSTIC newEmployeeId=${comparison.newEmployeeId} bestExistingEmployeeId=${comparison.bestExistingEmployeeId} absoluteSimilarity=${comparison.absoluteSimilarity} secondBestEmployeeId=${comparison.secondBestExistingEmployeeId} secondBestSimilarity=${comparison.secondBestSimilarity}")
                val now = System.currentTimeMillis(); val backupEnrollmentId = UUID.randomUUID().toString()
                features.forEachIndexed { index, feature ->
                    val id = FaceEnrollmentId("$backupEnrollmentId-${index + 1}")
                    repo.add(FaceEnrollmentRecord(id, employeeId, feature.copyPayload(), FaceTemplateMetadata(feature.metadata.engineId, feature.metadata.modelId, feature.metadata.modelVersion, feature.metadata.templateFormatVersion, 1, enrollmentSampleCount = MAX_SAMPLES), now, now)); written += id
                }
                FaceRegistrationPhotoStore(applicationContext).also { store ->
                    runCatching { store.save(employeeId, thumbnails) }
                        .onFailure { store.delete(employeeId); Log.w("FaceRegistration", "Review thumbnails unavailable", it) }
                }
                val backupUserId = LocalEmployeeDirectory(EmployeeDirectoryDatabase.create(applicationContext).employeeDao())
                    .getAll().firstOrNull { it.employeeId == employeeId }?.userId
                if (backupUserId != null) FaceBackupSyncStore(applicationContext)
                    .put(FaceBackupSyncStore.Pending(backupEnrollmentId, backupUserId, employeeId))
                runOnUiThread { clearImages(); soundManager.play(AppSoundManager.Event.SUCCESS); Toast.makeText(this, "Face registration saved", Toast.LENGTH_SHORT).show(); setResult(RESULT_OK); finish() }
                queueFaceBackup(backupEnrollmentId, portableTemplates)
            } catch (error: Throwable) {
                Log.e("FaceRegistration", "commit failed; rolling back ${written.size} records", error)
                written.forEach { runCatching { LocalFaceEnrollmentRepository(FaceEnrollmentDatabase.create(applicationContext).faceEnrollmentDao(), AndroidKeystoreFaceTemplateProtector()).delete(it) } }
                FaceRegistrationPhotoStore(applicationContext).delete(employeeId)
                committed = false; runOnUiThread { status.visibility = View.VISIBLE; status.text = "Registration could not be saved.\nPlease try again."; confirm.isEnabled = true; retry.isEnabled = true; soundManager.play(AppSoundManager.Event.ERROR) }
            }
        }
    }

    private fun queueFaceBackup(enrollmentId: String, templates: List<ByteArray>) {
        Thread {
            val employee = runCatching { LocalEmployeeDirectory(EmployeeDirectoryDatabase.create(applicationContext).employeeDao()).getAll().firstOrNull { it.employeeId == employeeId } }.getOrNull()
            if (employee == null) { Log.i("FaceRegistration", "FACE_BACKUP_PENDING employeeId=$employeeId reason=user_missing"); return@Thread }
            val store = FaceBackupSyncStore(applicationContext)
            store.put(FaceBackupSyncStore.Pending(enrollmentId, employee.userId, employeeId))
            Log.i("FaceRegistration", "FACE_BACKUP_START employeeId=$employeeId")
            FaceEnrollmentRemoteRepository(applicationContext).backup(enrollmentId, employee.userId, employeeId, templates)
                .onSuccess { status -> store.markSynced(employeeId); Log.i("FaceRegistration", "FACE_BACKUP_SUCCESS employeeId=$employeeId status=$status") }
                .onFailure { error -> Log.i("FaceRegistration", "FACE_BACKUP_PENDING employeeId=$employeeId reason=${error.javaClass.simpleName}") }
        }.apply { name = "face-backup-$employeeId" }.start()
    }
    private fun setCaptureStatus(message: String, color: Int) {
        status.text = message
        status.setTextColor(getColor(color))
    }

    private fun statusColor(state: FaceScanState): Int = when (state) {
        FaceScanState.HoldStill -> R.color.attendance_success
        FaceScanState.FaceDetected, FaceScanState.FaceTooSmall, FaceScanState.FaceTooLarge,
        FaceScanState.MultipleFaces, FaceScanState.TooDark -> R.color.attendance_warning
        FaceScanState.SearchingForFace, is FaceScanState.Error -> R.color.attendance_failure
        else -> R.color.attendance_text_secondary
    }

    private fun statusText(presentation: FaceScanPresentation): String = when (presentation.state) {
        FaceScanState.HoldStill -> "Face detected — Ready"
        FaceScanState.FaceDetected -> "Keep your face inside the frame"
        else -> presentation.status
    }

    private fun makeThumbnail(input:FaceFeatureExtractionInput):Bitmap?=try { val yuv=YuvImage(input.frame.copyData(),ImageFormat.NV21,input.frame.width,input.frame.height,null); val bytes=ByteArrayOutputStream(); yuv.compressToJpeg(Rect(0,0,input.frame.width,input.frame.height),55,bytes); val src=BitmapFactory.decodeByteArray(bytes.toByteArray(),0,bytes.size())?:return null; Bitmap.createBitmap(src,0,0,src.width,src.height,Matrix().apply{postRotate(input.rotationDegrees.toFloat())},true).also{src.recycle()} } catch(_:Exception){null}
    private fun showLiveCapture(message: String) {
        presentation = RegistrationPresentation.LIVE_CAPTURE
        previewContainer.visibility = View.VISIBLE
        preview.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE
        captured.setImageDrawable(null)
        captured.visibility = View.GONE
        review.visibility = View.GONE
        status.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        instruction.visibility = View.VISIBLE
        add.visibility = View.VISIBLE
        confirm.visibility = View.GONE
        retry.visibility = View.GONE
        add.isEnabled = latestInput != null && pendingFeature == null
        setCaptureStatus(message, R.color.attendance_text_secondary)
        updateUi()
    }

    private fun showPhotoReview() {
        presentation = RegistrationPresentation.PHOTO_REVIEW
        previewContainer.visibility = View.VISIBLE
        preview.visibility = View.GONE
        overlay.visibility = View.GONE
        captured.visibility = View.VISIBLE
        review.visibility = View.GONE
        add.visibility = View.GONE
        confirm.visibility = View.VISIBLE
        retry.visibility = View.VISIBLE
        confirm.isEnabled = true
        retry.isEnabled = true
        confirm.text = "USE PHOTO"
        retry.text = "RETAKE"
        status.text = "Check this photo"
        updateUi()
    }

    private fun clearImages(){ if(committed) FaceTemplateIndexManager.get(applicationContext).addOrReplaceEmployee(employeeId,features.map { it.copyPayload() }); pendingBitmap?.recycle(); pendingBitmap=null; thumbnails.forEach{it.recycle()}; thumbnails.clear(); review.removeAllViews(); review.visibility=View.GONE; captured.setImageDrawable(null); captured.visibility=View.GONE }
    private fun discardRegistration(){if(!committed){features.clear();clearImages()}}
    private fun updateUi(){ if (!committed) { progress.text=if(presentation == RegistrationPresentation.FINAL_REVIEW) "Step $MAX_SAMPLES of $MAX_SAMPLES" else "Step ${features.size + 1} of $MAX_SAMPLES"; instruction.text=if(presentation == RegistrationPresentation.FINAL_REVIEW) "REVIEW PHOTOS" else INSTRUCTIONS[features.size]; if (presentation == RegistrationPresentation.LIVE_CAPTURE) add.isEnabled=latestInput!=null&&pendingFeature==null } }
    private fun releaseHardware(){generation++; startupInFlight.set(false); handler.removeCallbacks(timeout);camera.close();controller?.close();controller=null;extractor?.close();extractor=null;busy.set(false);latestInput=null; if (::overlay.isInitialized) overlay.show(null,1280,720,90,false)}
    companion object { const val EXTRA_EMPLOYEE_ID="employeeId"; const val EXTRA_EMPLOYEE_NAME="employeeName"; const val REGISTRATION_TIMEOUT_MS=45_000L; private const val MAX_SAMPLES=3; private val INSTRUCTIONS=listOf("LOOK STRAIGHT","TURN SLIGHTLY LEFT","TURN SLIGHTLY RIGHT"); private val SAMPLE_LABELS=listOf("Straight","Left","Right") }
}
