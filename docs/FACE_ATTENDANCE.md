# Face attendance

This document describes the current release path, not diagnostic or calibration candidate-selection screens.

## Employee flow

```text
Home -> Face Recognition -> face identified -> CHECK IN or CHECK OUT -> attendance result -> Home
```

If identity cannot be confirmed, release UI presents a safe retry message/action (`TRY AGAIN`) or returns home. Unknown and insufficiently confirmed outcomes do not directly submit attendance.

## Recognition implementation

`face.scan.FaceScanActivity` opens the verified Camera 0 path (`FaceScanCameraConfiguration`): 90-degree physical orientation. Frames are processed by YuNet (`YuNetFaceDetectorAdapter`) for a face and landmarks, then by corrected SFace alignment/extraction (`SFaceAlignmentMapper`, `SFaceFeatureExtractorAdapter`). The query is compared against the process-wide `FaceTemplateIndexManager` RAM index.

`FaceRecognitionDecisionPolicy` evaluates ranking with the configuration supplied by `FaceCalibrationFirestoreRepository`; `FaceRecognitionFlowActivity` renders the resulting employee flow and invokes the shared `AttendanceService` with source/biometric type `FACE`. The flow offers Check In and Check Out and persists/queues attendance through the shared service.

## Enrollment and storage

Admin enrollment uses `face.scan.FaceRegistrationActivity` and requires exactly three samples: straight, slight left, and slight right. `LocalFaceEnrollmentRepository` stores the portable SFace feature data in Room protected by `AndroidKeystoreFaceTemplateProtector`. The runtime index is updated after local save so recognition does not decrypt/load templates for every frame.

`FaceEnrollmentSyncService` attempts backend backup asynchronously. Remote failure does not invalidate a successful local enrollment. `FaceBackupService`/remote repository code validates compatible portable templates during restore, encrypts them for the current Android Keystore, persists them locally, and refreshes the index. Raw face images are not the persisted biometric template or remote backup payload.

## Passive spoof handling and failures

`PassiveSpoofDetector` exists and examines passive frame signals. The scan activity currently uses `PassiveSpoofMode.OBSERVE_ONLY`, so it records/assesses signals but does not enforce a blocking liveness verdict in the release scan path. Do not represent it as active anti-spoof enforcement.

Recognition can fail because there is no suitable face, more than one face is present, feature extraction/alignment cannot proceed, the index has no usable template, the decision is unknown/ambiguous, or camera/engine startup fails. The UI keeps these outcomes out of the attendance handoff unless an employee is identified/selected by the applicable current flow.

Relevant components: `FaceScanActivity`, `FaceRecognitionFlowActivity`, `FaceRegistrationActivity`, `FaceTemplateIndexManager`, `LocalFaceEnrollmentRepository`, `FaceEnrollmentSyncService`, `YuNetFaceDetectorAdapter`, and `SFaceFeatureExtractorAdapter`.
