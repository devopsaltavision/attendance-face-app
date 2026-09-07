package com.syntaxgenie.hfx05attendance.face.calibration

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.syntaxgenie.hfx05attendance.BuildConfig
import com.syntaxgenie.hfx05attendance.backend.config.DeviceConfigurationRepository
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceFeatureCodec
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceModelConfiguration

/**
 * Direct Firestore calibration telemetry and threshold configuration. Firestore is best-effort:
 * failures never block the recognition or attendance UI flows.
 */
class FaceCalibrationFirestoreRepository private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val firestore = FirebaseFirestore.getInstance(FIRESTORE_DATABASE_ID)
    private val deviceConfiguration = DeviceConfigurationRepository(applicationContext)

    @Volatile
    private var lastValidConfig: FaceRecognitionThresholdConfig = loadCachedConfig()

    fun currentConfig(): FaceRecognitionThresholdConfig = lastValidConfig

    fun refreshThresholdConfig() {
        logDebug("FACE_FIRESTORE_DATABASE database=$FIRESTORE_DATABASE_ID")
        logDebug("FACE_CALIBRATION_AUTH authenticated=${FirebaseAuth.getInstance().currentUser != null}")
        firestore.collection(CONFIG_COLLECTION).document(CONFIG_DOCUMENT).get()
            .addOnSuccessListener { document ->
                if (!document.exists()) {
                    logDebug("FACE_THRESHOLD_CONFIG mode=${lastValidConfig.mode} version=${lastValidConfig.configVersion ?: "none"}")
                    return@addOnSuccessListener
                }
                val parsed = FaceRecognitionThresholdConfigParser.parse(document.data.orEmpty())
                if (parsed == null) {
                    Log.w(LOG_TAG, "FACE_THRESHOLD_CONFIG_INVALID ignored")
                    return@addOnSuccessListener
                }
                lastValidConfig = parsed
                cache(parsed)
                logDebug("FACE_THRESHOLD_CONFIG mode=${parsed.mode} version=${parsed.configVersion}")
            }
            .addOnFailureListener { error ->
                if (BuildConfig.DEBUG) {
                    val code = (error as? com.google.firebase.firestore.FirebaseFirestoreException)?.code
                    Log.w(LOG_TAG, "FACE_THRESHOLD_CONFIG_FETCH_FAILED code=${code ?: "unknown"}", error)
                }
            }
    }

    fun recordSelection(event: FaceCalibrationEvent) {
        val payload = hashMapOf<String, Any?>(
            "deviceId" to event.deviceId,
            "modelId" to event.modelId,
            "modelVersion" to event.modelVersion,
            "templateFormat" to event.templateFormat,
            "topEmployeeId" to event.topEmployeeId,
            "topScore" to event.topScore,
            "secondEmployeeId" to event.secondEmployeeId,
            "secondScore" to event.secondScore,
            "margin" to event.margin,
            "selectedEmployeeId" to event.selectedEmployeeId,
            "selectionType" to event.selectionType.name,
            "candidateCount" to event.candidateCount,
            "configVersion" to event.configVersion,
            "createdAt" to FieldValue.serverTimestamp(),
        )
        firestore.collection(EVENT_COLLECTION).add(payload)
            .addOnSuccessListener {
                logDebug(
                    "FACE_CALIBRATION_EVENT type=${event.selectionType} topEmployee=${event.topEmployeeId} " +
                        "topScore=${event.topScore} selectedEmployee=${event.selectedEmployeeId}",
                )
            }
            .addOnFailureListener { error ->
                if (BuildConfig.DEBUG) Log.w(LOG_TAG, "FACE_CALIBRATION_WRITE_FAILED", error)
            }
    }

    /** Static-image calibration records numeric labels/scores only; never the image or feature. */
    fun recordStaticImage(event: StaticCalibrationEvent) {
        val payload = hashMapOf<String, Any?>(
            "sourceType" to "STATIC_IMAGE", "groundTruthType" to event.groundTruth.name,
            "trueEmployeeId" to event.trueEmployeeId, "topEmployeeId" to event.topEmployeeId,
            "topScore" to event.topScore, "secondEmployeeId" to event.secondEmployeeId,
            "secondScore" to event.secondScore, "margin" to event.margin,
            "correctEmployeeScore" to event.correctEmployeeScore, "classification" to event.classification,
            "configVersion" to lastValidConfig.configVersion, "createdAt" to FieldValue.serverTimestamp(),
        )
        firestore.collection(EVENT_COLLECTION).add(payload)
    }

    /** Best-effort numeric telemetry only. A disabled config performs no Firestore write. */
    fun recordLiveRecognition(event: FaceRecognitionTelemetryEvent) {
        if (!FaceRecognitionTelemetryPolicy.shouldWrite(lastValidConfig.accuracyLoggingEnabled)) return
        firestore.collection(EVENT_COLLECTION).add(hashMapOf<String, Any?>(
            "sourceType" to "LIVE_RECOGNITION", "decision" to event.decision,
            "topEmployeeId" to event.topEmployeeId, "topScore" to event.topScore,
            "secondEmployeeId" to event.secondEmployeeId, "secondScore" to event.secondScore,
            "margin" to event.margin, "matchThresholdUsed" to event.matchThresholdUsed,
            "minMatchMarginUsed" to event.minMatchMarginUsed, "configVersion" to event.configVersion,
            "deviceId" to deviceConfiguration.deviceId(), "createdAt" to FieldValue.serverTimestamp(),
        )).addOnFailureListener { error -> if (BuildConfig.DEBUG) Log.w(LOG_TAG, "FACE_RECOGNITION_TELEMETRY_FAILED", error) }
    }

    fun newEvent(
        topEmployeeId: String,
        topScore: Double,
        secondEmployeeId: String?,
        secondScore: Double?,
        selectedEmployeeId: String?,
        selectionType: FaceCalibrationSelectionType,
        candidateCount: Int,
    ): FaceCalibrationEvent = FaceCalibrationEvent(
        deviceId = deviceConfiguration.deviceId(),
        modelId = SFaceModelConfiguration.MODEL_ID,
        modelVersion = SFaceModelConfiguration.MODEL_VERSION,
        templateFormat = SFaceFeatureCodec.FORMAT_ID,
        topEmployeeId = topEmployeeId,
        topScore = topScore,
        secondEmployeeId = secondEmployeeId,
        secondScore = secondScore,
        margin = secondScore?.let { topScore - it },
        selectedEmployeeId = selectedEmployeeId,
        selectionType = selectionType,
        candidateCount = candidateCount,
        configVersion = lastValidConfig.configVersion,
    )

    private fun cache(config: FaceRecognitionThresholdConfig) {
        preferences.edit()
            .putString(KEY_MODE, config.mode.name)
            .putInt(KEY_VERSION, config.configVersion ?: 0)
            .putValue(KEY_MATCH_THRESHOLD, config.matchThreshold)
            .putValue(KEY_MIN_MATCH_MARGIN, config.minMatchMargin)
            .putBoolean(KEY_ACCURACY_LOGGING_ENABLED, config.accuracyLoggingEnabled)
            .putValue(KEY_CANDIDATE_MINIMUM_SCORE, config.candidateMinimumScore)
            .putValue(KEY_CANDIDATE_MAXIMUM_GAP, config.candidateMaximumGap)
            .putValue(KEY_DUPLICATE_ENROLLMENT_THRESHOLD, config.duplicateEnrollmentThreshold)
            .apply()
    }

    private fun loadCachedConfig(): FaceRecognitionThresholdConfig {
        val mode = preferences.getString(KEY_MODE, null)?.let { runCatching { FaceRecognitionConfigMode.valueOf(it) }.getOrNull() }
            ?: return productionFallbackConfig()
        val raw = mapOf<String, Any?>(
            "mode" to mode.name,
            "configVersion" to preferences.getInt(KEY_VERSION, 0),
            "matchThreshold" to preferences.valueOrNull(KEY_MATCH_THRESHOLD),
            "minMatchMargin" to preferences.valueOrNull(KEY_MIN_MATCH_MARGIN),
            "accuracyLoggingEnabled" to preferences.getBoolean(KEY_ACCURACY_LOGGING_ENABLED, false),
            "candidateMinimumScore" to preferences.valueOrNull(KEY_CANDIDATE_MINIMUM_SCORE),
            "candidateMaximumGap" to preferences.valueOrNull(KEY_CANDIDATE_MAXIMUM_GAP),
            "duplicateEnrollmentThreshold" to preferences.valueOrNull(KEY_DUPLICATE_ENROLLMENT_THRESHOLD),
        )
        return FaceRecognitionThresholdConfigParser.parse(raw) ?: productionFallbackConfig()
    }

    private fun android.content.SharedPreferences.Editor.putValue(key: String, value: Double?) = apply {
        if (value == null) remove(key) else putString(key, value.toString())
    }

    private fun android.content.SharedPreferences.valueOrNull(key: String): Double? =
        if (contains(key)) getString(key, null)?.toDoubleOrNull() else null

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) Log.i(LOG_TAG, message)
    }

    companion object {
        private const val LOG_TAG = "FaceCalibration"
        private const val FIRESTORE_DATABASE_ID = "southernlanka"
        private const val PREFERENCES = "face_recognition_threshold_config"
        private const val CONFIG_COLLECTION = "face_recognition_config"
        private const val CONFIG_DOCUMENT = "hf_x05_sface_v1"
        private const val EVENT_COLLECTION = "face_calibration_events"
        private const val KEY_MODE = "mode"
        private const val KEY_VERSION = "config_version"
        private const val KEY_MATCH_THRESHOLD = "match_threshold"
        private const val KEY_MIN_MATCH_MARGIN = "min_match_margin"
        private const val KEY_ACCURACY_LOGGING_ENABLED = "accuracy_logging_enabled"
        private const val KEY_CANDIDATE_MINIMUM_SCORE = "candidate_minimum_score"
        private const val KEY_CANDIDATE_MAXIMUM_GAP = "candidate_maximum_gap"
        private const val KEY_DUPLICATE_ENROLLMENT_THRESHOLD = "duplicate_enrollment_threshold"

        @Volatile private var instance: FaceCalibrationFirestoreRepository? = null

        fun get(context: Context): FaceCalibrationFirestoreRepository = instance ?: synchronized(this) {
            instance ?: FaceCalibrationFirestoreRepository(context).also { instance = it }
        }

        fun productionFallbackConfig() = FaceRecognitionThresholdConfig(
            mode = FaceRecognitionConfigMode.PRODUCTION,
            matchThreshold = BuildConfig.FACE_MATCH_THRESHOLD,
            minMatchMargin = BuildConfig.FACE_MIN_MATCH_MARGIN,
            accuracyLoggingEnabled = BuildConfig.FACE_ACCURACY_LOGGING_ENABLED,
            candidateMinimumScore = null,
            candidateMaximumGap = null,
            duplicateEnrollmentThreshold = null,
            configVersion = null,
        )
    }
}

/** Pure validation so remote Firestore values can never introduce unsafe defaults. */
object FaceRecognitionThresholdConfigParser {
    fun parse(values: Map<String, Any?>): FaceRecognitionThresholdConfig? {
        val mode = (values["mode"] as? String)?.let { runCatching { FaceRecognitionConfigMode.valueOf(it) }.getOrNull() } ?: return null
        val version = (values["configVersion"] as? Number)?.toIntExactOrNull()?.takeIf { it > 0 } ?: return null
        val scoreNames = listOf(
            "matchThreshold",
            "minMatchMargin",
            "candidateMinimumScore",
            "candidateMaximumGap",
            "duplicateEnrollmentThreshold",
        )
        if (scoreNames.any { values.hasInvalidScore(it) }) return null
        val match = values.scoreOrNull("matchThreshold")
        val margin = values.scoreOrNull("minMatchMargin")
        val logging = values["accuracyLoggingEnabled"] as? Boolean
        val minimum = values.scoreOrNull("candidateMinimumScore")
        val maximumGap = values.scoreOrNull("candidateMaximumGap")
        val duplicate = values.scoreOrNull("duplicateEnrollmentThreshold")
        if (mode == FaceRecognitionConfigMode.PRODUCTION &&
            (match == null || margin == null || logging == null)
        ) return null
        return FaceRecognitionThresholdConfig(mode, match, margin, logging ?: false, minimum, maximumGap, duplicate, version)
    }

    private fun Map<String, Any?>.scoreOrNull(name: String): Double? = (this[name] as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it in 0.0..1.0 }

    private fun Map<String, Any?>.hasInvalidScore(name: String): Boolean =
        containsKey(name) && this[name] != null && scoreOrNull(name) == null

    private fun Number.toIntExactOrNull(): Int? {
        val value = toDouble()
        if (!value.isFinite() || value != value.toLong().toDouble()) return null
        return value.toLong().toInt().takeIf { it > 0 && it.toLong() == value.toLong() }
    }
}
