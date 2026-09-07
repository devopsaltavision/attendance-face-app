package com.syntaxgenie.hfx05attendance.face.calibration

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.syntaxgenie.hfx05attendance.R
import com.syntaxgenie.hfx05attendance.face.index.FaceTemplateIndexManager
import com.syntaxgenie.hfx05attendance.BuildConfig
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import java.util.concurrent.Executors

/** Admin diagnostic only: never launched from employee recognition. */
class StaticImageFaceCalibrationActivity : AppCompatActivity() {
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) contentResolver.openInputStream(uri)?.use { input -> bitmap = BitmapFactory.decodeStream(input); status.text = "Image selected. Evaluate it." }
    }
    private val worker = Executors.newSingleThreadExecutor()
    private var bitmap: android.graphics.Bitmap? = null
    private var result: StaticImageCalibrationResult? = null
    private var recorded = false
    private lateinit var status: TextView; private lateinit var scores: TextView; private lateinit var identity: Spinner
    private lateinit var known: RadioButton; private lateinit var unknown: RadioButton; private lateinit var ambiguous: RadioButton; private lateinit var record: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_static_image_face_calibration)
        if (!BuildConfig.DEBUG && FaceCalibrationFirestoreRepository.get(applicationContext).currentConfig().mode != FaceRecognitionConfigMode.CALIBRATION) { finish(); return }
        KioskWindowInsets.apply(this, findViewById(R.id.staticCalibrationRoot)); findViewById<MaterialToolbar>(R.id.staticCalibrationToolbar).setNavigationOnClickListener { finish() }
        status=findViewById(R.id.staticCalibrationStatus); scores=findViewById(R.id.staticCalibrationScores); identity=findViewById(R.id.staticCalibrationEmployee)
        known=findViewById(R.id.staticCalibrationKnown); unknown=findViewById(R.id.staticCalibrationUnknown); ambiguous=findViewById(R.id.staticCalibrationAmbiguous); record=findViewById(R.id.staticCalibrationRecord)
        findViewById<Button>(R.id.staticCalibrationSelect).setOnClickListener { picker.launch(arrayOf("image/*")) }
        findViewById<Button>(R.id.staticCalibrationEvaluate).setOnClickListener { evaluate() }; record.setOnClickListener { record() }
        FaceTemplateIndexManager.get(applicationContext).ensureReady { index -> runOnUiThread {
            val ids = index.getOrNull()?.candidates()?.map { it.employeeId }?.sorted().orEmpty()
            identity.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ids)
        } }
    }

    private fun evaluate() {
        val image = bitmap ?: return; status.text = "Evaluating image..."; result = null; recorded = false; record.isEnabled = false
        FaceTemplateIndexManager.get(applicationContext).ensureReady { index -> worker.execute {
            val evaluated = index.fold({ StaticImageFaceCalibrationProcessor(applicationContext).evaluate(image, it) }, { Result.failure(it) })
            runOnUiThread { evaluated.onSuccess { value -> result=value; val top=value.ranks.getOrNull(0); val second=value.ranks.getOrNull(1)
                scores.text="Top 1: ${top?.employeeId ?: "none"}  ${top?.score ?: 0.0}\nTop 2: ${second?.employeeId ?: "none"}  ${second?.score ?: 0.0}\nMargin: ${top?.margin ?: 0.0}"; status.text="Confirm ground truth, then record."; record.isEnabled=top!=null
            }.onFailure { status.text="Image could not be evaluated." } }
        } }
    }

    private fun record() {
        val top = result?.ranks?.firstOrNull() ?: return; if (recorded) return
        val truth = when { unknown.isChecked -> StaticGroundTruth.UNKNOWN; ambiguous.isChecked -> StaticGroundTruth.AMBIGUOUS_TEST; else -> StaticGroundTruth.KNOWN }
        val actual = if (truth == StaticGroundTruth.KNOWN || truth == StaticGroundTruth.AMBIGUOUS_TEST) identity.selectedItem as? String else null
        if ((truth == StaticGroundTruth.KNOWN || truth == StaticGroundTruth.AMBIGUOUS_TEST) && actual.isNullOrBlank()) { status.text="Select the actual employee."; return }
        val second = result?.ranks?.getOrNull(1); val actualScore = actual?.let { id -> result!!.ranks.firstOrNull { it.employeeId == id }?.score }
        val classification = when (truth) { StaticGroundTruth.UNKNOWN -> "UNKNOWN"; StaticGroundTruth.AMBIGUOUS_TEST -> "AMBIGUOUS_TEST"; StaticGroundTruth.KNOWN -> if (top.employeeId == actual) "GENUINE_TOP1" else "MISIDENTIFIED" }
        recorded = true; record.isEnabled=false
        FaceCalibrationFirestoreRepository.get(applicationContext).recordStaticImage(StaticCalibrationEvent(truth, actual, top.employeeId, top.score, second?.employeeId, second?.score, top.margin, actualScore, classification))
        status.text="Calibration event recorded."
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
