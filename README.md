# Tunerquet v0.3 — Android TV companion prototype

Tunerquet is a handset companion for Android TV / Google TV optimization. This build provides discovery, **explicit Wireless Debugging pairing**, profile planning and baseline safety explanations. Pairing authorizes the app key on the selected TV. The app does not yet open an authenticated ADB command session, inspect installed apps, measure memory, execute cleanup, roll back changes, modify TV settings or reboot a TV.

## Build and run

1. Open this folder in Android Studio and install Android SDK Platform 35.
2. Use JDK 17 or newer (verified here with JDK 21).
3. Let Gradle synchronize. The included Gradle 8.11.1 wrapper has a pinned distribution checksum and verified wrapper JAR.
4. Run on an Android handset or emulator (API 26 or newer).

With `ANDROID_HOME` pointing to your SDK (or `sdk.dir` in an untracked `local.properties`):

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

To exercise the shipping native SPAKE2 implementation, AndroidKeyStore and TLS provider, connect an emulator/test handset and run:

```powershell
./gradlew.bat :app:connectedDebugAndroidTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. First use requires internet access for build dependencies. The native SPAKE2 dependency is fetched from JitPack with an exclusive repository filter; Conscrypt comes from Maven Central. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for versions, licenses and rebuild references.

## Pair a TV

1. On an Android 11+ TV that supports Wireless Debugging, open **Pair device with pairing code**. Menu names vary by manufacturer.
2. Discover the pairing service in Tunerquet, or select **Pair using a TV code** and enter its IP address and pairing port manually. Hostnames and URLs are not accepted. The pairing port differs from the connection port.
3. Enter the fresh six-digit code, confirm the selected TV and select **Pair with this TV**.
4. Wait for a confirmed pairing response. Pairing success means a verified pairing exchange completed and device metadata was saved; it does not mean an ADB command session is connected.

The app uses TLS 1.3, a TLS-exporter-bound SPAKE2 proof, authenticated peer-info exchange and Android's RSA public-key format. The client RSA private key is generated in AndroidKeyStore and remains non-exportable. Codes are not persisted, logged or restored after Activity recreation, and the entry field is cleared on submission/background/navigation. Socket operations have deadlines and a 30-second overall timeout; cancellation closes the raw socket and rejects late completion.

Saved records contain the authenticated GUID, IP, pairing port and time, in an atomic file under `noBackupFilesDir`. **Forget record** removes local metadata; it cannot revoke the already trusted key on a TV. Remove Tunerquet from the TV's paired devices to revoke access. One app identity is shared across paired TVs. No automatic reconnect or shell-command API is exposed in this increment.

## Other current functionality

- Native screens with density-aware spacing, system-bar insets, accessible headings/status updates and an adaptive launcher icon.
- Bounded mDNS scans for secure connection, pairing and legacy ADB services, with IPv6 formatting, errors, rescan/stop, lost-service handling and stale-callback filtering.
- Five planning profiles; the selected preset survives navigation and app restarts. Custom selection/export remain planned.
- Baseline package safety checker with human-readable rationale. This screen does not inspect a TV.
- Fail-closed safety policy requiring verified device inventory, optional-package evidence and approval. Core components and required dependencies always override optional evidence. Pairing never makes packages eligible for cleanup.

## Validation and next steps

See [ROADMAP.md](ROADMAP.md) for status. Validation: 29 JVM tests and 13 Android-native protocol/TLS/storage/UI tests passed on an API 37 x86_64 emulator. The debug APK builds successfully. Android lint reports no issues (zero errors or warnings). Real Android TV / Google TV pairing, minimum-API devices, ARM ABIs and 16 KB page-size devices still need validation. No physical TV was contacted.

The next milestone is an authenticated connection to the separate connection port, followed by read-only inventory. Legacy RSA authentication, reconnect identity policy and credential rotation remain pending.
