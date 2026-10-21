# Tunerquet v0.4 — Android TV companion prototype

Tunerquet is a handset companion for Android TV / Google TV optimization. It provides local discovery, explicit Wireless Debugging pairing, authenticated TLS connections and **read-only package/RAM/storage inventory**. Profile planning and baseline package safety explanations remain available. Package cleanup, settings edits, rollback and reboot are not implemented.

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

Only these fixed requests are exposed by the transport:

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

Five planning presets persist the selected profile. The baseline safety checker explains immutable package protections. Inventory does **not** populate verified optional-package evidence or a complete dependency graph. No package becomes eligible for cleanup merely because it was inventoried, and there is no mutation API or custom command field.

## Validation and remaining work

Validation: the debug APK builds, all 52 JVM tests and 23 Android tests pass, and lint reports zero issues. Android tests ran on an API 37 x86_64 emulator. The latter exercise native pairing, real TLS with the shipping KeyStore/Conscrypt providers, mocked ADB streams, actual Android package/memory/storage output, pin persistence and Activity recreation. No physical TV was contacted.

Real Android TV/Google TV, minimum-API clients, ARM ABIs and 16 KB page-size devices still need validation. Legacy RSA connections, credential rotation, vendor dependency analysis, profile proposals and cleanup remain future work. See [ROADMAP.md](ROADMAP.md).
