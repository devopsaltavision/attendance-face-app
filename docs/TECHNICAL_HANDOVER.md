# Technical handover

## Purpose and current scope

HF-X05 Attendance is an Android terminal application for HFSECURITY / Proline HF-X05 devices. It currently implements face and fingerprint employee identification, Check In/Check Out attendance submission, local pending attendance synchronization, employee synchronization, admin/device screens, and biometric enrollment/management. NFC and iris are not implemented.

The central rule is:

```text
Biometric identification -> employeeId -> AttendanceService -> backend
```

Do not introduce method-specific attendance transaction systems.

## High-level implementation

The one application module is `app`. UI is activity/view based. Room stores employee, attendance, fingerprint, and face data; face template persistence is protected by Android Keystore and recognized from a process-wide RAM index. Retrofit/OkHttp connects to the backend. Firebase is used by existing admin/calibration/backup-related code. WorkManager runs pending attendance synchronization.

Important entry points and services:

- `Hfx05AttendanceApplication`: schedules pending attendance sync, warms the face index, refreshes face configuration, retries face sync/restore dispatch.
- `MainActivity`: home screen.
- `AttendanceService`: shared durable attendance recording/submission logic.
- `EmployeeSyncService` and `LocalEmployeeDirectory`: employee synchronization and local employee directory.
- `DeviceConfigurationRepository` and `BackendEnvironmentConfig`: device/backend configuration access.
- `FingerprintApiService`/`FingerprintApiClient`: Retrofit endpoint definitions/client.

## Main screens and navigation

The manifest declares the home, admin login/dashboard, user management, employee biometric management, fingerprint registration/management/scan, face registration/scan/recognition flow, attendance, device backup/settings, and diagnostic activities. Main employee paths are Home to Face Recognition or Fingerprint Recognition, then attendance action/result. Admin paths provide employee selection, biometric registration/management, device configuration, and backup functions.

## Face flow

Conceptually, the implemented flow is:

```text
Camera / hardware capture
-> frame processing / normalization
-> face detection
-> passive spoof assessment
-> feature/template extraction
-> enrollment repository and RAM index
-> 1:N identification
-> employee
-> shared attendance flow
```

`FaceScanActivity` opens Camera 0 at 90 degrees. `YuNetFaceDetectorAdapter` detects faces/landmarks; `SFaceAlignmentMapper` and `SFaceFeatureExtractorAdapter` create SFace features. `FaceTemplateIndexManager` performs the runtime local-template comparison. `FaceRecognitionDecisionPolicy` produces the identity decision consumed by `FaceRecognitionFlowActivity`.

Registration in `face.scan.FaceRegistrationActivity` requires straight, slight-left, and slight-right samples. `LocalFaceEnrollmentRepository` stores encrypted feature data in Room, then the index is updated. `FaceEnrollmentSyncService` backups locally successful enrollment asynchronously; remote failure does not undo local registration. Portable backup templates are validated during restore and re-encrypted for the current Android Keystore.

`PassiveSpoofDetector` is present but the scan activity is configured `OBSERVE_ONLY`; it is assessment/telemetry rather than a blocking anti-spoof gate today. See [Face attendance](FACE_ATTENDANCE.md).

## Fingerprint flow

Conceptually, the implemented flow is:

```text
Fingerprint hardware
-> capture
-> template/feature handling
-> enrollment repository/cache
-> identification
-> employee
-> shared attendance flow
```

`Hfx05FingerprintScanner` and `Hfx05NativeBridge` provide the HF-X05 scanner integration. `FingerprintEnrollmentService` drives enrollment, `SourceAfisFingerprintMatcher` handles matching, `IdentificationService` identifies against `BiometricTemplateCache`/`LocalBiometricRepository`, and `FingerprintScanActivity` hands the identified employee to `AttendanceService`.

`FingerprintRegistrationActivity` receives employee details and a selected `FingerPosition`; `FingerprintManagementActivity` lists, searches, synchronizes, and deletes local enrollment groups. `FingerprintBackupService` provides existing backup/synchronization behavior. Treat low-level GPIO, SPI, reset/init, native access, capture, and cleanup as protected physical-device code. See [Fingerprint attendance](FINGERPRINT_ATTENDANCE.md).

## Attendance and offline behavior

`AttendanceService.record` makes an event ID/timestamp, persists it as pending through `LocalAttendanceRepository`, then attempts the backend request if device/backend/network configuration is available. Accepted events become synced. Network/configuration/retryable server failures remain pending. Backend rejections and debounced/duplicate outcomes are terminally recorded. `PendingAttendanceSyncWorker` retries queued events in batches, and the application schedules it at startup when pending events exist.

This means local employee/biometric recognition can continue without a network where its local data exists. A network outage does not discard the attendance event; it defers backend synchronization. It does not mean remote-only employee or biometric data is available before it has been synchronized/restored locally.

## Employee, persistence, and backend data

Employees are held in `EmployeeDirectoryDatabase`/`LocalEmployeeDirectory`; attendance events are stored through the same local database/attendance DAO. Fingerprint templates use `BiometricDatabase` and its repository/cache. Face enrollments use `FaceEnrollmentDatabase`, Android-Keystore protection, and a RAM index. Do not log or document raw biometric payloads.

The Retrofit service includes enrollment, face enrollment/lookup, user sync, and attendance endpoints; current face backup reuses the fingerprint enrollment endpoint with biometric type `FACE`. Backend error mapping converts transport/API errors into application outcomes. The detailed, existing endpoint document is [Backend contracts](codex/BACKEND_CONTRACTS.md).

## Configuration and device dependencies

Build configuration is sourced from an environment variable, ignored `local.properties`, or Gradle property. The app needs appropriate backend URL/API/device configuration for online work; credentials must remain outside Git. The manifest declares internet, network-state, package-install, and camera permissions. Face is verified with Camera 0/90 degrees/YuNet/SFace. Fingerprint relies on native HF-X05 hardware integration and must be tested on the device.

## Architectural boundaries

- Keep biometric identification separate from attendance business logic.
- Keep face Camera 0, 90-degree orientation, YuNet preprocessing, corrected SFace alignment, and the current models unchanged unless a task explicitly targets them.
- Keep fingerprint low-level hardware code isolated and physically tested.
- Local biometric enrollment must remain valid when remote backup is temporarily unavailable.
- Portable backup data is not Android-Keystore ciphertext; restore must validate, encrypt locally, persist, and refresh runtime state.
- Do not make calibration/debug UI the normal production face behavior or invent recognition thresholds.

Further status and known limitations: [Project status](codex/PROJECT_STATUS.md), [Architecture background](codex/ARCHITECTURE.md), and [Roadmap](codex/ROADMAP.md).
