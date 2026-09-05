# Current Project Status

Last stable baseline:

`22c4981 Implement face enrollment, recognition, calibration, and remote backup`

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

---

# Working Features

## Fingerprint

The HF-X05 fingerprint implementation is working.

Verified:

- HF-X05 fingerprint hardware access works.
- Sensor capture works.
- Fingerprint enrollment works.
- Fingerprint identification works.
- User Management can launch fingerprint registration.
- Existing fingerprint attendance integration exists.
- Existing 5-minute attendance-gap logic exists.
- Hardware-unavailable handling exists for unsupported devices/emulators.

The low-level fingerprint hardware implementation is considered a protected baseline.

Do not unnecessarily modify:

- GPIO
- SPI
- sensor reset
- initialization
- capture
- cleanup
- proven HF-X05 hardware configuration

---

# Face Recognition

Face Recognition is working on the real HF-X05.

Verified hardware/configuration:

- Camera ID: `0`
- Camera orientation: `90°`
- Camera0 is the RGB camera used for recognition.
- YuNet face detection works.
- SFace feature extraction works.
- Corrected SFace geometry/alignment works.
- Face ranking works.
- Release build can navigate from Face Scan to the employee candidate flow.

Current face detector:

`face_detection_yunet_2023mar.onnx`

Current face recognizer:

`face_recognition_sface_2021dec.onnx`

Current face template format:

`sface-f32le-v1`

Feature structure:

- 128 Float32 values
- 512 bytes portable feature data

---

# Face Registration

Face Registration is working.

Current enrollment flow:

1. Select employee.
2. Capture Straight face.
3. Capture Slight Left face.
4. Capture Slight Right face.
5. Review three photos.
6. Save Face.
7. Store encrypted templates locally.
8. Update RAM face index.
9. Show registration success.
10. Attempt asynchronous backend backup.

Exactly 3 face templates are stored per active registration.

The three samples represent:

1. Straight
2. Slight Left
3. Slight Right

Raw face photos are not required for biometric persistence.

---

# Face Local Storage

Local face templates use:

- Room database
- Android Keystore encryption
- encrypted template persistence
- process-wide RAM face template index

The local Room repository is the runtime source for recognition.

The RAM index is used to avoid repeatedly decrypting/loading templates during recognition.

Verified RAM index behaviors include:

- startup warm-up
- reuse between scans
- employee replacement
- employee removal
- restore refresh

---

# Face Backend Backup

Face backend backup integration is implemented.

Existing endpoint reused:

`POST /api/fingerprint/enrollments`

Face requests use:

`biometricType = FACE`

Face backup contains:

- enrollmentId
- deviceId
- backend userId
- employeeId
- biometricType
- engineId
- modelId
- modelVersion
- templateFormat
- exactly 3 portable templates

Expected model:

`face_recognition_sface`

Expected model version:

`2021dec`

Expected template format:

`sface-f32le-v1`

Backend backup is asynchronous.

Local registration must succeed even if:

- network is unavailable
- backend is unavailable
- timeout occurs
- remote backup fails

Remote backup failure must not delete or invalidate local face registration.

---

# Face Restore

Face restore integration is implemented.

Endpoint:

`GET /api/fingerprint/enrollments`

with:

`deviceId=<deviceId>`

`userId=<backendUserId>`

`biometricType=FACE`

Expected response envelope:

```json
{
  "enrollment": {
    "enrollmentId": "...",
    "userId": "...",
    "employeeId": "...",
    "biometricType": "FACE",
    "engineId": "...",
    "modelId": "face_recognition_sface",
    "modelVersion": "2021dec",
    "templateFormat": "sface-f32le-v1",
    "templates": [
      {
        "templateSlot": 1,
        "templateDataBase64": "..."
      },
      {
        "templateSlot": 2,
        "templateDataBase64": "..."
      },
      {
        "templateSlot": 3,
        "templateDataBase64": "..."
      }
    ]
  }
}
````

or:

```json
{
  "enrollment": null
}
```

Before restore, Android validates:

* biometricType is FACE
* correct model
* correct model version
* correct template format
* exactly 3 templates
* slots exactly 1, 2, 3
* Base64 decoding succeeds
* SFace payload is valid
* feature contains 128 floats

Remote portable templates are then encrypted using the current Android Keystore and stored in Room.

After restore, the RAM face index is updated.

---

# Face Calibration

Firestore calibration infrastructure is implemented.

Firestore database:

`southernlanka`

Collections:

* `face_calibration_events`
* `face_recognition_config/hf_x05_sface_v1`

Current stage:

`CALIBRATION`

Final production thresholds have NOT yet been approved.

Do not invent production thresholds.

Calibration telemetry must remain non-blocking.

Firestore failure must never prevent:

* recognition
* registration
* navigation
* attendance UI

---

# Face Recognition Modes

Three conceptual runtime modes are required.

## DEVELOPMENT

Used for:

* diagnostics
* technical logs
* testing
* score inspection

## CALIBRATION

Used during current real-device calibration.

Behavior:

* real face engine runs
* SFace ranking runs
* candidate/manual confirmation flow is allowed
* calibration events can be recorded
* no invented production threshold is applied

## PRODUCTION

Future final behavior.

Must use calibrated rules.

Production recognition must not simply accept the nearest employee.

Required outcomes:

* MATCHED
* UNKNOWN
* AMBIGUOUS

---

# Known Face Recognition Issue

The matcher always has a nearest template when templates exist.

A nearest employee is NOT automatically a valid biometric match.

Example of invalid behavior:

Unknown person
→ low similarity scores
→ highest low score selected
→ employee incorrectly displayed

This must be fixed before final production use.

Production decision must eventually evaluate:

* absolute top score
* difference/margin between top and second candidate
* calibrated recognition policy

Do not hardcode arbitrary values.

---

# Known Duplicate Test Data

Employees:

`E338`

and:

`E247`

may represent the same physical face intentionally.

They were used to test ambiguity / duplicate enrollment behavior.

Do NOT treat E338 vs E247 as a normal different-person impostor pair during threshold analysis.

---

# Registration / Management Improvements Needed

Current technical registration works, but the administration flow needs simplification.

Target User Management experience:

Employee
→ Attendance Methods

Each employee may have multiple methods:

* Fingerprint
* Face
* NFC
* Iris

Each method should show:

* registration status
* registered details
* Manage
* Register
* Re-register
* Delete

Fingerprint management should eventually show which fingers are enrolled.

Face management should show whether the required face templates exist.

---

# Multi-Biometric Product Direction

This application is becoming a full multi-biometric attendance terminal.

Planned methods:

* Fingerprint
* Face
* NFC Card
* Iris

One employee may register multiple methods.

Separate concepts must exist:

1. Method is registered.
2. Method is enabled on this device.
3. Employee is allowed to use this method.

Attendance should be possible only when the method is available under the configured policy.

---

# Unified Attendance

Final architecture should be:

```text
Fingerprint ----\
Face ------------> employeeId -> AttendanceService -> Backend
NFC -------------/
Iris ------------/
```

Each biometric module identifies an employee.

Attendance business logic must remain shared.

Do NOT create completely separate attendance systems for:

* Face
* Fingerprint
* NFC
* Iris

Face Check In / Check Out backend wiring is not yet finalized in the current plan.

---

# Backup / Disaster Recovery Goal

The final application must support:

1. APK uninstall or device reset.
2. Reinstall application.
3. Admin login.
4. Device/user synchronization.
5. Download backed-up biometric registrations.
6. Validate templates.
7. Encrypt templates locally using the new Android Keystore.
8. Restore Room databases.
9. Rebuild runtime indexes.
10. Resume attendance operation.

Android-Keystore ciphertext must NOT be treated as portable backup data.

Portable biometric templates must be backed up instead.

---

# NFC Status

NFC attendance is planned but not implemented.

First investigation must determine whether the HF-X05 card reader is exposed through standard Android NFC APIs.

If standard Android NFC works:

use Android NFC APIs.

Do not create vendor-specific hardware code unless necessary.

Planned NFC capabilities:

* card enrollment
* card replacement
* card deletion
* card identification
* local storage
* backend backup/restore
* attendance identification

---

# Iris Status

HF-X05 appears to include iris-related hardware.

Iris recognition is NOT yet proven.

Before implementation, investigate:

* accessible iris camera
* Android camera/API exposure
* infrared illumination
* usable frame quality
* vendor SDK
* template generation
* matcher availability
* licensing
* runtime performance

Do not assume iris can be implemented simply because the hardware exists.

---

# APK Size

Current APK size is too large.

Known major problem:

`glintr100.onnx`

This is the rejected AuraFace model and is approximately 249 MB.

AuraFace is not the current production face engine.

Current required production face models are:

* YuNet
* SFace

Immediate APK optimization plan:

1. prove `glintr100.onnx` has no required runtime references
2. remove rejected AuraFace model/artifacts where safe
3. build HF-X05 APK for `arm64-v8a` only
4. measure APK again
5. only then consider additional shrinking

Do NOT remove or modify YuNet/SFace just to reduce APK size.

Do NOT blindly enable R8 before establishing the clean baseline size.

---

# Performance

HF-X05 can occasionally lag.

Performance optimization is required.

Future measurements:

* idle RAM
* face scan RAM
* CPU
* FPS
* recognition latency
* battery usage
* device temperature

Resource policy:

Only the currently required biometric hardware should consume significant resources.

Examples:

FACE ACTIVE:

* Camera0 active
* face engine active
* unused cameras closed

FINGERPRINT ACTIVE:

* fingerprint capture active
* camera closed

IDLE:

* cameras closed
* unnecessary biometric processing stopped

Avoid:

* decrypting Room templates on every frame
* repeated model loading
* keeping cameras active unnecessarily
* running multiple heavy biometric pipelines simultaneously

---

# UI / UX Direction

Primary users are non-technical employees and administrators.

UI must use:

* large buttons
* large employee numbers
* clear icons
* short wording
* strong contrast
* consistent colors
* obvious Back/Cancel actions
* friendly error messages

Do not expose:

* stack traces
* model names
* similarity internals
* raw backend errors
* technical hardware codes

to normal employees.

---

# Immediate Development Order

Do not start multiple phases simultaneously.

Current order:

1. Add Codex project knowledge documentation.
2. Reduce APK size.
3. Establish clean arm64-v8a production baseline.
4. Harden Face MATCHED / UNKNOWN / AMBIGUOUS behavior.
5. Improve biometric registration/management UX.
6. Complete backup/recovery architecture.
7. Connect all biometric methods to shared AttendanceService.
8. Add NFC.
9. Profile and reduce device lag.
10. Investigate Iris.
11. Complete updater/release production work.
12. Final calibration and acceptance testing.

Each Codex task should address only one narrow outcome.