# Build and release

## Current project configuration

This is one Android application module, `:app`. The included wrapper uses Gradle 9.5.0 and the version catalog specifies Android Gradle Plugin 9.3.1. The app compiles Java source with Java 11 compatibility, uses compile/target SDK 37, minimum SDK 23, and has package ID `com.syntaxgenie.hfx05attendance`.

Install a suitable JDK for the Android Gradle Plugin, Android SDK platform 37, and the corresponding SDK build tools. Use the repository wrapper; a separately installed Gradle is not required.

## Build commands

From the repository root:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat clean
.\gradlew.bat :app:clean :app:assembleDebug
```

Debug is universal unless a supported ABI is supplied, for example:

```powershell
.\gradlew.bat :app:assembleDebug -PtargetAbi=arm64-v8a
```

Supported debug values are `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. The release variant always filters native code to `arm64-v8a`. There are no product flavors in the current Gradle configuration.

The exact current release build command is:

```powershell
.\gradlew.bat :app:assembleRelease
```

Because the current `release` build type has no `signingConfig`, its expected output is the unsigned file:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

The standard debug output is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Device installation and verification

The documented target serial is `HF20250221000350A62`.

```powershell
adb devices -l
adb -s HF20250221000350A62 shell getprop ro.build.version.release
adb -s HF20250221000350A62 install -r <SIGNED_APK_PATH>
adb -s HF20250221000350A62 shell pm path com.syntaxgenie.hfx05attendance
```

Use a signed APK for an installed-app update. `install -r` retains app data only when Android accepts the update signature and other compatibility checks.

## Release signing

An Android app already installed on a device normally can be updated only by an APK signed with the **same signing certificate**. A different certificate causes an update/signature failure; do not uninstall a production app merely to work around this without an approved data/recovery plan.

The repository intentionally contains no Gradle `signingConfig` and no release credentials. The outgoing handover must provide separately and securely:

1. Release keystore file
2. Keystore password
3. Key alias
4. Key password

Use placeholders only in local/secure documentation:

```text
<RELEASE_KEYSTORE_PATH>
<KEYSTORE_PASSWORD>
<KEY_ALIAS>
<KEY_PASSWORD>
```

Do not put those values in Git, `build.gradle.kts`, committed `local.properties`, or this document. The root `.gitignore` excludes common keystore and signing-property files. A developer can keep a local, ignored `signing.properties`/`keystore.properties` file or use secure environment/CI secret injection, then use an approved signing process. This repository does not currently consume either signing-properties file, so adding a Gradle signing integration is a separate, reviewed build-change task.

For the current unsigned output, an authorized release operator can sign outside the repository configuration using Android SDK Build Tools. The exact key arguments must come from the secure handover:

```powershell
apksigner sign --ks <RELEASE_KEYSTORE_PATH> --ks-key-alias <KEY_ALIAS> --ks-pass pass:<KEYSTORE_PASSWORD> --key-pass pass:<KEY_PASSWORD> --out <SIGNED_APK_PATH> app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --verbose --print-certs <SIGNED_APK_PATH>
```

Avoid placing passwords in shell history where the operating environment makes that unsafe; approved CI secret variables or an interactive/secure credential mechanism are preferable.

Inspect the handover certificate without exposing a password:

```powershell
keytool -list -v -keystore <RELEASE_KEYSTORE_PATH> -alias <KEY_ALIAS>
```

Record the SHA-256 certificate fingerprint and compare it with the installed production APK/certificate before deploying. See [Release-key handover checklist](RELEASE_KEY_HANDOVER_CHECKLIST.md).

## Runtime configuration

`app/build.gradle.kts` resolves runtime values in this order: environment variable, ignored `local.properties`, then Gradle property. Existing configuration names include `FINGERPRINT_API_BASE_URL`, `FINGERPRINT_API_KEY`, `FINGERPRINT_DEVICE_ID`, `FINGERPRINT_BACKUP_KEY_BASE64`, `FINGERPRINT_EMULATOR`, `FINGERPRINT_GUIDE_MODE`, face-score settings, and `targetAbi`. Supply approved non-secret configuration through the secure project process; do not commit credential values.
