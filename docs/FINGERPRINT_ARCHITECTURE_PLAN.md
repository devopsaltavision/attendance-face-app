# HF-X05 Fingerprint Architecture and Implementation Plan

## 1. Project goal

Build an offline-first fingerprint attendance application for the HF-X05. It must capture real fingerprints, register employees locally, enroll multiple impressions of the same finger, generate templates, identify employees without Internet access, record attendance locally first, and synchronize application data later.

Hardware capture, fingerprint matching, biometric storage, attendance, and backend integration must remain independently replaceable. The system must not require HF FingerSDK, FingerAlgAPI, FingerprintLicenseAPI, vendor SDK credentials, a paid fingerprint SDK, or Internet access for identification.

## 2. Current verified hardware baseline

Real capture has been proven on an HF-X05 running Android 11:

- `/dev/spidev3.0` is accessible.
- `/dev/mtgpio` is accessible with `O_RDONLY`.
- GPIO 164 power control works.
- Scanner reset and SPI register communication work.
- Sensor ID `0x66` was detected.
- The recovered initialization table works.
- Full image transfer works and produced an actual fingerprint ridge image.

The captured image is 256 × 360, 8-bit grayscale, 92,160 bytes, with a 500 DPI reference. The successful test image had a minimum pixel value of 1, maximum of 255, and 255 unique values.

The current RAW/BMP capture facility is engineering diagnostics only. It is not the production biometric-storage design.

## 3. High-level architecture

```text
UI / Application
        |
        v
Fingerprint Application Services
        |
        +--------------------+
        |                    |
        v                    v
Scanner Layer          Matching Layer
        |                    |
        v                    v
HF-X05 Driver       Fingerprint Templates
                             |
                             v
                     Biometric Repository
                             |
                             v
                     Identification Engine
                             |
                             v
                     Attendance Service
                             |
                             v
                     Backend / Sync Layer
```

Each boundary represents a separate responsibility. In particular, device communication must not leak into matching or application services, and backend availability must not affect real-time identification.

## 4. Scanner layer

The scanner owns only hardware availability, GPIO power control, SPI communication, initialization, reset, image acquisition, image metadata, cleanup/power-off, diagnostics, and scanner-specific errors.

It must not know employee IDs, attendance concepts, Firebase or backend services, template databases, or matching rules.

Target abstraction:

```kotlin
interface FingerprintScanner {
    fun initialize(): ScannerResult
    fun capture(): ScannerResult<FingerprintImage>
    fun shutdown(): ScannerResult
}
```

`FingerprintImage` contains pixel bytes, width, height, DPI, and optional capture-quality metadata. The concrete implementation is `Hfx05FingerprintScanner`. It keeps `/dev/mtgpio`, `/dev/spidev3.0`, GPIO 164, sensor registers and ID, initialization tables, SPI framing, and capture sequencing internal.

The long-term goal is an internal reusable scanner SDK. Future hardware can implement the same `FingerprintScanner` contract.

## 5. Matching layer

Matching is independent from scanning and provides these transformations:

```text
FingerprintImage -> FingerprintTemplate
template + template -> match score
probe template + candidates -> IdentificationResult
```

Target abstraction:

```kotlin
interface FingerprintMatcher {
    fun createTemplate(image: FingerprintImage): FingerprintTemplate
    fun compare(probe: FingerprintTemplate, enrolled: FingerprintTemplate): MatchResult
    fun identify(probe: FingerprintTemplate, candidates: List<FingerprintTemplate>): IdentificationResult
}
```

The implementation must be replaceable without changing scanning, enrollment UI, attendance, or backend code. Better HR/HF score thresholds must not be reused. Thresholds and score interpretation belong to the selected matcher implementation and configuration.

Every stored template records `matcherType`, `matcherVersion`, and `templateFormat`.

## 6. Enrollment design

Initial enrollment requires five accepted captures of the same finger, with slight repositioning between scans:

```text
EMP001
  RIGHT_INDEX
    Template 1
    Template 2
    Template 3
    Template 4
    Template 5
```

The raw images must not be combined pixel-by-pixel. Separate templates preserve coverage for different placements.

Enrollment must validate capture quality, reject unusable images without incrementing progress, confirm scans 2–5 appear to be the same finger as earlier accepted samples, detect duplicates among existing employees, and show progress such as `3/5 accepted scans`.

Raw images should be discarded after template generation. RAW/BMP retention remains an explicit developer/debug option.

## 7. Multiple-finger support

Storage must model `employee -> finger -> enrollment samples`, not `employee -> one fingerprint`. Finger type is an explicit enum/value even if V1 enrolls only one finger.

```text
EMP001
  RIGHT_INDEX
    5 templates
  LEFT_INDEX
    5 templates
```

## 8. Local biometric storage

Identification must work offline. A conceptual `EnrolledFingerprint` record contains:

- employee ID
- finger type
- sample index
- template bytes
- template format
- matcher type and version
- creation and update timestamps

Raw images are not the primary production storage format. The repository exposes operations to save enrollment, load an employee's fingerprints, list all enrolled templates, delete enrollment, update/re-enroll, and check duplicate enrollment. Persistence technology will be selected later.

## 9. Template cache

Enrolled templates should be loaded into a `FingerprintTemplateCache` for attendance identification. Hundreds of records must not be reloaded from disk for every scan. Registration and removal update both persistent storage and the in-memory cache.

## 10. Company size and search scale

The initial target is approximately 150 employees, one finger each, and five templates per finger: about 750 templates. This is suitable for initial exhaustive 1:N matching. Avoid premature indexing; benchmark actual generation and identification performance on the HF-X05 first.

## 11. Identification algorithm

Initial identification creates one probe template and compares it with cached enrollment templates. For a finger with five templates:

```text
fingerScore = MAX(template1, template2, template3, template4, template5)
```

Use the best score rather than an average as the initial rule. Evaluate the matcher-specific minimum threshold, best candidate, second-best candidate, and a confidence/separation rule. Accept a clear winner, request another scan when the leading candidates are too close, and return `Fingerprint not registered` when no result meets threshold and confidence requirements.

## 12. Duplicate detection

During enrollment, search new templates against existing employee templates. A strong match owned by another employee must produce a duplicate-enrollment warning instead of being silently saved. Threshold and confidence rules belong to matcher configuration and must be validated with real captures.

## 13. Quality control

The scanner may report basic image statistics, but enrollment and matcher services own quality policy. Supported outcomes should include:

- `ACCEPT`
- `RETRY_LOW_QUALITY`
- `RETRY_POSITION`
- `DIFFERENT_FINGER_DETECTED`
- `DUPLICATE_EMPLOYEE_FINGERPRINT`
- `COMPLETE`

These policies must not be embedded in the low-level driver.

## 14. Backend boundary

Backend integration is outside scanning and matching. Later it may provide employee metadata and synchronization, attendance synchronization, device configuration, and admin/authentication data. It must not be required for normal real-time identification. Raw fingerprints should not be uploaded during normal attendance; matching remains local unless requirements explicitly change.

## 15. Future attendance flow

```text
Employee places finger
        |
        v
HF-X05 Scanner
        |
        v
FingerprintImage
        |
        v
Matcher creates probe template
        |
        v
Local 1:N identification
        |
        +--- match ---> employee ID
        |
        +--- no match -> Fingerprint not registered
                        |
                        v
                  Attendance service
                        |
                        v
               Save attendance locally
                        |
                        v
                  Backend sync later
```

Attendance is local-first and offline-first.

## 16. Package and module structure

```text
fingerprint/
  scanner/
    FingerprintScanner.kt
    FingerprintImage.kt
    ScannerResult.kt
    hfx05/
      Hfx05FingerprintScanner.kt
      Hfx05NativeBridge.kt
      Hfx05Constants.kt
  matcher/
    FingerprintMatcher.kt
    FingerprintTemplate.kt
    MatchResult.kt
    IdentificationResult.kt
  enrollment/
    EnrollmentService.kt
    EnrollmentSession.kt
    EnrollmentSample.kt
  identification/
    IdentificationService.kt
  repository/
    FingerprintRepository.kt
```

Possible later Gradle modules are `:hfx05-scanner`, `:fingerprint-matching`, and `:app`. Do not create excessive modules now. First stabilize package and interface boundaries; then extract `hfx05-scanner` into an Android library if useful.

## 17. Scanner refactor plan

The next milestone is to refactor the working capture POC behind the scanner architecture without unnecessarily rewriting verified behavior. Preserve GPIO control, reset, sensor initialization, register communication, image capture, and cleanup.

The UI should call a clean scanner API and know no SPI/GPIO details. Engineering diagnostics can remain behind a separate scanner diagnostics API.

## 18. Matcher implementation plan

After the scanner refactor:

1. Create the matcher abstraction.
2. Select an appropriate open-source, local matcher.
3. Integrate it behind `FingerprintMatcher`.
4. Generate templates from 256 × 360 HF-X05 images.
5. Test 1:1 matching with same and different fingers.
6. Measure score distributions using actual HF-X05 captures.
7. Establish an initial matcher-specific threshold.
8. Implement 1:N identification.
9. Benchmark approximately 750 templates on the HF-X05.
10. Add best-versus-second-best confidence handling.

Library-specific classes must not leak into UI or application services.

## 19. Development milestones

1. **Complete — HF-X05 hardware access and raw capture.**
2. **Next — Refactor and enhance the scanner layer.**
3. Matcher abstraction and implementation.
4. Local biometric repository and template cache.
5. Five-scan enrollment.
6. Local 1:N identification.
7. Employee and backend integration.
8. Local attendance persistence and offline synchronization.
9. Production hardening, security, and device management.

## 20. Test plan

### Scanner

- Emulator reports unavailable.
- HF-X05 initializes and detects the sensor ID.
- Capture returns 256 × 360 pixels.
- Unusable/all-FF images are rejected.
- Cleanup powers the sensor off.
- Repeated captures work.

### Matcher

- Same capture versus itself.
- Same finger with different placement, pressure, and angle.
- Different finger on the same person.
- Different employee.
- Unknown finger.
- Threshold boundary.
- Ambiguous top candidates.

### Enrollment

- Five valid scans.
- Low-quality retry.
- Accidental different finger.
- Duplicate employee finger.
- Restart/recovery behavior.

### Identification performance

Benchmark 50, 100, and 150 employees at five templates each. Measure template-generation time, number of comparisons, total identification time, and memory usage.

## 21. Security and biometric data

Fingerprint data is sensitive. Apply local-first storage, minimum retention, templates over raw images, no biometric bytes in logs or diagnostics, no default raw-image upload, protected local storage, delete/re-enroll support, and versioned template formats. Keep engineering RAW/BMP capture separate from production behavior. Encryption and key storage will be designed with the biometric repository.

## 22. Non-negotiable architectural rules

1. Scanner does not know employee IDs.
2. Scanner does not perform matching.
3. Matcher does not know SPI/GPIO.
4. Repository does not control scanner hardware.
5. Backend does not participate in normal real-time matching.
6. UI does not call native GPIO/SPI directly.
7. Raw images are not the long-term production database.
8. Matcher scoring stays inside matcher implementation/configuration.
9. Hardware implementations can be replaced independently.
10. Matching implementations can be replaced independently.

## 23. Immediate next tasks

After this document is committed:

- **Task A:** Refactor and enhance the scanner layer around the proven HF-X05 capture implementation.
- **Task B:** Create the matcher abstraction and implement the first local matcher.
- **Task C:** Validate same-finger versus different-finger matching using real HF-X05 captures.

Only after Tasks A–C are stable should enrollment persistence and higher-level application features begin.
