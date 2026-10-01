# HF-X05 Attendance

Android attendance terminal for the HFSECURITY / Proline HF-X05. It identifies an employee with face or fingerprint recognition, then uses a shared attendance service to record Check In or Check Out.

## Implemented features

- Face enrollment (three SFace samples), local encrypted storage, recognition, and remote backup/restore support
- HF-X05 fingerprint enrollment, local matching, management, and backup/synchronization support
- Employee synchronization, admin screens, device configuration, and attendance history/queue
- Local-first attendance: events are persisted and queued when a backend request cannot be completed

## Architecture and flows

Each biometric method identifies an employee; it does not own attendance rules:

```text
Face or fingerprint -> employeeId -> AttendanceService -> backend / local pending queue
```

Normal attendance flows are:

```text
Home -> Face Recognition -> identified employee -> CHECK IN or CHECK OUT -> result -> Home
Home -> Fingerprint Recognition -> identified employee -> CHECK IN or CHECK OUT -> result -> Home
```

The code is in `app/`; major packages include `attendance`, `face`, `fingerprint`, `employee`, `backend`, and `admin`. See the detailed documents below before changing a subsystem.

## Prerequisites and builds

Use the included Gradle wrapper, a JDK compatible with the Android Gradle Plugin (the project compiles Java 11 source), and installed Android SDK platform 37/build tools. Build a debug APK with:

```powershell
.\gradlew.bat :app:assembleDebug
```

Build the current unsigned arm64-v8a release artifact with:

```powershell
.\gradlew.bat :app:assembleRelease
```

Release signing credentials are deliberately **not** in Git. The current Gradle release build has no signing configuration; obtain the production keystore and credentials through the separate handover, then sign in the approved secure release process. Details: [Build and release](docs/BUILD_AND_RELEASE.md).

## Documentation

- [Technical handover](docs/TECHNICAL_HANDOVER.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Build and release](docs/BUILD_AND_RELEASE.md)
- [HF-X05 device setup](docs/DEVICE_SETUP.md)
- [Face attendance](docs/FACE_ATTENDANCE.md)
- [Fingerprint attendance](docs/FINGERPRINT_ATTENDANCE.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Release-key handover checklist](docs/RELEASE_KEY_HANDOVER_CHECKLIST.md)
- [Existing fingerprint architecture plan](docs/FINGERPRINT_ARCHITECTURE_PLAN.md)

## Important cautions

The HF-X05 fingerprint scanner layer is proven, device-specific code. Do not casually alter its GPIO, SPI, reset/init, native access, capture lifecycle, or cleanup paths; test any deliberate change on physical HF-X05 hardware.

`main` is the approved integration branch and should be the starting point for new work. Historical branches are not a replacement for the approved baseline.
