# Troubleshooting

## Build failures

Use the wrapper from repository root and verify a compatible JDK plus Android SDK platform 37/build tools:

```powershell
.\gradlew.bat :app:assembleDebug --stacktrace
.\gradlew.bat clean :app:assembleDebug
```

Do not inspect or commit generated `build/` output as a fix. Check local SDK/JDK configuration first.

## Release signing and installation failures

The current release Gradle type has no signing configuration and outputs `app/build/outputs/apk/release/app-release-unsigned.apk`. Sign it with the authorized production certificate before installation.

For `INSTALL_FAILED_UPDATE_INCOMPATIBLE` or a signature mismatch, confirm the old installed app and new APK use the same certificate:

```powershell
apksigner verify --verbose --print-certs <NEW_SIGNED_APK>
keytool -list -v -keystore <RELEASE_KEYSTORE_PATH> -alias <KEY_ALIAS>
```

Do not work around a production update signature mismatch by uninstalling unless an approved recovery/data plan exists. See [Build and release](BUILD_AND_RELEASE.md).

## ADB device not detected

```powershell
adb devices -l
adb kill-server
adb start-server
adb devices -l
```

Reconnect/authorize the device if it is `unauthorized` or absent. Use the documented serial when more than one device is connected.

## Network/API failures

Online work requires configured base URL, API key, and device ID. Do not print or commit their values. Verify approved configuration and network reachability. `AttendanceService` stores the event before submission; network/configuration/retryable failures leave it pending for `PendingAttendanceSyncWorker`. Employee sync and backup/restore also need connectivity.

## Fingerprint hardware unavailable or not recognized

Use a physical HF-X05. The scanner is device-specific and emulator/guide-mode behavior is not hardware verification. Check logcat, then verify the sensor is not held by a conflicting flow and that the low-level capture lifecycle was not modified. If match quality is poor, retry clean, centered finger placement; do not lower matching policy or rewrite GPIO/SPI/reset/native scanner code as a first response.

## Face not recognized or ambiguous

Ensure camera permission and Camera 0 availability. Recognition needs exactly one usable detected face and a compatible local template/index. A no-face, extraction/alignment, unknown, ambiguous, or engine-start condition should go to the retry/safe UI rather than attendance. Passive spoof code is currently observe-only, so do not diagnose it as a production liveness block.

## Offline attendance and sync

An attendance attempt is first written locally as pending. When connectivity returns, launch the app/scheduled worker and review safe logs for sync outcomes. Rejected, debounced, and duplicate outcomes are terminal backend results; distinguish them from retryable pending failures. Local operation depends on employees/templates having already been synchronized or enrolled locally.

## Backend business rejection

The attendance service maps an explicit backend rejection to a rejected outcome; one known mapped case is no open session for check-out. Treat it as a backend attendance-state/business result, not as a biometric match failure.

## Useful logs

```powershell
adb -s HF20250221000350A62 logcat -s AttendanceService FaceScan PassiveSpoof FingerprintEnrollSync
adb -s HF20250221000350A62 logcat
adb -s HF20250221000350A62 shell pm path com.syntaxgenie.hfx05attendance
```

Keep biometric templates, credentials, and raw API secrets out of logs, tickets, and documentation.
