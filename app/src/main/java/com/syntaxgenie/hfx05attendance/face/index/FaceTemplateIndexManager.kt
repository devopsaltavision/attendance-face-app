package com.syntaxgenie.hfx05attendance.face.index

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.syntaxgenie.hfx05attendance.BuildConfig
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceFeatureCodec
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceModelConfiguration
import com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics.SFaceDebugCandidate
import com.syntaxgenie.hfx05attendance.face.repository.AndroidKeystoreFaceTemplateProtector
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentStatus
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateCompatibility
import com.syntaxgenie.hfx05attendance.face.repository.local.FaceEnrollmentDatabase
import com.syntaxgenie.hfx05attendance.face.repository.local.LocalFaceEnrollmentRepository
import java.util.concurrent.Executors

enum class FaceTemplateIndexState { UNLOADED, LOADING, READY, DIRTY, ERROR }

/**
 * Application-process cache of decrypted, compatible SFace templates. Room remains the source of
 * truth; nothing from this index is written outside process memory.
 */
data class FaceTemplateIndexEntry(val employeeId: String, val templates: List<ByteArray>) {
    init {
        require(employeeId.isNotBlank())
        require(templates.size == TEMPLATES_PER_EMPLOYEE)
        require(templates.all { it.isNotEmpty() })
    }

    internal fun asCandidate() = SFaceDebugCandidate(employeeId, templates)

    companion object { const val TEMPLATES_PER_EMPLOYEE = 3 }
}

data class FaceTemplateIndexSnapshot internal constructor(
    private val entries: Map<String, FaceTemplateIndexEntry>,
) {
    val employeeCount: Int get() = entries.size
    val templateCount: Int get() = entries.values.sumOf { it.templates.size }
    internal fun candidates(): List<SFaceDebugCandidate> = entries.values.map { it.asCandidate() }
    /** Returns only one selected employee's in-memory templates, defensively copied for callers. */
    fun templatesForEmployee(employeeId: String): List<ByteArray>? =
        entries[employeeId]?.templates?.map { it.copyOf() }
    internal fun entriesForUpdate(): Map<String, FaceTemplateIndexEntry> = entries
}

class FaceTemplateIndexManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "face-template-index")
    }
    private val lock = Any()
    private val compatibility = FaceTemplateCompatibility(
        SFaceModelConfiguration.ENGINE_ID,
        SFaceModelConfiguration.MODEL_ID,
        SFaceModelConfiguration.MODEL_VERSION,
        SFaceFeatureCodec.FORMAT_ID,
    )
    private var callbacks = mutableListOf<(Result<FaceTemplateIndexSnapshot>) -> Unit>()
    private var refreshAfterLoad = false

    @Volatile private var currentState = FaceTemplateIndexState.UNLOADED
    @Volatile private var currentSnapshot: FaceTemplateIndexSnapshot? = null

    fun state(): FaceTemplateIndexState = currentState

    fun readySnapshot(): FaceTemplateIndexSnapshot? = if (currentState == FaceTemplateIndexState.READY) currentSnapshot else null

    /** Starts the one background load for this process, without blocking application startup. */
    fun warmUp() = ensureReady { }

    /** Delivers a ready immutable index from the loader thread; callers must marshal UI work. */
    fun ensureReady(callback: (Result<FaceTemplateIndexSnapshot>) -> Unit) {
        val ready = synchronized(lock) {
            currentSnapshot?.takeIf { currentState == FaceTemplateIndexState.READY }
        }
        if (ready != null) {
            debug("FACE_INDEX_REUSED employees=${ready.employeeCount} templates=${ready.templateCount}")
            callback(Result.success(ready))
            return
        }
        synchronized(lock) {
            currentSnapshot?.takeIf { currentState == FaceTemplateIndexState.READY }?.let {
                debug("FACE_INDEX_REUSED employees=${it.employeeCount} templates=${it.templateCount}")
                callback(Result.success(it))
                return
            }
            callbacks += callback
            if (currentState == FaceTemplateIndexState.LOADING) return
            currentState = FaceTemplateIndexState.LOADING
            executor.execute(::loadAll)
        }
    }

    /** Replaces one READY employee entry after its Room transaction has committed. */
    fun addOrReplaceEmployee(employeeId: String, templates: List<ByteArray>) {
        if (templates.size != FaceTemplateIndexEntry.TEMPLATES_PER_EMPLOYEE) {
            markDirty()
            return
        }
        val entry = FaceTemplateIndexEntry(employeeId, templates.map { it.copyOf() })
        synchronized(lock) {
            if (currentState != FaceTemplateIndexState.READY || currentSnapshot == null) {
                refreshAfterLoad = currentState == FaceTemplateIndexState.LOADING
                currentState = if (currentState == FaceTemplateIndexState.LOADING) currentState else FaceTemplateIndexState.DIRTY
                return
            }
            val updated = LinkedHashMap(currentSnapshotEntries())
            updated[employeeId] = entry
            currentSnapshot = FaceTemplateIndexSnapshot(updated)
        }
        debug("FACE_INDEX_EMPLOYEE_UPDATED employeeId=$employeeId")
    }

    /** Removes one READY employee entry after Room deletion succeeds. */
    fun removeEmployee(employeeId: String) {
        synchronized(lock) {
            if (currentState != FaceTemplateIndexState.READY || currentSnapshot == null) {
                refreshAfterLoad = currentState == FaceTemplateIndexState.LOADING
                currentState = if (currentState == FaceTemplateIndexState.LOADING) currentState else FaceTemplateIndexState.DIRTY
                return
            }
            val updated = LinkedHashMap(currentSnapshotEntries())
            updated.remove(employeeId)
            currentSnapshot = FaceTemplateIndexSnapshot(updated)
        }
        debug("FACE_INDEX_EMPLOYEE_REMOVED employeeId=$employeeId")
    }

    /** For restore/import/bulk updates where a complete background rebuild is safer. */
    fun markDirty() {
        synchronized(lock) {
            if (currentState == FaceTemplateIndexState.LOADING) {
                refreshAfterLoad = true
                return
            }
            currentState = FaceTemplateIndexState.DIRTY
            currentSnapshot = null
        }
        debug("FACE_INDEX_REFRESH")
    }

    fun refresh() {
        markDirty()
        ensureReady { }
    }

    private fun loadAll() {
        val started = SystemClock.elapsedRealtime()
        debug("FACE_INDEX_WARMUP_START")
        val result = runCatching {
            val repository = LocalFaceEnrollmentRepository(
                FaceEnrollmentDatabase.create(appContext).faceEnrollmentDao(),
                AndroidKeystoreFaceTemplateProtector(),
            )
            val grouped = repository.listCompatible(compatibility)
                .asSequence()
                .filter { it.status == FaceEnrollmentStatus.ACTIVE && it.metadata.enrollmentSampleCount == FaceTemplateIndexEntry.TEMPLATES_PER_EMPLOYEE }
                .groupBy { it.employeeId }
            val indexed = LinkedHashMap<String, FaceTemplateIndexEntry>()
            grouped.forEach { (employeeId, records) ->
                if (records.size == FaceTemplateIndexEntry.TEMPLATES_PER_EMPLOYEE) {
                    indexed[employeeId] = FaceTemplateIndexEntry(employeeId, records.map { it.templatePayload() })
                }
            }
            FaceTemplateIndexSnapshot(indexed)
        }
        val callbacksToRun: List<(Result<FaceTemplateIndexSnapshot>) -> Unit>
        val refresh: Boolean
        synchronized(lock) {
            callbacksToRun = callbacks.toList()
            callbacks.clear()
            refresh = refreshAfterLoad
            refreshAfterLoad = false
            result.onSuccess {
                currentSnapshot = it
                currentState = FaceTemplateIndexState.READY
                debug("FACE_INDEX_READY employees=${it.employeeCount} templates=${it.templateCount} loadMillis=${SystemClock.elapsedRealtime() - started}")
            }.onFailure {
                currentSnapshot = null
                currentState = FaceTemplateIndexState.ERROR
                Log.e(LOG_TAG, "FACE_INDEX_LOAD_FAILED", it)
            }
        }
        callbacksToRun.forEach { it(result) }
        if (refresh) refresh()
    }

    private fun currentSnapshotEntries(): Map<String, FaceTemplateIndexEntry> {
        return checkNotNull(currentSnapshot).entriesForUpdate()
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(LOG_TAG, message)
    }

    companion object {
        const val LOG_TAG = "FaceTemplateIndex"
        @Volatile private var instance: FaceTemplateIndexManager? = null

        fun get(context: Context): FaceTemplateIndexManager = instance ?: synchronized(this) {
            instance ?: FaceTemplateIndexManager(context).also { instance = it }
        }
    }
}
