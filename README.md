# Tunerquet v0.5 — Android TV companion prototype

Tunerquet is a handset companion for Android TV / Google TV optimization. It provides local discovery, explicit Wireless Debugging pairing, authenticated TLS connections, package/RAM/storage inventory and **reviewed, disable-only cleanup with journaled restore**. Initial cleanup eligibility is limited to recognized user-installed media apps. System-app cleanup, settings edits and reboot remain unavailable.

## Build and run

1. Open this folder in Android Studio and install Android SDK Platform 35.
2. Use JDK 17 or newer (verified with JDK 21).
3. Let Gradle synchronize. The included Gradle 8.11.1 wrapper has a pinned distribution checksum and verified wrapper JAR.
4. Run on an Android handset/emulator with API 26 or newer. The target TV must support Android 11+ Wireless Debugging and shell-v2.

With `ANDROID_HOME` pointing to your SDK (or `sdk.dir` in untracked `local.properties`):

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew.bat :app:connectedDebugAndroidTest
```

The second command needs a connected test emulator/handset. The APK is `app/build/outputs/apk/debug/app-debug.apk`. First use requires internet access for dependencies. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the pinned TLS/SPAKE2 dependencies, licenses and rebuild references.

## Pair, connect and inspect

1. On the TV, open **Wireless Debugging → Pair device with pairing code**. In Tunerquet select the advertised pairing service or **Pair using a TV code**. Enter the IP, pairing port and fresh six-digit code, then approve the selected TV.
2. Select **Connect and read inventory**, choose the saved TV and enter the **connection port** from the main Wireless Debugging screen. Pairing and connection ports are different; a discovered service is only an address hint.
3. Confirm the TV and read-only request. On the first connection, verify the TV address on a trusted local network and explicitly approve the displayed server public-key fingerprint. No inventory command is sent before that approval.
4. Review the timestamped snapshot, per-reading issues and searchable user-0 package list. The connection closes when collection finishes. Refresh to collect new readings; an old snapshot is never a live connection.

### Authentication and server identity

Pairing uses TLS 1.3, a TLS-exporter-bound SPAKE2 proof and authenticated GUID exchange. The client RSA private key stays non-exportable in AndroidKeyStore. Codes are never persisted, logged or restored after recreation and are cleared on submission/background/navigation.

A command connection sends ADB CNXN, requires STLS, performs TLS 1.3 with the client identity, then waits for the server's **encrypted CNXN**. A TLS handshake alone is not accepted as evidence that ADB authorized the client. Plaintext/legacy AUTH fallback and arbitrary shell commands are deliberately unsupported.

Android pairing establishes the TV's trust in the client key. It does not cryptographically bind the separately generated ADB server key to the saved GUID. This app therefore uses **explicit trust on first connection**, followed by SHA-256 public-key pinning. Initial trust requires verifying the TV address on a trusted local network; it is not independent proof of the server identity. A changed pin blocks inventory. Android can regenerate the server key when its ADB daemon restarts; pair with the TV again and approve the new key rather than silently replacing a pin. See the [AOSP server-key implementation](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/auth.cpp).

The app checks the saved pairing record again before every request. If the TV exposes `persist.adb.wifi.guid`, it must match before package queries proceed; otherwise the snapshot carries an identity-verification warning. Forgetting or replacing a record invalidates an in-flight request. Local **Forget record** cannot revoke a key on the TV; remove Tunerquet from the TV's paired devices to revoke access. The client identity is shared across paired TVs.

### Inventory and partial results

The inventory flow exposes these fixed read-only requests:

| Request | Reading |
| --- | --- |
| `getprop` | Device metadata and pairing GUID when exposed |
| `pm list packages -f -U --user 0` | Package names, APK paths and UIDs |
| `pm list packages -d --user 0` | Disabled-package state |
| `pm list packages -s --user 0` | System/user classification |
| `dumpsys package` | Package diagnostics and versions |
| `cat /proc/meminfo` | Total, available, free and cached RAM as separate readings |
| `df -k /data` | Data filesystem capacity/used/available space and reported mount |
| `dumpsys meminfo` | Process-memory diagnostic output |

Shell-v2 stdout, stderr and exit status stay separate. Failed/empty/malformed readings are marked partial; missing memory/storage/state is unknown, never inferred as zero, enabled or optional. A broken protocol stream closes the connection and skips remaining requests. Packet lengths, stream IDs, output sizes and shell exit framing are checked. TCP connect has an 8-second limit, connection establishment a 20-second deadline, socket reads a 10-second timeout, each command a 30-second deadline, and the overall flow a 120-second limit including first-key approval.

RAM and filesystem storage are labeled separately. Free/cached RAM is not substituted for available RAM, and no cleanup savings are claimed. Diagnostics offer a selectable preview capped at 12,000 characters. Raw readings and snapshots stay in memory; cancellation/navigation discard pending results and Activity recreation never restores approval or automatically connects. Approved server pins and last connection ports join the pairing metadata in an atomic `noBackupFilesDir` file.

## Safety and profiles

Five planning presets persist the selected profile. The baseline safety checker explains immutable package protections. Inventory does **not** populate verified optional-system-package evidence or a complete dependency graph. System apps and unfamiliar packages remain locked. Profiles, imports and manually supplied risk labels cannot authorize changes.

## Disable-only cleanup and restore

1. Pair with the TV, then read inventory and explicitly trust its server key.
2. Open **Disable apps and restore**, select the same TV and its current connection port, approve fresh checks, and select **Prepare fresh cleanup check**.
3. Review eligible apps and their feature-loss explanation. Nothing is preselected. Select apps, then **Review selected apps** and confirm the complete batch.
4. Each operation saves the original state in an atomic write-ahead journal before sending pm disable-user --user 0 PACKAGE. A second package-state read must confirm enabled=3; command text or exit status alone never proves success.
5. Prepare a fresh check to access **Check and restore original state** for each saved entry. Restore uses pm default-state --user 0 PACKAGE for original state 0, or pm enable --user 0 PACKAGE for original state 1, and verifies the result. See the [AOSP enabled-state commands](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/PackageManagerShellCommand.java).

The initial catalog covers the Netflix phone/TV, Disney+, Prime Video phone/TV, Hulu, Spotify TV and YouTube TV package identifiers listed in CleanupPolicy.optionalMedia. These identifiers describe intended media clients, not a guarantee of vendor compatibility or app authenticity. Only enabled user-installed apps under /data/app/, with known version/state, a unique ordinary app UID, no reported shared/required/library role and no launcher/keyboard role can appear. System or updated-system packages are excluded even if their identifier is in the catalog. This is a separate, explicitly reviewed user-app policy; it does not relax the existing optional-system-package policy or assert complete dependency analysis.

Cleanup checks the active user (only user 0 is supported), fresh package/UID/system/disabled lists, all HOME handlers and installed input methods. It rechecks the selected batch after review, then the exact original app state before each change. Unknown or malformed evidence leaves apps locked. Package arguments are validated and commands use closed enums; there is no uninstall, data-clear, arbitrary shell or reboot action.

The journal stays in noBackupFilesDir across process death and stores TV GUID/key, package identity/version, original state, timestamps and pending/verified/restore status. Journal write failure prevents the next mutation. Connection loss, stop/background/navigation or the five-minute session deadline closes the connection and stops later operations; an already-sent command may complete. Pending/restoring entries remain explicitly uncertain and can be checked/restored after reconnecting. Apps already disabled before cleanup never enter the plan or journal. Restoration requires the same trusted GUID/server key and unchanged package identity/version; a changed key, app update or externally changed enabled state requires manual inspection/TV Settings. Unreadable journals block new cleanup instead of silently resetting history. Clearing app data or uninstalling Tunerquet loses this recovery journal.

Disabling retains the installed APK and app data, and stops playback/background work/integrations until restored. It does not promise recovered disk space or RAM savings. Android package changes are not transactional: concurrent external changes can still race a check. Any failure stops the batch; successful earlier entries remain recorded for individual restoration.

## Validation and remaining work

Validation: the debug APK builds, all 69 JVM tests and 28 Android tests pass, and lint reports zero issues. Android tests ran on an API 37 x86_64 emulator. The latter exercise native pairing, real TLS with the shipping KeyStore/Conscrypt providers, mocked ADB streams, actual Android package/memory/storage output, pin persistence, cleanup approval recreation, atomic journal persistence, and an actual disable/restore round trip on an inert, signed emulator fixture with APK/data retention. See [fixture setup](app/src/androidTest/fixtures/README.md); the round-trip test skips on devices without that dedicated fixture. No physical TV was contacted.

Real Android TV/Google TV, minimum-API clients, ARM ABIs and 16 KB page-size devices still need validation. Legacy RSA connections, credential rotation, vendor dependency analysis, profile proposals and system-app cleanup remain future work. See [ROADMAP.md](ROADMAP.md).
