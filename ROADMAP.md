# Tunerquet implementation roadmap

## v0.2: discovery polish and safety foundations

Implemented:

- Clear read-only capability labels, setup guide and package rationale screen.
- Density-aware native UI, system-bar insets, Android back navigation and adaptive icon.
- Remembered profile selection and descriptions of profile risk.
- Structured discovery results that distinguish pairing, secure connection and legacy services.
- Scan deadline, rescan/stop, visible errors, empty states and last-scan labels.
- Sequential service resolution, service-loss handling and stale callback filtering.
- Stop discovery when backgrounded or leaving the screen.
- Safety decisions with reasons; device-role/dependency protections override optional evidence.
- Prevent a low-risk label from authorizing unknown packages without verified device evidence.
- Gradle wrapper with verified JAR and pinned distribution checksum.
- Regression tests for safety authorization and IPv4/IPv6 address formatting.

This is partial groundwork for original roadmap items 3 and 4. Device roles, dependencies and optional-package evidence are modeled but are not collected from a TV yet. No packages become eligible through the current UI.

## 1. Authenticated ADB transport — next

- Implement documented secure ADB TLS pairing/connect and legacy RSA authentication.
- Store device credentials securely; define revoke/forget behavior and device identity checks.
- Keep pairing and connection ports distinct; never send shell commands to an unpaired or unapproved target.
- Add connection deadlines, cancellation, actionable errors and a mock transport.
- Test wrong pairing codes, expired services, rejected authorization, disconnects and identity changes.

## 2. Read-only inventory

- Collect `pm list packages -f -U`, `dumpsys package` and disabled-package state.
- Collect `/proc/meminfo`, `df` and `dumpsys meminfo` with timestamps and device metadata.
- Distinguish RAM, available/cached memory and storage; record partial/failed reads honestly.
- Parse through a tested read-only inventory layer before exposing real package proposals.

## 3. Device-specific safety classification — foundation implemented

- Populate active launchers, all input methods and default keyboard from authenticated inventory.
- Discover TV/input/HDMI/Bluetooth/boot components and required package dependencies.
- Add reviewed vendor/model allowlists with provenance; uncertainty always leaves a package enabled.
- Bind verified optional evidence to a specific device and inventory snapshot; invalidate it after reconnect or device changes.
- Keep immutable core protections regardless of profile or custom selection.

## 4. Explain and persist plans — foundation implemented

- Display device-specific rationale and feature-loss risk for every real proposal.
- Generate profile proposals only after verified classification.
- Persist and export Custom profiles with vendor/model/package metadata.
- Review imported profiles against current device evidence; imports cannot authorize commands.

## 5. Approval, journal and rollback

- Review a complete batch and require extra confirmation for risky proposals.
- Journal original state durably before each command.
- For system packages, use `pm disable-user --user 0 PACKAGE`; restore original state with appropriate rollback.
- Never automatically uninstall system packages or enable packages that were disabled before the session.

## 6. Settings and resilient execution

- Snapshot/edit/restore settings, show per-command progress and verify results.
- Pause/cancel between operations; handle interrupted sessions and partial failures.

## 7. Reboot and comparable verification

- Require at least 30 seconds since the last modification before enabling user-controlled reboot.
- Reconnect, verify TV health and collect comparable before/after readings.
- Do not report success without confirmation.

## 8. Device coverage and release validation

Pending manual checks for this increment:

- Install on API 26 and API 35+ phones/emulators; inspect small screens, large fonts, keyboard and system-bar insets.
- Check gesture/system back on each screen, rotation, profile persistence and process recreation.
- Scan with no advertisements, multiple endpoints, IPv6, a lost service and network interruptions.
- Stop/restart rapidly and background during resolution; confirm old callbacks cannot repopulate results.
- Exercise discovery start/resolve/stop failures, including partial failure across service types.
- Confirm no ADB connection or command is sent during discovery or safety/profile browsing.

Before implementing execution, add mock-transport tests and validate on Android TV, Google TV, sticks and OEM-integrated sets.
