# Release key handover checklist

This checklist contains placeholders only. Do not put credentials or keystore material in Git.

```text
Keystore filename: <PROVIDE_SECURELY>
Key alias: <PROVIDE_SECURELY>
SHA-256 fingerprint: <RECORD_NON-SECRET_FINGERPRINT>
Keystore password: <PROVIDE_SECURELY>
Key password: <PROVIDE_SECURELY>
```

- [ ] Identify the production keystore used for the currently installed production APK.
- [ ] Confirm the key alias.
- [ ] Confirm the keystore and key passwords are available through an approved secure channel.
- [ ] Record the certificate SHA-256 fingerprint.
- [ ] Verify certificate validity with `keytool -list -v -keystore <RELEASE_KEYSTORE_PATH> -alias <KEY_ALIAS>`.
- [ ] Securely transfer the keystore file.
- [ ] Securely transfer credentials through an approved separate channel.
- [ ] Have the new developer/operator perform a test release build.
- [ ] Verify the signed APK certificate with `apksigner verify --verbose --print-certs <SIGNED_APK_PATH>`.
- [ ] Test installation/update on a physical HF-X05 using the signed APK.
- [ ] Store a protected backup of the keystore and recovery instructions.
- [ ] Confirm the keystore and credential files are not in Git.

See [Build and release](BUILD_AND_RELEASE.md) for the current unsigned release output and signing constraints.
