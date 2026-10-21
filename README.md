# Tunerquet v0.2 — Android TV companion prototype

Tunerquet is a handset companion for Android TV / Google TV optimization. This build provides **read-only discovery, profile planning and baseline safety explanations**. It does not yet implement authenticated ADB, installed-package inventory, memory measurements, cleanup execution, rollback, settings modifications or reboot.

## Build and run

1. Open this folder in Android Studio and install Android SDK Platform 35.
2. Use JDK 17 or newer (verified here with JDK 21).
3. Let Gradle synchronize. The included wrapper uses Gradle 8.11.1 with a pinned distribution SHA-256; its JAR was verified against Gradle's published checksum.
4. Run on an Android handset or emulator (Android 8.0 / API 26 or newer).

From PowerShell, with `ANDROID_HOME` pointing to your SDK (or `sdk.dir` in an untracked `local.properties`):

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. First use requires internet access to download build dependencies.

## Current functionality

- Native screens with density-aware spacing, system-bar insets, accessible headings/status updates and an adaptive launcher icon.
- Bounded 30-second mDNS scans for secure connection, pairing and legacy ADB services, with typed endpoints, IPv6 formatting, error reporting, rescan and stop controls.
- Sequential NSD resolution, removal of lost services and rejection of late results after stop or restart. Scanning stops when the Activity leaves the foreground.
- Five planning profiles; the selected preset survives navigation and app restarts. Custom package selection, saving and export remain planned.
- Baseline package safety checker with human-readable rationale. No TV is inspected by this screen.
- Device safety context for active launchers, input methods, required components and their dependencies. These protections override optional-package evidence.
- Fail-closed authorization: caller-supplied low risk is insufficient. The policy also requires verified inventory, device-specific optional evidence and explicit approval. There is no command execution path in this build.

## Roadmap and validation

See [ROADMAP.md](ROADMAP.md) for implementation status and the next milestones. Secure authentication is the next major dependency before real inventory can be connected to the safety engine.

On October 20, 2026, the debug APK built successfully and all 15 JVM tests passed using SDK 35 / JDK 21. Android lint reported no issues (zero errors or warnings). No emulator or physical device was connected, so runtime layout, back gestures and real-network discovery still need the manual checks in the roadmap. The unit tests cover safety decisions and endpoint formatting; they do not simulate Android NSD callbacks.
