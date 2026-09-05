package com.syntaxgenie.hfx05attendance.face.detection

import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

data class FaceDetectionPerformance(
    val completedInvocations: Long,
    val detectionFramesPerSecond: Double,
    val averageLatencyMillis: Double,
    val p95LatencyMillis: Double,
    val maximumLatencyMillis: Double,
    val replacedPendingFrames: Long,
)

class FaceDetectionRunner(
    private val detector: FaceDetector,
    private val onOutcome: (FaceDetectionInput, FaceDetectionOutcome, FaceDetectionPerformance) -> Unit,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "face-detection-runner")
    }
    private val closed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val pending = AtomicReference<FaceDetectionInput?>(null)
    private val latencyWindow = ArrayDeque<Double>()
    private var completed = 0L
    private var replaced = 0L
    private var firstCompletionNanos = 0L

    fun submit(input: FaceDetectionInput) {
        if (closed.get()) return
        if (running.compareAndSet(false, true)) {
            execute(input)
        } else {
            if (pending.getAndSet(input) != null) synchronized(this) { replaced++ }
        }
    }

    private fun execute(input: FaceDetectionInput) {
        executor.execute {
            Log.d("FaceDetectionRunner", "RUNNER_EXECUTE camera=${input.frame.cameraId}")
            if (closed.get()) return@execute finishInvocation()
            Log.d("FaceDetectionRunner", "RUNNER_DETECT_BEGIN camera=${input.frame.cameraId}")
            detector::detect.startCoroutine(input, object : Continuation<FaceDetectionOutcome> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<FaceDetectionOutcome>) {
                    Log.d("FaceDetectionRunner", "RUNNER_DETECT_COMPLETE success=${result.isSuccess}")
                    val outcome = result.getOrElse { error ->
                        FaceDetectionOutcome.Error(
                            code = error.javaClass.simpleName,
                            message = error.message ?: "Detector failed.",
                            recoverable = true,
                            latencyNanos = 0,
                        )
                    }
                    val performance = record(outcome)
                    if (!closed.get()) onOutcome(input, outcome, performance)
                    finishInvocation()
                }
            })
        }
    }

    private fun finishInvocation() {
        val next = pending.getAndSet(null)
        if (next != null && !closed.get()) {
            execute(next)
        } else {
            running.set(false)
            val raced = pending.getAndSet(null)
            if (raced != null && !closed.get() && running.compareAndSet(false, true)) execute(raced)
        }
    }

    @Synchronized
    private fun record(outcome: FaceDetectionOutcome): FaceDetectionPerformance {
        val now = System.nanoTime()
        if (firstCompletionNanos == 0L) firstCompletionNanos = now
        completed++
        val latencyMillis = outcome.latencyNanos / 1_000_000.0
        latencyWindow.addLast(latencyMillis)
        while (latencyWindow.size > MAX_LATENCY_SAMPLES) latencyWindow.removeFirst()
        val sorted = latencyWindow.sorted()
        val p95Index = ((sorted.size - 1) * 0.95).toInt().coerceAtLeast(0)
        val elapsedSeconds = (now - firstCompletionNanos) / 1_000_000_000.0
        return FaceDetectionPerformance(
            completedInvocations = completed,
            detectionFramesPerSecond = if (elapsedSeconds > 0) completed / elapsedSeconds else 0.0,
            averageLatencyMillis = sorted.average(),
            p95LatencyMillis = sorted[p95Index],
            maximumLatencyMillis = sorted.last(),
            replacedPendingFrames = replaced,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.set(null)
        detector.close()
        executor.shutdownNow()
    }

    companion object { private const val MAX_LATENCY_SAMPLES = 512 }
}
