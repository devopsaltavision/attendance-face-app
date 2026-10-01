# Fingerprint attendance

## Flow

Fingerprint enrollment is launched from employee/biometric management. `FingerprintRegistrationActivity` selects a supported `FingerPosition`, uses `FingerprintEnrollmentService` to collect the required captures, creates a local enrollment through `LocalBiometricRepository`, and starts remote synchronization/backup through `FingerprintBackupService`.

`FingerprintManagementActivity` lists local enrollments, filters by employee/name/finger, supports deletion of an enrollment, and can synchronize fingerprint storage. A delete is coordinated through the backup service; the UI records an explicit local deletion after successful deletion.

For attendance, `FingerprintScanActivity` captures a finger, uses `IdentificationService` and the SourceAFIS matcher/cache to identify an employee from local templates, then passes the employee to the shared `AttendanceService` for Check In or Check Out. It does not own a separate attendance transaction system.

## Storage and layers

- Scanner interface/implementation: `FingerprintScanner`, `Hfx05FingerprintScanner`, `Hfx05NativeBridge`
- Enrollment: `FingerprintEnrollmentService`
- Matching/identification: `SourceAfisFingerprintMatcher`, `IdentificationService`
- Local records/cache: `LocalBiometricRepository`, `BiometricDatabase`, `BiometricTemplateCache`
- Backup/sync: `FingerprintBackupService`

The app reports user-safe scanner/matcher/repository failures. It also has emulator/guide-mode build configuration for development; those modes do not prove HF-X05 hardware behavior.

## HF-X05 protected hardware baseline

The low-level scanner path is proven HF-X05 hardware code. Changes to GPIO, SPI, reset/init, native hardware access, capture lifecycle, or cleanup must be made only for an explicit hardware task and validated on a physical HF-X05. Do not casually replace or rewrite this layer. Hardware-unavailable handling exists for devices where the scanner cannot be used.
