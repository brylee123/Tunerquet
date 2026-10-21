# Tunerquet implementation roadmap

## v0.3: Wireless Debugging pairing — implemented, device validation pending

- Manual IP/pairing-port/code entry plus selection from discovered pairing services.
- Explicit TV approval; no pairing request before approval and input validation.
- TLS 1.3 bootstrap with exporter-bound SPAKE2 and authenticated GUID exchange.
- AndroidKeyStore RSA-2048 identity, sign-only and non-exportable, with the raw signing padding required by Conscrypt TLS 1.3.
- Atomic authenticated metadata under `noBackupFilesDir`; no persisted pairing code or exported private key.
- 8-second TCP connect timeout, 10-second socket read timeouts and a 30-second overall deadline.
- Cancel/background/navigation abort the transport; old callbacks cannot save or overwrite a newer attempt.
- Local forget UI explains that revocation must happen on the TV and that the app key is shared.
- Mock-session tests, native protocol tests (including wrong codes/tampering), full TLS/KeyStore loopback integration and code-entry recreation test.
- Native SPAKE2 artifact is used; the pure-Java artifact was rejected during validation.

Pairing confirms a trust exchange. It does not open an ADB command session or prove a TV is currently connected/healthy. No physical TV was contacted in development.
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

## 1. Authenticated ADB transport — pairing implemented; connection is next

- Wireless Debugging TLS/code pairing is implemented. Add the separate authenticated TLS connection and legacy RSA authentication.
- AndroidKeyStore identity and local forget behavior are implemented. Add credential rotation and reconnect identity checks.
- Keep pairing and connection ports distinct; never send shell commands to an unpaired or unapproved target.
- Pairing deadlines, cancellation and mock transport are implemented. Extend those guarantees to command connections.
- Wrong-code/tampering and pairing-session failure paths have automated coverage. Validate expired advertisements, actual TVs, revoked trust and identity changes on reconnect.

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

Additional pairing validation before release: real Android TV / Google TV, API 26 clients, ARM ABIs, 16 KB page-size devices, expired pairing dialogs, and physical Wi-Fi disruptions. Automated Android tests currently exercise native crypto, TLS and KeyStore on an API 37 x86_64 emulator. Validate on TVs, sticks and OEM-integrated sets before implementing execution.
