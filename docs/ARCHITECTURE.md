# Architecture

The application is a single Android module organized by feature packages. Face and fingerprint flows identify an employee, then hand off to the shared attendance domain. Existing product/background context is in [docs/codex/ARCHITECTURE.md](codex/ARCHITECTURE.md) and backend details are in [docs/codex/BACKEND_CONTRACTS.md](codex/BACKEND_CONTRACTS.md).

```mermaid
flowchart TD
  UI[Activities and Android views] --> Face[Face biometric layer]
  UI --> Fingerprint[Fingerprint biometric layer]
  Face --> Employee[Employee directory]
  Fingerprint --> Employee
  Employee --> Attendance[AttendanceService]
  Attendance --> Local[Room local attendance store]
  Attendance --> API[Retrofit backend API]
  Local --> Worker[WorkManager pending sync]
  Worker --> API
  Face --> FaceStore[Encrypted Room face store and RAM index]
  Fingerprint --> FingerStore[Room template store and cache]
```

## Components

- **UI:** Activities including `MainActivity`, `FingerprintScanActivity`, `FingerprintRegistrationActivity`, `FingerprintManagementActivity`, `EmployeeBiometricManagementActivity`, face scan/flow/registration activities, admin activities, and `AttendanceActivity`.
- **Face:** `Hfx05DualFaceCamera`, `FaceDetectionRunner`, `YuNetFaceDetectorAdapter`, `SFaceFeatureExtractorAdapter`, `FaceTemplateIndexManager`, `LocalFaceEnrollmentRepository`, and face backup/sync services. The verified hardware path is Camera 0 at physical orientation 90 degrees, with YuNet detection and SFace extraction.
- **Fingerprint:** `Hfx05FingerprintScanner`/`Hfx05NativeBridge`, `FingerprintEnrollmentService`, `SourceAfisFingerprintMatcher`, `IdentificationService`, `LocalBiometricRepository`, `BiometricTemplateCache`, and `FingerprintBackupService`.
- **Attendance/domain:** `AttendanceService` inserts a durable event before an online attempt. `LocalAttendanceRepository` persists it; `PendingAttendanceSyncWorker` retries pending events through WorkManager.
- **Employee data:** `LocalEmployeeDirectory` and `EmployeeDirectoryDatabase` persist employee records and attendance events. `EmployeeSyncService` obtains current employee data from the backend.
- **Network/configuration:** `FingerprintApiClient` creates Retrofit API access through `FingerprintApiService`; `BackendEnvironmentConfig` reads generated BuildConfig values. `DeviceConfigurationRepository` provides local device configuration/ID.

## Face attendance sequence

```mermaid
sequenceDiagram
  participant U as Employee
  participant C as Camera 0
  participant D as YuNet/SFace
  participant I as RAM face index
  participant F as Face flow UI
  participant A as AttendanceService
  U->>C: start Face Recognition
  C->>D: normalized frame
  D->>D: detect, align, extract feature
  D->>I: rank local templates
  I-->>F: identity decision
  F->>A: employee ID plus CHECK IN/OUT
  A->>A: persist event then submit/queue
```

## Fingerprint attendance sequence

```mermaid
sequenceDiagram
  participant U as Employee
  participant S as HF-X05 scanner
  participant M as SourceAFIS matcher
  participant R as template cache/store
  participant A as AttendanceService
  U->>S: present finger
  S->>M: captured image/template
  M->>R: compare local templates
  R-->>A: identified employee ID
  A->>A: persist event then submit/queue
```

## Shared attendance processing

```mermaid
flowchart LR
  ID[Identified employee] --> E[Create attendance event]
  E --> P[Persist PENDING in Room]
  P --> N{Network/configuration available?}
  N -- Yes --> B[Submit to attendance API]
  B -- accepted --> S[Mark SYNCED]
  B -- rejected/debounced --> R[Mark terminal result]
  B -- retryable failure --> Q[Keep PENDING]
  N -- No --> Q
  Q --> W[PendingAttendanceSyncWorker retries]
```

## Boundaries that must remain intact

- Biometric code identifies an employee; `AttendanceService` owns attendance submission and pending-event behavior.
- Face local persistence uses Android-Keystore protection plus Room. Its portable template backups are not Keystore ciphertext; restore validates then encrypts locally and refreshes the RAM index.
- The fingerprint scanner’s GPIO/SPI/reset/native capture lifecycle is protected HF-X05 code and requires physical-device testing.
- NFC and iris are not implemented attendance methods in the current app.
