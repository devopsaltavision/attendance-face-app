# HF-X05 device setup

The documented target is an HFSECURITY / Proline HF-X05 on Android 11 / API 30. The package is `com.syntaxgenie.hfx05attendance`; the documented ADB serial is `HF20250221000350A62`.

## ADB and installation

```powershell
adb devices -l
adb -s HF20250221000350A62 shell getprop ro.product.model
adb -s HF20250221000350A62 shell getprop ro.build.version.sdk
adb -s HF20250221000350A62 install -r <SIGNED_APK_PATH>
adb -s HF20250221000350A62 shell monkey -p com.syntaxgenie.hfx05attendance 1
```

If the device is not listed, confirm its USB/debugging connection and authorization, then restart the ADB server if appropriate:

```powershell
adb kill-server
adb start-server
adb devices -l
```

The app declares camera, internet, network-state, and package-install request permissions. Camera access must be granted for face features. Network is needed for live API calls, employee synchronization, backup/restore, and deferred attendance synchronization; locally available biometric data and pending attendance events support offline operation.

## Diagnostics and logs

```powershell
adb -s HF20250221000350A62 logcat
adb -s HF20250221000350A62 logcat -s AttendanceService FaceScan PassiveSpoof FingerprintEnrollSync
adb -s HF20250221000350A62 shell pm path com.syntaxgenie.hfx05attendance
```

The app includes device, fingerprint, and face diagnostic activities, but normal deployment should use the employee-facing attendance flows.

## Hardware cautions

- Face recognition uses Camera ID 0 with physical orientation 90 degrees. Keep the camera available and avoid changing this verified configuration without a targeted hardware regression test.
- Fingerprint code accesses HF-X05-specific native/GPIO/SPI hardware. GPIO, SPI, reset/init, native access, capture lifecycle, and cleanup are protected paths. Test any intentional change on a physical HF-X05; emulator results cannot validate them.

## Basic installation failures

- **Device offline/unauthorized:** reconnect/authorize debugging and re-run `adb devices -l`.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`:** the installed app and new APK do not share the signing certificate. Obtain the original release key; see [Build and release](BUILD_AND_RELEASE.md).
- **Version downgrade:** use a correctly versioned approved release rather than forcing a downgrade on production hardware.
- **Camera unavailable:** grant camera permission and ensure no conflicting camera user is active.
- **Backend unavailable:** confirm configured endpoint/device/API credentials through the secure configuration process; attendance should remain pending locally when submission cannot complete.
