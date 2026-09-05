# Development Roadmap

## Purpose

This roadmap defines the order of work for the HF-X05 multi-biometric attendance application.

The goal is to avoid parallel development, repeated investigation, and unnecessary Codex token usage.

Rules:

- One task = one outcome.
- Complete and verify one phase before starting the next.
- Do not mix unrelated cleanup with feature work.
- Preserve verified Fingerprint and Face baselines.
- Update this roadmap only when priorities materially change.

---

# Phase 1 — Stable Development Foundation

Priority: CRITICAL

Goal:

Create a clean, small, repeatable development baseline before adding more features.

## 1.1 Project Knowledge

Maintain:

- `AGENTS.md`
- `docs/codex/PROJECT_STATUS.md`
- `docs/codex/ARCHITECTURE.md`
- `docs/codex/BACKEND_CONTRACTS.md`
- `docs/codex/ROADMAP.md`

Future Codex tasks should read only:

1. `AGENTS.md`
2. one relevant `docs/codex/*.md`
3. exact files required for the task

Do not broadly reinvestigate the repository.

## 1.2 Git Baseline

Current stable checkpoint:

`22c4981 Implement face enrollment, recognition, calibration, and remote backup`

Keep working tree clean before starting major tasks.

Commit completed phases separately.

Push stable checkpoints before risky changes.

## 1.3 APK Size Reduction

Current APK is too large.

First optimization target:

`glintr100.onnx`

AuraFace/glintr100 was rejected and should not remain in production if runtime references are proven unnecessary.

Steps:

1. audit references to AuraFace/glintr100
2. prove current working Face pipeline uses YuNet + SFace only
3. remove unused AuraFace model/artifacts
4. build HF-X05-specific `arm64-v8a` APK
5. measure APK size
6. smoke-test Fingerprint and Face

Required Face models to preserve:

- `face_detection_yunet_2023mar.onnx`
- `face_recognition_sface_2021dec.onnx`

Do not blindly enable R8/minification during this first APK cleanup.

## 1.4 Runtime Modes

Formalize behavior for:

- DEVELOPMENT
- CALIBRATION
- PRODUCTION

Required rule:

Do not use `BuildConfig.DEBUG` as the only gate for functionality required in release builds.

---

# Phase 2 — Face Recognition Production Hardening

Priority: CRITICAL

Goal:

Prevent false identification and make Face Recognition safe for attendance.

## 2.1 Identity Decision

Current nearest-neighbor behavior is not sufficient.

Implement explicit results:

- MATCHED
- UNKNOWN
- AMBIGUOUS

Do not always return the nearest employee.

## 2.2 Threshold Calibration

Use existing Firestore calibration data.

Current configuration source:

`face_recognition_config/hf_x05_sface_v1`

Calibration must determine:

- minimum accepted match score
- ambiguity/top-vs-second margin
- candidate minimum score if still required
- duplicate enrollment threshold

Do not invent thresholds.

## 2.3 Unknown Face Flow

Unknown person must not be shown as a registered employee.

Target production behavior:

```text
Face not recognized

[ TRY AGAIN ]

[ USE FINGERPRINT ]

[ USE NFC CARD ]
````

Only show fallback methods that are available.

## 2.4 Ambiguous Face Flow

If multiple identities are too close:

```text
We could not confirm your identity.

[ TRY AGAIN ]

[ USE FINGERPRINT ]

[ USE NFC CARD ]
```

Do not automatically select one candidate.

## 2.5 Calibration Candidate UI

Candidate lists and score information may remain available in:

* DEVELOPMENT
* CALIBRATION

Production should hide technical similarity scores.

---

# Phase 3 — Unified Biometric Registration and Management

Priority: HIGH

Goal:

Make biometric enrollment easy for non-technical administrators.

User Management should become the main entry point.

Target flow:

```text
User Management
-> Employee
-> Attendance Methods
```

Example:

```text
Fingerprint
2 fingers registered
[ MANAGE ]

Face
Registered
[ MANAGE ]

NFC Card
Not Registered
[ REGISTER ]

Iris
Not Registered
[ REGISTER ]
```

---

# Phase 4 — Fingerprint Management Improvements

Priority: HIGH

Existing hardware implementation must remain protected.

## 4.1 Registered Finger Visibility

Admin should be able to see which fingers are registered.

Example:

```text
Right Thumb       Not Registered
Right Index       Registered
Right Middle      Not Registered
Left Index        Registered
```

## 4.2 Search

Fingerprint Management should support searching by:

* employee number
* employee name where available

## 4.3 Re-Registration

Target behavior:

```text
registered finger
-> delete
-> register again
```

Avoid silently creating multiple conflicting records.

## 4.4 Friendly Errors

Translate technical fingerprint failures into simple administrator/user messages.

---

# Phase 5 — Face Registration Improvements

Priority: HIGH

Current technical registration is working.

Improve UX without changing proven recognition geometry.

## 5.1 Simplified Capture

Keep:

* Straight
* Slight Left
* Slight Right

But make instructions clearer.

Use:

* large visual guidance
* progress indicator
* short wording
* proper icons

## 5.2 Registration Status

Admin should clearly see:

* Registered
* Not Registered
* Backup Pending
* Backed Up

Do not expose template internals.

## 5.3 Manage Face

Provide:

* Register
* Re-register
* Delete
* Restore/backup status

Remote delete behavior must be finalized before production restore logic is complete.

---

# Phase 6 — Backup and Disaster Recovery

Priority: CRITICAL

Goal:

APK uninstall/reinstall must not permanently lose backed-up registrations.

## 6.1 Local Runtime Storage

Runtime source:

* local Room
* Android Keystore encryption
* RAM indexes where needed

## 6.2 Portable Backup

Backend backup must contain portable enrollment data.

Do not backup installation-bound Android-Keystore ciphertext.

## 6.3 Recovery Flow

Target:

```text
Install application
-> Admin login
-> configure/validate device
-> sync users
-> download backed-up enrollments
-> validate
-> encrypt with current Keystore
-> store locally
-> rebuild indexes
-> Ready
```

## 6.4 Backup Coverage

Final recovery must support:

* Fingerprint
* Face
* NFC
* Iris if implemented
* relevant device settings

## 6.5 Restore UI

Admin should have a simple recovery screen.

Example:

```text
Restore Device Data

Employees        125
Face Records     68
Fingerprints     112
NFC Cards        73

[ RESTORE ]
```

Show simple progress and completion state.

---

# Phase 7 — Unified Attendance Service

Priority: HIGH

Goal:

All identification methods use one attendance transaction layer.

Architecture:

```text
Fingerprint ----\
Face ------------> employeeId -> AttendanceService -> Backend
NFC -------------/
Iris ------------/
```

## 7.1 Shared Actions

Support:

* CHECK IN
* CHECK OUT

## 7.2 Shared Rules

Reuse one implementation for:

* 5-minute minimum attendance gap
* duplicate protection
* backend submission
* offline/pending events
* success/failure UI

## 7.3 Face Attendance

Only connect Face attendance after MATCHED / UNKNOWN / AMBIGUOUS policy is safe.

Do not submit attendance for UNKNOWN or AMBIGUOUS.

## 7.4 Fingerprint Attendance

Preserve existing working attendance behavior.

## 7.5 NFC/Iris Attendance

Future modules only return `employeeId`.

Do not duplicate attendance logic.

---

# Phase 8 — Attendance Method Policies

Priority: HIGH

An employee may have multiple registered methods.

Need separate concepts:

1. method registered
2. device method enabled
3. employee allowed to use method

Effective method availability:

```text
device enabled
AND
employee allowed
AND
registration exists
```

## 8.1 Device Settings

Admin should be able to enable/disable:

* Fingerprint
* Face
* NFC
* Iris

Example:

```text
Attendance Methods

Fingerprint    ON
Face           ON
NFC            ON
Iris           OFF
```

Disabling a method must not delete its enrollment.

## 8.2 Employee Policies

Future employee configuration may allow:

```text
E420

Allowed Methods

✓ Fingerprint
✓ Face
✓ NFC
□ Iris
```

---

# Phase 9 — NFC Investigation and Implementation

Priority: MEDIUM/HIGH

First prove the hardware path.

## 9.1 Hardware Investigation

Determine whether HF-X05 exposes card reading through:

`android.nfc.*`

If yes:

prefer standard Android NFC.

If no:

investigate vendor interface only as necessary.

## 9.2 NFC Registration

Target flow:

```text
Employee
-> NFC Card
-> Register
-> Tap Card
-> validate uniqueness
-> save locally
-> backup remotely
```

## 9.3 NFC Identification

```text
Tap Card
-> local lookup
-> employeeId
```

Unknown card:

`UNKNOWN`

## 9.4 NFC Management

Support:

* view card status
* replace
* delete
* backup/restore

---

# Phase 10 — Performance and Device Lag

Priority: HIGH

HF-X05 sometimes lags.

Do not optimize blindly.

Measure first.

## 10.1 Measurements

Measure:

* idle RAM
* Face scan RAM
* Fingerprint RAM
* CPU
* camera FPS
* face detection latency
* feature extraction latency
* identification latency
* battery use
* device temperature

## 10.2 Model Lifecycle

Avoid repeated initialization of:

* YuNet
* SFace
* other heavy components

Reuse safe process-level resources where appropriate.

## 10.3 Camera Lifecycle

Camera must close when leaving Face scan.

Do not keep camera processing while:

* showing employee card
* showing attendance confirmation
* navigating elsewhere

## 10.4 Biometric Hardware Lifecycle

Only necessary biometric subsystem should be active.

Example:

FACE_ACTIVE:

* Camera0 ON
* YuNet/SFace active
* unused cameras OFF

FINGERPRINT_ACTIVE:

* fingerprint scanner active
* cameras OFF

IDLE:

* cameras OFF
* unnecessary biometric processing OFF

## 10.5 Database / Encryption

Avoid:

* Room queries every camera frame
* Keystore decryption every frame
* repeated RAM index rebuilds

---

# Phase 11 — Production UI / UX

Priority: HIGH

Primary users are non-technical.

## 11.1 Home Attendance UI

Use:

* large controls
* proper vector icons
* clear labels
* strong contrast

Conceptual home:

```text
Mark Attendance

[ FACE ]

[ FINGERPRINT ]

[ TAP CARD ]

[ IRIS ]
```

Do not use technical terminology.

## 11.2 User Messages

Examples:

```text
Face not recognized
Please try again.
```

```text
Fingerprint not recognized
Please try again or use another method.
```

```text
Card not registered
Please contact an administrator.
```

Do not show raw:

* HTTP codes
* stack traces
* model names
* backend error bodies

## 11.3 Admin UI

Admin screens should support:

* user search
* method registration state
* manage enrollment
* device settings
* backup/restore
* application updates
* diagnostics where appropriate

---

# Phase 12 — Iris Investigation

Priority: LATER

Do not assume iris is production-ready.

First investigate:

* camera/device accessibility
* IR illumination
* image quality
* focus/distance
* vendor SDK/API
* template generation
* matching algorithm
* licensing
* CPU/RAM requirements

Only implement if technically and commercially viable.

If viable:

```text
Iris capture
-> iris template
-> matcher
-> employeeId
-> shared AttendanceService
```

---

# Phase 13 — Application Update

Priority: HIGH before final deployment

Existing updater direction should remain.

Future release process:

```text
new version
-> increase versionCode
-> build with same signing key
-> upload APK
-> Device Settings
-> Application Update
-> install over existing app
```

Never intentionally uninstall during normal update.

Preserve:

* Room databases
* Keystore-backed data
* preferences
* registrations

Backup remains necessary for disaster recovery/uninstall scenarios.

---

# Phase 14 — Final Production Validation

Before production acceptance, test:

## Installation

* clean install
* update install
* reboot
* app restart

## Recovery

* backup
* controlled uninstall/reset test where safe
* reinstall
* admin login
* restore
* verify registrations

## Fingerprint

* enrollment
* identification
* unknown fingerprint
* multiple registered fingers
* delete/re-register

## Face

* enrollment
* MATCHED
* UNKNOWN
* AMBIGUOUS
* different lighting
* pose variation
* duplicate-face test

## NFC

If implemented:

* registration
* identification
* unknown card
* replacement
* restore

## Attendance

* Check In
* Check Out
* shared 5-minute rule
* Face/Fingerprint/NFC cross-method rule
* offline attendance
* later synchronization

## Performance

* long-running Face scans
* repeated attendance
* memory stability
* CPU
* temperature
* battery

---

# Immediate Next Tasks

Current recommended sequence:

1. Finish and commit Codex knowledge files.
2. Push documentation commit.
3. Audit/remove `glintr100.onnx` and unused AuraFace production artifacts.
4. Build `arm64-v8a` APK and measure new size.
5. Smoke-test Fingerprint and Face.
6. Harden Face MATCHED / UNKNOWN / AMBIGUOUS using calibration data.
7. Simplify registration/management UX.
8. Complete backup/recovery.
9. Connect unified attendance.
10. Investigate and implement NFC.
11. Profile/fix device lag.
12. Investigate Iris.
13. Final updater/release hardening.

Do not start multiple numbered tasks in one Codex session unless explicitly requested.

---

# Codex Task Discipline

For every new Codex task:

1. Read `AGENTS.md`.
2. Read only the relevant `docs/codex/*.md`.
3. Search exact symbols first.
4. Avoid repository-wide investigation.
5. Make the smallest change.
6. Build only what is necessary.
7. Report concise PASS/FAIL results.
8. Stop.

Example APK task:

```text
Read AGENTS.md and docs/codex/PROJECT_STATUS.md.

Task:
Audit and remove rejected AuraFace/glintr100 production artifacts if proven
unused, then build arm64-v8a and report before/after APK size.

Do not change biometric behavior.
Stop after verification.
```

Example Face task:

```text
Read AGENTS.md and docs/codex/ARCHITECTURE.md.

Task:
Implement UNKNOWN and AMBIGUOUS Face Recognition behavior using the already
configured calibration system.

Do not change Camera0, YuNet, SFace alignment, registration, fingerprint, or
attendance.

Build and stop.
```

Example backend task:

```text
Read AGENTS.md and docs/codex/BACKEND_CONTRACTS.md.

Task:
<one backend contract change>

Reuse existing authentication and API architecture.
Do not investigate unrelated endpoints.
Build and stop.
```
