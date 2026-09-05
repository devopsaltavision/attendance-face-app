# Backend Contracts

## Purpose

This document contains only the backend contracts that are already established or currently planned for the HF-X05 attendance app.

Codex should use these contracts directly instead of repeatedly rediscovering API behavior.

Do not redesign working backend contracts unless a task explicitly requires it.

---

# Device Authentication

HF-X05 device APIs reuse the existing fingerprint/device authentication mechanism.

Current device authentication supports the existing API key/device-authentication flow.

Do not create a separate authentication stack specifically for Face, NFC, or Iris.

All biometric methods should reuse the same trusted device identity.

---

# Device Identity

Current device ID:

`HF-X05-DEV-001`

The application obtains the configured device ID through the existing device configuration layer.

Do not hardcode a second device identifier specifically for Face/NFC/Iris.

---

# User Identity Fields

Backend and Android distinguish:

`userId`

and:

`employeeId`

Do NOT assume these are always the same value.

Use:

- `userId` = backend user/document identity
- `employeeId` = employee/EPF attendance identity

The Android synced employee model should retain both.

Do not derive one from the other using string manipulation.

---

# User Synchronization

The existing user synchronization API remains the common employee source.

Relevant biometric fields include:

- `fingerprintEnrolled`
- `fingerprintEnrollmentId`
- `faceEnrolled`
- `faceEnrollmentId`

Future fields may be added for:

- NFC registration
- Iris registration
- allowed attendance methods

Missing newer fields from an older backend must be handled safely.

Example:

Missing:

`faceEnrolled`

should default safely rather than crashing parsing.

---

# Fingerprint Enrollment

Existing fingerprint enrollment API:

`POST /api/fingerprint/enrollments`

Legacy fingerprint requests may omit:

`biometricType`

Omitted `biometricType` must remain equivalent to:

`FINGERPRINT`

This preserves existing deployed fingerprint behavior.

Existing fingerprint enrollment rules must remain intact.

Fingerprint-specific fields may include:

- enrollmentId
- deviceId
- userId
- employeeId
- fingerPosition
- matcher information
- template metadata
- fingerprint template slots

Do not apply Face rules to Fingerprint.

---

# Face Enrollment

Face enrollment reuses the existing enrollment endpoint:

`POST /api/fingerprint/enrollments`

Face requests explicitly use:

`biometricType = FACE`

Expected request shape:

```json
{
  "enrollmentId": "<stable UUID>",
  "deviceId": "HF-X05-DEV-001",
  "userId": "<backend-user-id>",
  "employeeId": "<employee-id>",
  "biometricType": "FACE",
  "engineId": "<Android engine id>",
  "modelId": "face_recognition_sface",
  "modelVersion": "2021dec",
  "templateFormat": "sface-f32le-v1",
  "enrolledAtDevice": "<ISO timestamp>",
  "templates": [
    {
      "templateSlot": 1,
      "templateDataBase64": "<portable SFace feature>"
    },
    {
      "templateSlot": 2,
      "templateDataBase64": "<portable SFace feature>"
    },
    {
      "templateSlot": 3,
      "templateDataBase64": "<portable SFace feature>"
    }
  ]
}
````

Face rules:

* exactly 3 templates
* slots exactly 1, 2, 3
* no fingerPosition
* no 5-slot fingerprint validation
* no raw image upload
* no review-thumbnail upload
* no Android-Keystore ciphertext upload

Expected model:

`face_recognition_sface`

Expected model version:

`2021dec`

Expected format:

`sface-f32le-v1`

---

# Face Template Representation

Each SFace feature is:

```text
128 Float32 values
= 512 bytes
```

Android uploads the portable encoded feature bytes.

Typical transport representation:

Base64

The backend backup must contain portable biometric data.

Do not treat local Android-Keystore ciphertext as backup data because it may not be decryptable after reinstall/device replacement.

---

# Face Enrollment Success

Successful Face responses may return:

`RECORDED`

or:

`ALREADY_RECORDED`

Both must be treated as successful synchronization outcomes.

Conceptual response:

```json
{
  "success": true,
  "enrollmentId": "<same enrollment id>",
  "userId": "<backend-user-id>",
  "status": "RECORDED",
  "biometricType": "FACE",
  "templateCount": 3,
  "serverTimestamp": "<timestamp>"
}
```

Retrying the same completed registration must reuse the same:

`enrollmentId`

Do not generate a new UUID for each network retry.

---

# Face Re-Registration

A new Face registration should represent a replacement registration for the relevant employee/device scope.

It must not indefinitely append:

```text
3
-> 6
-> 9
```

templates.

The backend should retain one current active Face enrollment set for the intended scope.

A new completed re-registration may use a new enrollment ID.

Retries of that same completed registration must reuse that ID.

---

# Face User State

Face enrollment must update Face-specific registration state.

Expected backend concept:

```text
face_enrolled = true
face_enrollment_id = <id>
```

Face registration must NOT automatically set:

```text
fingerprint_enrolled = true
```

Fingerprint and Face enrollment states are independent.

---

# Face Restore

Restore endpoint:

```text
GET /api/fingerprint/enrollments
?deviceId=<deviceId>
&userId=<userId>
&biometricType=FACE
```

Expected successful envelope:

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
```

If no remote Face enrollment exists:

```json
{
  "enrollment": null
}
```

Android must handle this without crashing.

---

# Face Restore Validation

Before writing restored data locally, Android validates:

* biometricType = FACE
* correct employee/user
* correct model
* correct model version
* correct template format
* exactly 3 templates
* slots 1, 2, 3
* valid Base64
* valid SFace payload
* expected feature length

Invalid remote data must not partially overwrite a valid local enrollment.

---

# Face Restore Storage

Remote backup contains portable templates.

Restore process:

```text
Backend portable template
-> decode
-> validate
-> Android Keystore encryption
-> Room
-> RAM face index
```

Do not persist the backend Base64 representation as a second permanent plaintext biometric repository.

---

# Face Backup Failure Behavior

Face backup is side functionality.

The local registration sequence is authoritative for immediate device operation.

Correct behavior:

```text
save locally
-> update RAM index
-> show success
-> attempt backend backup asynchronously
```

Backend errors must not:

* roll back local registration
* delete templates
* hide registration success
* prevent use of local Face recognition

Failures should be retained for later retry.

Do not create tight retry loops.

---

# Fingerprint and Face Separation

Although both currently use the same enrollment endpoint path, the biometric logic remains different.

Conceptually:

```text
POST /api/fingerprint/enrollments
```

may handle:

```text
biometricType = FINGERPRINT
```

and:

```text
biometricType = FACE
```

The endpoint path does NOT mean Face should behave like Fingerprint internally.

Fingerprint rules remain fingerprint-specific.

Face rules remain face-specific.

---

# Remote Face Delete

Remote Face deletion support is currently not considered complete.

Do not invent a new backend DELETE contract in Android unless the backend task explicitly defines one.

Until remote delete exists, be careful that:

local delete

and:

remote faceEnrolled=true

can conflict during future restore.

This must be resolved before final production backup/recovery behavior is considered complete.

---

# Attendance API

Existing Fingerprint attendance backend integration already exists.

The final architecture should reuse the same attendance transaction layer for all biometric methods.

Conceptual pipeline:

```text
Fingerprint -> employeeId ---\
Face -> employeeId -----------\
NFC -> employeeId -------------> AttendanceService -> existing attendance API
Iris -> employeeId -----------/
```

Do not build:

* Face Attendance API
* NFC Attendance API
* Iris Attendance API

as separate transaction systems unless the backend contract explicitly requires it.

---

# Attendance Actions

Shared attendance actions:

* CHECK IN
* CHECK OUT

The exact existing request DTO/field names should be reused from the current fingerprint implementation.

Codex should inspect the existing attendance service/client only when implementing attendance wiring.

Do not redesign existing working fingerprint attendance behavior.

---

# Attendance Identity

Attendance submission must use the employee selected/identified by the current biometric flow.

For Face:

```text
Face ranking
-> identity decision / employee confirmation
-> selected employeeId
-> AttendanceService
```

If an alternate employee was selected in a calibration/manual-selection flow, the selected employee ID must be used.

Do not silently use the original top candidate after the user selects another employee.

---

# Attendance Gap

The existing minimum attendance-gap rule is shared across methods.

Current requirement:

approximately 5-minute minimum gap

This is an attendance-level rule, NOT a biometric-level rule.

Example:

```text
Fingerprint Check In
-> 2 minutes
-> Face Check Out
```

must still be evaluated by the same shared attendance-gap policy.

Do not create separate Face/Fingerprint/NFC gap timers.

---

# Offline Attendance

If the existing fingerprint attendance system supports:

* local pending events
* offline queue
* later synchronization

all future biometric methods should reuse the same mechanism.

Do not create Face-specific or NFC-specific attendance queues.

---

# Future NFC Backend Contract

NFC backend registration contract is not finalized.

Future NFC enrollment will likely require:

* userId
* employeeId
* deviceId
* biometric/method type = NFC
* card credential/identifier
* active state
* registration timestamp

Do not invent the exact API contract until the HF-X05 NFC hardware path is proven.

NFC backup data should be portable and restorable.

---

# Future Iris Backend Contract

Iris backend contract is not defined.

Do not design or implement one until:

* iris hardware access is proven
* template representation is known
* matching technology is selected
* licensing is confirmed

---

# Future Unified Biometric Model

Longer-term backend design may represent employee attendance methods separately.

Conceptual model:

```json
{
  "employeeId": "E420",
  "allowedMethods": [
    "FINGERPRINT",
    "FACE",
    "NFC"
  ],
  "registrationState": {
    "fingerprint": true,
    "face": true,
    "nfc": true,
    "iris": false
  }
}
```

This is architectural direction only.

Do not migrate the backend to this representation unless a dedicated backend task requires it.

---

# Device-Level Method Settings

Future backend/device configuration should support device method availability.

Example:

```json
{
  "fingerprintEnabled": true,
  "faceEnabled": true,
  "nfcEnabled": true,
  "irisEnabled": false
}
```

Effective attendance method availability should eventually consider:

```text
device method enabled
AND
employee method allowed
AND
valid enrollment exists
```

The exact backend contract is not finalized yet.

---

# Admin Backup / Recovery Goal

Final system must support:

```text
Fresh install
-> Admin login
-> validate/configure device
-> sync employees
-> fetch biometric backup
-> validate enrollment data
-> create new local encryption
-> restore Room
-> rebuild runtime indexes
-> ready for attendance
```

Backup/restore must ultimately cover:

* Fingerprint
* Face
* NFC
* Iris if implemented

No biometric data should depend solely on an Android installation-specific key if recovery after uninstall is required.

---

# Security Rules

Never send or store unnecessary biometric data.

Face:

* portable features only
* no raw face photos for backup

Do not log:

* Base64 templates
* raw feature bytes
* encryption ciphertext
* API keys
* signing secrets

API authentication credentials must come from existing secure/configured application mechanisms.

Do not hardcode new credentials.

---

# Backend Error Handling

Backend errors should be mapped to safe application outcomes.

Normal employees should not see:

* stack traces
* raw HTTP responses
* database errors
* technical API codes unless intentionally translated

Internal logs may retain safe diagnostic categories.

Examples:

```text
AUTH_FAILED
DEVICE_DISABLED
USER_NOT_FOUND
NETWORK_UNAVAILABLE
SERVER_ERROR
INVALID_BACKUP
```

User-facing message should remain simple.

---

# Contract Change Rules

When working on backend-related tasks:

1. Read this document first.
2. Reuse existing endpoint contracts.
3. Inspect only the exact API/service involved in the requested task.
4. Do not rediscover unrelated backend architecture.
5. Do not modify Fingerprint behavior while adding Face/NFC/Iris support.
6. Preserve backward compatibility where practical.
7. Keep local biometric operation independent of temporary backend failure.
8. One backend task = one contract change.
9. Update this document if a verified contract materially changes.
10. Stop when the requested contract is implemented and verified.