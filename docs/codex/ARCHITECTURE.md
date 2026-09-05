# Multi-Biometric Architecture

## Purpose

This application is a multi-biometric attendance terminal for the HF-X05 device.

Supported/planned identification methods:

- Fingerprint
- Face
- NFC Card
- Iris — future, not yet proven

Each biometric method has its own enrollment and identification implementation.

All identification methods must converge on a shared attendance pipeline.

---

# Core Architecture

```text
Fingerprint ----\
Face ------------> employeeId -> AttendanceService -> Backend
NFC -------------/
Iris ------------/
````

Biometric modules must NOT contain duplicated attendance business logic.

Their responsibility is:

```text
Capture
-> identify employee
-> return employeeId
```

AttendanceService is responsible for:

* Check In
* Check Out
* attendance-gap validation
* offline/pending handling
* backend submission
* success/failure result handling

---

# Employee Biometric Model

One employee may have multiple registered attendance methods.

Example:

```text
Employee: E420

Fingerprint
  Right Index     Registered
  Left Index      Registered

Face
  Registered

NFC
  Registered

Iris
  Not Registered
```

Registration status and permission are separate concepts.

A method should be usable only when:

```text
device method enabled
AND
employee allowed to use method
AND
employee has valid registration
```

Future employee policy may conceptually include:

```text
allowedMethods:
- FINGERPRINT
- FACE
- NFC
- IRIS
```

Do not treat:

`fingerprintRegistered`

as a generic biometric-registration flag.

Each biometric type must maintain its own enrollment state.

---

# Device-Level Biometric Settings

Admin should eventually be able to enable or disable attendance methods for the device.

Example:

```text
Fingerprint    ON
Face           ON
NFC            ON
Iris           OFF
```

Disabling one biometric method must not affect registrations for other methods.

Example:

Face OFF

must not:

* delete Face templates
* modify Fingerprint data
* modify NFC data

It only disables that method for attendance until re-enabled.

---

# Fingerprint Architecture

The HF-X05 low-level fingerprint implementation is already working and is considered protected.

Conceptual pipeline:

```text
HF-X05 fingerprint sensor
-> hardware capture
-> fingerprint feature/template
-> matcher
-> employeeId
```

The low-level implementation includes device-specific behavior such as:

* GPIO
* SPI
* sensor power/reset
* initialization
* capture
* cleanup

Do not replace or rewrite this layer unless the task specifically requires it.

Fingerprint enrollment and identification should remain separate from Face logic.

---

# Fingerprint Enrollment

Target enrollment flow:

```text
User Management
-> Employee
-> Fingerprint
-> Manage
-> Add Finger
-> select finger
-> capture required samples
-> verify enrollment
-> save locally
-> backup remotely
```

An employee may register multiple fingers.

Future UI should clearly display enrolled fingers.

Example:

```text
Right Thumb       Not Registered
Right Index       Registered
Right Middle      Not Registered
Left Index        Registered
```

Deleting one finger must not delete other registered fingers.

---

# Face Architecture

Current verified pipeline:

```text
Camera0
-> camera frame
-> frame normalization
-> YuNet face detection
-> landmarks
-> corrected SFace geometry/alignment
-> SFace feature extraction
-> RAM template comparison
-> identity decision
-> employeeId
```

Verified target configuration:

Camera:

`0`

Physical orientation:

`90 degrees`

Face detector:

`face_detection_yunet_2023mar.onnx`

Recognizer:

`face_recognition_sface_2021dec.onnx`

Template format:

`sface-f32le-v1`

Feature:

```text
128 Float32
= 512 bytes
```

---

# Face Detection

YuNet is responsible for:

* detecting faces
* face bounding box
* five facial landmarks

Current YuNet pipeline and preprocessing are verified.

Do not modify:

* Camera0
* 90-degree orientation
* YuNet preprocessing
* coordinate mapping

unless a regression is clearly proven.

---

# SFace Alignment

SFace feature extraction requires correctly mapped YuNet geometry.

The previous geometry issue was fixed.

The current corrected path must remain:

```text
YuNet detection
-> detector-image geometry
-> SFaceAlignmentMapper
-> alignCrop
-> feature
```

Do not reintroduce legacy/presentation-coordinate mapping into production recognition.

---

# Face Registration

Current registration requires exactly three samples:

```text
1. Straight
2. Slight Left
3. Slight Right
```

Target flow:

```text
Employee
-> Face
-> Register
-> Straight capture
-> review
-> Slight Left capture
-> review
-> Slight Right capture
-> review all 3
-> Save Face
```

Final save order:

```text
validate 3 samples
-> persist locally
-> update RAM index
-> show success
-> asynchronous backend backup
```

Remote backup must never block local registration.

---

# Face Local Storage

Face biometric features are stored locally using:

```text
portable SFace feature
-> Android Keystore encryption
-> Room database
```

Raw camera images are not required for biometric persistence.

Do not persist:

* live camera frames
* review thumbnails
* JPEG face images

as biometric templates.

---

# Face RAM Index

Recognition must not decrypt and load all templates for every camera frame.

Process-wide face index is used.

Conceptual lifecycle:

```text
Application startup
-> decrypt compatible active templates once
-> RAM index READY
```

Recognition:

```text
Face scan
-> query SFace feature
-> compare against RAM index
```

Registration:

```text
save local templates
-> addOrReplaceEmployee()
```

Deletion:

```text
remove local templates
-> removeEmployee()
```

Restore:

```text
restore encrypted local templates
-> addOrReplaceEmployee() / refresh()
```

Room remains the persistent source of truth.

RAM is runtime cache only.

---

# Face Identification Decision

A nearest employee is NOT automatically a valid identity.

Recognition must eventually return one of:

```text
MATCHED
UNKNOWN
AMBIGUOUS
```

Conceptual policy:

MATCHED:

```text
topScore >= calibrated match threshold

AND

topScore - secondScore >= calibrated ambiguity requirement
```

UNKNOWN:

```text
topScore too low
```

AMBIGUOUS:

```text
top score acceptable
BUT
top and second identities too close
```

Exact values must come from calibration data.

Do NOT invent threshold values.

---

# Known Duplicate Test

E338 and E247 may intentionally represent the same physical face.

Their scores are useful for:

* duplicate enrollment testing
* ambiguity testing

Do not classify that pair as a normal different-person impostor pair.

---

# Runtime Modes

Recognition behavior must not depend only on:

`BuildConfig.DEBUG`

Three logical modes are required.

## DEVELOPMENT

Purpose:

* diagnostics
* developer testing
* detailed logs
* score inspection
* temporary debug controls

## CALIBRATION

Purpose:

* run real release recognition engine
* collect score distributions
* inspect candidate behavior
* manually confirm identity

Allowed:

```text
scan
-> rank
-> show candidate
-> manual confirmation
```

Not allowed:

* pretending uncalibrated result is final production authorization
* invented thresholds

## PRODUCTION

Purpose:

normal employee attendance.

Required:

* calibrated thresholds
* MATCHED / UNKNOWN / AMBIGUOUS
* simple UI
* no technical score display
* no diagnostic candidate behavior

---

# Face Error States

Face engine should eventually produce clear standardized outcomes.

Examples:

```text
FACE_NO_FACE
FACE_MULTIPLE_FACES
FACE_TOO_FAR
FACE_POOR_QUALITY
FACE_UNKNOWN
FACE_AMBIGUOUS
FACE_CAMERA_UNAVAILABLE
FACE_ENGINE_ERROR
```

Technical error code may be logged.

User-facing message must remain simple.

Examples:

```text
Face not detected
Please look at the camera.
```

```text
We couldn't identify you.
Please try again or use another attendance method.
```

```text
We couldn't confirm your identity.
Please use Fingerprint or NFC.
```

---

# NFC Architecture

NFC is planned but not yet implemented.

First task is hardware/API investigation.

Determine whether HF-X05 exposes its card reader through:

```text
android.nfc.*
```

If standard Android NFC works, prefer that implementation.

Conceptual pipeline:

```text
NFC reader
-> card credential / UID
-> local mapping
-> employeeId
```

NFC enrollment:

```text
Employee
-> NFC
-> Register Card
-> Tap Card
-> verify not already assigned
-> save mapping locally
-> backup remotely
```

NFC identification:

```text
Tap Card
-> lookup local registration
-> employeeId
```

Unknown card:

```text
UNKNOWN
```

Do not assign nearest/similar card results.

---

# Iris Architecture

Iris is future research.

Do not implement production iris flow until hardware support is proven.

Required investigation:

* iris camera accessible?
* Android Camera2 ID?
* IR illumination?
* frame resolution?
* usable focus/distance?
* vendor SDK available?
* template format available?
* matcher available?
* commercial licensing acceptable?
* performance acceptable on HF-X05?

If viable, future architecture should follow the same pattern:

```text
Iris capture
-> iris feature/template
-> matcher
-> employeeId
-> AttendanceService
```

Iris must remain isolated from Face and Fingerprint internals.

---

# Unified Registration Architecture

User Management should become the central biometric-management screen.

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

Each biometric method should independently support:

* Register
* View status
* Manage
* Delete
* Re-register
* Backup status

Avoid separate confusing dashboard registration pages.

---

# Local-First Architecture

The terminal must continue functioning without backend connectivity once data is available locally.

Registration order:

```text
capture
-> validate
-> save locally
-> update runtime index
-> show success
-> backup asynchronously
```

Do NOT use:

```text
backend save
-> wait
-> local save
```

for biometric registration.

Network failure must not make local biometric enrollment unusable.

---

# Backup Architecture

Portable biometric templates are required for disaster recovery.

Do NOT backup Android-Keystore ciphertext.

Reason:

Android Keystore encryption keys are tied to the installation/device environment.

Correct architecture:

```text
Biometric feature/template
        |
        +-> Android Keystore encryption -> local Room
        |
        +-> portable backend backup
```

Backend storage and security requirements must be handled independently of Android local encryption.

---

# Restore Architecture

After reinstall/reset/device recovery:

```text
Install app
-> Admin login
-> configure device
-> synchronize users
-> download biometric backup
-> validate type/model/version/format
-> encrypt using current Android Keystore
-> store in Room
-> rebuild runtime indexes
-> Ready
```

Restore must be idempotent.

Running it multiple times must not create duplicate enrollment sets.

---

# Unified Attendance Architecture

Final attendance pipeline:

```text
Fingerprint identification --\
Face identification -----------\
NFC identification -------------> employeeId
Iris identification -----------/
                                  |
                                  v
                           AttendanceService
                                  |
                           Check In / Check Out
                                  |
                                  v
                              Backend
```

AttendanceService should contain shared logic such as:

* selected employee
* Check In
* Check Out
* minimum attendance gap
* duplicate protection
* offline/pending events
* backend submission
* user-facing success/error result

Do not duplicate these rules inside biometric modules.

---

# Multi-Method Fallback

If one identification method fails, the employee should be able to use another allowed method.

Example:

```text
Face could not identify you.

[ TRY FACE AGAIN ]

Other available methods:

[ USE FINGERPRINT ]
[ TAP NFC CARD ]
```

Only show methods that are:

```text
enabled on device
AND
allowed for employee where applicable
AND
registered/available
```

Production fallback must not expose technical recognition scores.

---

# Resource Management

HF-X05 resource usage must be controlled.

The device may lag when expensive subsystems remain active.

Conceptual hardware states:

## IDLE

```text
Camera closed
Iris camera closed
Face processing stopped
Fingerprint polling minimized where technically possible
NFC listener low-cost where supported
```

## FACE_ACTIVE

```text
Camera0 active
YuNet active
SFace active
unused cameras closed
unnecessary fingerprint scanning disabled
iris inactive
```

## FINGERPRINT_ACTIVE

```text
Fingerprint scanner active
camera closed
iris inactive
```

## NFC_ACTIVE

```text
NFC listener active
camera closed unless another UI requires it
```

## IRIS_ACTIVE

```text
Iris hardware active
other expensive biometric pipelines inactive
```

Hardware must be released when leaving a biometric screen.

---

# Performance Requirements

Measure before optimizing.

Important measurements:

* idle RAM
* active Face RAM
* active Fingerprint RAM
* CPU
* camera FPS
* face detection latency
* feature extraction latency
* identification latency
* battery use
* temperature

Avoid:

* repeated ONNX model initialization
* Room access every camera frame
* Keystore decrypt every frame
* concurrent unnecessary camera use
* repeated face-index rebuilds

---

# APK Architecture

HF-X05 production APK should target:

`arm64-v8a`

Development builds may retain broader ABI support when required.

Required production face assets:

* YuNet
* SFace

Rejected production model:

`glintr100.onnx`

AuraFace/glintr100 should not remain in production after its runtime references are proven unnecessary.

Do not replace working models merely to reduce size.

---

# UI Architecture

The target users are non-technical.

UI should prioritize recognition over configuration details.

Employee attendance home should use:

* large controls
* clear icons
* short labels
* strong contrast

Example:

```text
Mark Attendance

[ FACE ]

[ FINGERPRINT ]

[ TAP CARD ]

[ IRIS ]
```

Use proper vector/Material icons rather than technical text or emoji in production.

Admin UI may expose more detail, but should still remain simple.

---

# Architecture Rules

1. Keep biometric engines independent.
2. Share AttendanceService.
3. Keep local biometric storage authoritative for runtime.
4. Backup portable templates remotely.
5. Do not block local registration on backend.
6. Do not use nearest-neighbor result as automatic identity.
7. Do not depend on BuildConfig.DEBUG for required release behavior.
8. Keep hardware lifecycle explicit.
9. Do not run heavy biometric engines unnecessarily.
10. Keep UI simple for non-technical users.
11. One employee may have multiple biometric registrations.
12. Registration status and attendance permission are separate concepts.
13. Preserve proven HF-X05 hardware implementations.