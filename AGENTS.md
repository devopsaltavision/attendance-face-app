# HF-X05 Attendance App — Codex Instructions

## Token efficiency

Codex quota is limited.

- Read this file before doing any task.
- Do not broadly inspect the repository unless required.
- Read only the relevant file under `docs/codex/`.
- Treat facts marked VERIFIED as established; do not rediscover them.
- Search for exact symbols/files before opening large files.
- Never inspect `build/`, `.gradle/`, decompiled reference apps, or generated files unless explicitly required.
- Make the smallest possible change.
- One task = one outcome.
- Do not perform unrelated cleanup.
- Do not create large new test infrastructure.
- Reuse existing services and architecture.
- After completing the requested task, return concise PASS/FAIL results and STOP.

## Repository

Path:
`G:\SGX\hfx05-attendance`

Current baseline commit:
`22c4981`

Branch:
`spike/x05-fingerprint-hardware`

Target device:
HFSECURITY / Proline HF-X05

Android:
Android 11 / API 30

ADB serial:
`HF20250221000350A62`

Production ABI:
`arm64-v8a`

Package:
`com.syntaxgenie.hfx05attendance`

## Product direction

This is a multi-biometric attendance terminal.

Supported/planned methods:

- Fingerprint
- Face
- NFC card
- Iris — future, not yet proven

An employee may have multiple registered attendance methods.

Biometric modules identify an `employeeId`.

Attendance business logic must remain shared:

`Biometric identification -> employeeId -> AttendanceService -> backend`

Do not create separate attendance transaction systems for Face, Fingerprint,
NFC, or Iris.

## Protected fingerprint baseline

The HF-X05 fingerprint hardware implementation is working.

Do not modify low-level fingerprint GPIO/SPI/reset/init/capture/cleanup code
unless the task explicitly requires it.

## Protected face baseline

VERIFIED working:

- Camera ID: 0
- Camera physical orientation: 90 degrees
- YuNet face detection
- SFace recognition
- corrected SFace alignment geometry
- 3-sample face enrollment
- encrypted Room persistence
- RAM face template index
- release recognition flow in CALIBRATION mode
- backend face backup/restore integration

Do not change Camera0, orientation 90, YuNet preprocessing, SFace alignment,
or face model unless a task explicitly targets them.

Face models:

- detector: `face_detection_yunet_2023mar.onnx`
- recognizer: `face_recognition_sface_2021dec.onnx`
- format: `sface-f32le-v1`
- SFace feature: 128 Float32 = 512 bytes

AuraFace/glintr100 is rejected and should not be part of production.

## Runtime modes

Keep these concepts separate:

DEVELOPMENT
- diagnostics/testing allowed

CALIBRATION
- release engine runs normally
- ranking/manual confirmation allowed
- calibration data collected
- no invented production thresholds

PRODUCTION
- calibrated acceptance/rejection rules
- simple user-facing UI
- UNKNOWN and AMBIGUOUS must not become attendance matches

Never use `BuildConfig.DEBUG` as the only gate for required production behavior.

## Local-first rule

Attendance terminal must continue working without network access when local data
is available.

Biometric enrollment:

1. save locally
2. update RAM/index
3. show success
4. backup remotely asynchronously

Backend failure must not invalidate successful local registration.

Never upload raw face images unless explicitly designed and approved.

## Backup/restore

Local biometric data is encrypted.

Backend backup must contain portable templates, not Android-Keystore ciphertext.

After reinstall/device recovery:

backend portable template
-> validate
-> encrypt with current Android Keystore
-> Room
-> RAM index

## APK size

APK size is currently a priority.

HF-X05 production builds should use arm64-v8a only.

Do not remove:

- YuNet
- SFace
- required fingerprint native libraries

`glintr100.onnx` / AuraFace is rejected and should be audited/removed.

Do not blindly enable R8 or change recognition models just to reduce APK size.

## User experience

Target users are non-technical employees/admins.

UI rules:

- large touch targets
- short labels
- proper icons
- strong contrast
- no technical errors
- no raw API error messages
- simple registration/manage/delete flows

## Detailed context

Read only when relevant:

- `docs/codex/PROJECT_STATUS.md`
- `docs/codex/ARCHITECTURE.md`
- `docs/codex/BACKEND_CONTRACTS.md`
- `docs/codex/ROADMAP.md`