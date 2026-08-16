package com.syntaxgenie.hfx05attendance.fingerprint.enrollment

import com.syntaxgenie.hfx05attendance.fingerprint.matcher.FingerprintMatcher
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.FingerprintTemplate
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherResult
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRecord
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRepository
import com.syntaxgenie.hfx05attendance.fingerprint.repository.RepositoryResult
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.FingerprintScanner
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerResult
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.diagnostics.CaptureImageStatisticsCalculator
import java.util.UUID

class FingerprintEnrollmentService(
    private val scanner: FingerprintScanner,
    private val matcher: FingerprintMatcher,
    private val repository: BiometricRepository,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val newEnrollmentId: () -> String = { UUID.randomUUID().toString() },
    private val newRecordId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private var session: EnrollmentSession? = null
    private val stagedTemplates = mutableListOf<FingerprintTemplate>()

    fun start(request: EnrollmentRequest): EnrollmentResult<EnrollmentSession> {
        synchronized(lock) {
            if (session?.state in BUSY_STATES || session?.state == EnrollmentState.READY) {
                return error(EnrollmentError.SESSION_BUSY, session)
            }
        }
        val existing = repository.getByEmployeeAndFinger(request.employeeId, request.fingerPosition)
        if (existing is RepositoryResult.Error) {
            return repositoryError(existing, EnrollmentError.TEMPLATE_STORAGE_FAILED, null)
        }
        if ((existing as RepositoryResult.Success).value.isNotEmpty()) {
            return error(EnrollmentError.ENROLLMENT_ALREADY_EXISTS)
        }
        return synchronized(lock) {
            if (session?.state in BUSY_STATES || session?.state == EnrollmentState.READY) {
                error(EnrollmentError.SESSION_BUSY, session)
            } else {
                stagedTemplates.clear()
                EnrollmentSession(
                    enrollmentId = newEnrollmentId(),
                    employeeId = request.employeeId,
                    fingerPosition = request.fingerPosition,
                    requiredCaptures = REQUIRED_CAPTURES,
                    completedCaptures = 0,
                    state = EnrollmentState.READY,
                ).also { session = it }.let { EnrollmentResult.Success(it) }
            }
        }
    }

    fun captureNext(
        progress: ((EnrollmentProgress) -> Unit)? = null,
    ): EnrollmentResult<EnrollmentSession> {
        val active = synchronized(lock) {
            val current = session ?: return error(EnrollmentError.ENROLLMENT_NOT_STARTED)
            when (current.state) {
                EnrollmentState.COMPLETED -> return error(EnrollmentError.ENROLLMENT_ALREADY_COMPLETE, current)
                EnrollmentState.CANCELLED -> return error(EnrollmentError.ENROLLMENT_CANCELLED, current)
                EnrollmentState.CAPTURING, EnrollmentState.PROCESSING, EnrollmentState.SAVING ->
                    return error(EnrollmentError.SESSION_BUSY, current)
                EnrollmentState.FAILED -> return error(EnrollmentError.ENROLLMENT_INCOMPLETE, current)
                EnrollmentState.READY -> current.copy(state = EnrollmentState.CAPTURING, lastError = null)
            }.also { session = it }
        }
        emit(progress, active)

        val captured = try {
            scanner.capture { scannerProgress ->
                progress?.invoke(
                    EnrollmentProgress(
                        EnrollmentState.CAPTURING,
                        active.nextCaptureNumber,
                        active.completedCaptures,
                        REQUIRED_CAPTURES,
                        scannerProgress,
                    ),
                )
            }
        } catch (throwable: Throwable) {
            return recoverableFailure(
                EnrollmentError.CAPTURE_FAILED,
                "Scanner threw ${throwable.javaClass.simpleName}: ${throwable.message}",
                throwable,
            )
        }
        if (captured is ScannerResult.Error) {
            return recoverableFailure(
                EnrollmentError.CAPTURE_FAILED,
                captured.diagnosticDetails,
                captured.cause,
                EnrollmentLowerLayerError.Scanner(captured),
            )
        }
        val image = (captured as ScannerResult.Success).value
        val usable = CaptureImageStatisticsCalculator.calculate(image.pixels()).usable
        if (!usable) {
            return recoverableFailure(
                EnrollmentError.CAPTURE_REJECTED,
                "Capture failed the existing unique/range/dominant-pixel usability checks.",
            )
        }

        val processing = synchronized(lock) {
            requireNotNull(session).copy(state = EnrollmentState.PROCESSING)
                .also { session = it }
        }
        emit(progress, processing)
        val created = try {
            matcher.createTemplate(image)
        } catch (throwable: Throwable) {
            return recoverableFailure(
                EnrollmentError.TEMPLATE_CREATION_FAILED,
                "Matcher threw ${throwable.javaClass.simpleName}: ${throwable.message}",
                throwable,
            )
        }
        if (created is MatcherResult.Error) {
            return recoverableFailure(
                EnrollmentError.TEMPLATE_CREATION_FAILED,
                created.diagnosticDetails,
                created.cause,
                EnrollmentLowerLayerError.Matcher(created),
            )
        }

        val readyOrSaving = synchronized(lock) {
            stagedTemplates += (created as MatcherResult.Success).value
            val completed = stagedTemplates.size
            requireNotNull(session).copy(
                completedCaptures = completed,
                state = if (completed == REQUIRED_CAPTURES) EnrollmentState.SAVING else EnrollmentState.READY,
                lastError = null,
            ).also { session = it }
        }
        emit(progress, readyOrSaving)
        return if (readyOrSaving.state == EnrollmentState.SAVING) commitStaged() else {
            EnrollmentResult.Success(readyOrSaving)
        }
    }

    fun retrySave(): EnrollmentResult<EnrollmentSession> {
        synchronized(lock) {
            val current = session ?: return error(EnrollmentError.ENROLLMENT_NOT_STARTED)
            if (current.state in BUSY_STATES) return error(EnrollmentError.SESSION_BUSY, current)
            if (current.state == EnrollmentState.COMPLETED) {
                return error(EnrollmentError.ENROLLMENT_ALREADY_COMPLETE, current)
            }
            if (current.completedCaptures != REQUIRED_CAPTURES || stagedTemplates.size != REQUIRED_CAPTURES) {
                return error(EnrollmentError.ENROLLMENT_INCOMPLETE, current)
            }
            session = current.copy(state = EnrollmentState.SAVING, lastError = null)
        }
        return commitStaged()
    }

    fun cancel(): EnrollmentResult<EnrollmentSession> = synchronized(lock) {
        val current = session ?: return error(EnrollmentError.ENROLLMENT_NOT_STARTED)
        if (current.state in BUSY_STATES) return error(EnrollmentError.SESSION_BUSY, current)
        if (current.state == EnrollmentState.COMPLETED) {
            return error(EnrollmentError.ENROLLMENT_ALREADY_COMPLETE, current)
        }
        stagedTemplates.clear()
        current.copy(state = EnrollmentState.CANCELLED, lastError = EnrollmentError.ENROLLMENT_CANCELLED)
            .also { session = it }
            .let { EnrollmentResult.Success(it) }
    }

    fun currentSession(): EnrollmentSession? = synchronized(lock) { session?.copy() }

    private fun commitStaged(): EnrollmentResult<EnrollmentSession> {
        val current: EnrollmentSession
        val templates: List<FingerprintTemplate>
        synchronized(lock) {
            current = requireNotNull(session)
            templates = stagedTemplates.toList()
        }
        val now = currentTimeMillis().coerceAtLeast(1)
        val records = templates.mapIndexed { index, template ->
            BiometricRecord(
                recordId = newRecordId(),
                enrollmentId = current.enrollmentId,
                employeeId = current.employeeId,
                fingerPosition = current.fingerPosition,
                templateSlot = index + 1,
                template = template,
                createdAtEpochMillis = now,
            )
        }
        return when (val saved = repository.saveEnrollment(records)) {
            is RepositoryResult.Success -> synchronized(lock) {
                stagedTemplates.clear()
                requireNotNull(session).copy(
                    completedCaptures = REQUIRED_CAPTURES,
                    state = EnrollmentState.COMPLETED,
                    lastError = null,
                ).also { session = it }.let { EnrollmentResult.Success(it) }
            }
            is RepositoryResult.Error -> synchronized(lock) {
                session = requireNotNull(session).copy(
                    state = EnrollmentState.FAILED,
                    lastError = EnrollmentError.TEMPLATE_STORAGE_FAILED,
                )
                repositoryError(saved, EnrollmentError.TEMPLATE_STORAGE_FAILED, session)
            }
        }
    }

    private fun recoverableFailure(
        enrollmentError: EnrollmentError,
        details: String?,
        cause: Throwable? = null,
        lowerLayer: EnrollmentLowerLayerError? = null,
    ): EnrollmentResult.Error = synchronized(lock) {
        session = requireNotNull(session).copy(state = EnrollmentState.READY, lastError = enrollmentError)
        EnrollmentResult.Error(enrollmentError, session, details, cause, lowerLayer)
    }

    private fun repositoryError(
        repositoryError: RepositoryResult.Error,
        enrollmentError: EnrollmentError,
        current: EnrollmentSession?,
    ) = EnrollmentResult.Error(
        enrollmentError,
        current,
        repositoryError.diagnosticDetails,
        repositoryError.cause,
        EnrollmentLowerLayerError.Repository(repositoryError),
    )

    private fun error(
        error: EnrollmentError,
        current: EnrollmentSession? = null,
    ) = EnrollmentResult.Error(error, current)

    private fun emit(callback: ((EnrollmentProgress) -> Unit)?, current: EnrollmentSession) {
        callback?.invoke(
            EnrollmentProgress(
                current.state,
                current.nextCaptureNumber,
                current.completedCaptures,
                current.requiredCaptures,
            ),
        )
    }

    companion object {
        const val REQUIRED_CAPTURES = 5
        private val BUSY_STATES = setOf(
            EnrollmentState.CAPTURING,
            EnrollmentState.PROCESSING,
            EnrollmentState.SAVING,
        )
    }
}
