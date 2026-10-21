# Tunerquet v0.1 — Android Studio starter

Tunerquet is an Android TV/Google TV optimizer. This initial project is a **read-only discovery and profiles prototype**. It does NOT yet implement authenticated ADB, inventory, memory measurement, cleanup execution, rollback, settings modifications, or reboot. It must not be marketed as a working debloater.

## Android Studio

1. Unzip and select **Open** → the `Tunerquet` folder (the folder containing `settings.gradle.kts`).
2. Install Android SDK Platform 35 and Build Tools through SDK Manager if prompted.
3. Let Gradle synchronize (internet needed to fetch Android Gradle Plugin and Kotlin plugin).
4. Choose a connected Android handset or emulator; click **Run**.
5. Alternatively run `gradlew.bat assembleDebug` once Android Studio has generated/configured a Gradle wrapper, or use installed Gradle 8.11.1. This zip intentionally does not contain an unverified wrapper JAR.

## Current functionality
- Native Android Activity with home, scan, profiles and setup screens.
- mDNS scan for `_adb-tls-connect._tcp`, `_adb-tls-pairing._tcp`, and `_adb._tcp` endpoints.
- Five cleanup profile descriptions.
- Safety classification that defaults unknown packages to protected-from-automatic-cleanup.
- Unit test examples.

## Engineering roadmap for Codex
1. Implement documented secure ADB TLS pairing/connect and legacy RSA-authenticated ADB, including key storage and timeout/error handling. Never transmit ADB commands to unpaired/unapproved targets.
2. Read-only inventory via `pm list packages -f -U`, `dumpsys package`, `pm list packages -d`, `cat /proc/meminfo`, `df`, `dumpsys meminfo`; distinguish RAM from disk and cached memory.
3. Device-specific safety engine: immutable protected packages, current launcher/input methods, default keyboard, input/TV/HDMI/Bluetooth components, package dependencies and vendor allowlists. Leave uncertain packages enabled.
4. Explain per-package rationale and risk; profiles only propose changes after verified classification. Persist and export Custom profiles with vendor/model/package metadata.
5. Approval UI and batch review; journal original state before every command; system packages via `pm disable-user --user 0 PACKAGE`, rollback via `pm enable --user 0 PACKAGE`; never auto-uninstall system packages. Require extra confirmation for risky proposals.
6. Settings snapshot/edit/restore, per-command progress, robust error recovery, pause/cancel between operations.
7. Enforce >=30 seconds since last modification before presenting an enabled user-controlled reboot; reconnect, verify TV health, collect comparable before/after readings. Do not claim success without confirmation.
8. Test on Android TV, Google TV, sticks and OEM-integrated sets. Use mock ADB layer for tests.

## Build attempt status
Source-level project generated in a container with JDK 21. No installed Android SDK or Gradle executable were available, so Android APK compilation could not be performed in that runtime.
