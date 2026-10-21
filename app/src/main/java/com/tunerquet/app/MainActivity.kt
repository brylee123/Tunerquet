package com.tunerquet.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import com.tunerquet.app.adb.*
import com.tunerquet.app.inventory.*
import java.util.concurrent.Executor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.*

/** Read-only companion app. Discovery never opens an ADB connection. */
class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var discovery: Discovery
    private var scan = ScanState()
    private lateinit var pairing: PairingSession
    private lateinit var pairedDevices: AndroidPairedDeviceStore
    private lateinit var inventory: InventorySession
    private var inventoryStatus: TextView? = null
    private var inventoryTrust: LinearLayout? = null
    private var inventoryResults: LinearLayout? = null
    private var inventoryStart: Button? = null
    private var inventoryCancel: Button? = null
    private var inventoryInputs = emptyList<View>()
    private var pairingStatus: TextView? = null
    private var pairingCode: EditText? = null
    private var pairButton: Button? = null
    private var cancelPairButton: Button? = null
    private var pairingInputs = emptyList<View>()
    private var pairedRecords: LinearLayout? = null
    private var screen = "home"
    private var currentProfile = Profile.CONSERVATIVE
    private var scanStatus: TextView? = null
    private var scanResults: LinearLayout? = null
    private var scanButton: Button? = null
    private var scanProgress: ProgressBar? = null
    private val ink = Color.rgb(25, 47, 55)
    private val muted = Color.rgb(67, 89, 97)
    private val accent = Color.rgb(0, 102, 94)
    private val preferences by lazy { getSharedPreferences("preferences", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentProfile = Profile.entries.firstOrNull {
            it.name == preferences.getString("profile", null)
        } ?: Profile.CONSERVATIVE
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(242, 247, 247))
            isFillViewport = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(28))
        }
        scroll.addView(content)
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 30) {
            val lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(lightBars, lightBars)
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { if (screen == "home") finish() else showHome() }
        }
        discovery = Discovery(this) { state ->
            scan = state
            if (screen == "discovery") renderScan()
        }
        pairedDevices = AndroidPairedDeviceStore(this)
        val mainHandler = Handler(Looper.getMainLooper())
        val identity = AdbIdentity()
        pairing = PairingSession({ TlsPairingClient(identity) }, pairedDevices,
            Executor { mainHandler.post(it) }, { if (screen == "pairing") renderPairing(it) })
        inventory = InventorySession({ TlsInventoryClient(identity) }, pairedDevices,
            Executor { mainHandler.post(it) }, { if (screen == "inventory") renderInventory(it) })
        when (savedInstanceState?.getString("screen")) {
            "discovery" -> showDiscovery(false)
            "pairing" -> showPairing()
            "inventory" -> showInventory()
            "profiles" -> showProfiles()
            "safety" -> showSafety()
            "setup" -> showInstructions()
            else -> showHome()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 16f, parent: LinearLayout = content, bold: Boolean = false): TextView =
        TextView(this).also {
            it.text = value
            it.textSize = size
            it.setTextColor(if (bold) ink else muted)
            if (bold) it.setTypeface(null, Typeface.BOLD)
            it.setLineSpacing(dp(3).toFloat(), 1f)
            it.setPadding(0, dp(6), 0, dp(14))
            parent.addView(it)
        }
    private fun button(label: String, parent: LinearLayout = content, action: () -> Unit): Button = Button(this).also {
        it.text = label
        it.isAllCaps = false
        it.setTextColor(accent)
        it.minHeight = dp(52)
        parent.addView(it, LinearLayout.LayoutParams(-1, -2))
        it.setOnClickListener { action() }
    }
    private fun card(heading: String, body: String, parent: LinearLayout = content) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(214, 228, 227))
            }
        }
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        text(heading, 18f, box, true)
        text(body, parent = box)
    }
    private fun reset(destination: String, heading: String, subtitle: String) {
        pairing.reset()
        inventory.reset()
        inventoryStatus = null
        inventoryTrust = null
        inventoryResults = null
        inventoryStart = null
        inventoryCancel = null
        inventoryInputs = emptyList()
        screen = destination
        discovery.stop()
        pairingCode?.text?.clear()
        pairingCode = null
        pairingStatus = null
        pairButton = null
        cancelPairButton = null
        pairingInputs = emptyList()
        pairedRecords = null
        scanStatus = null
        scanResults = null
        scanButton = null
        scanProgress = null
        content.removeAllViews()
        text("TUNERQUET  /  NO PACKAGE CHANGES", 12f, bold = true)
        text(heading, 30f, bold = true).accessibilityHeadingCompat()
        text(subtitle)
    }
    private fun TextView.accessibilityHeadingCompat() {
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    private fun showHome() {
        reset("home", "A clearer view of your TV", "Discover debugging services and explore safer cleanup plans.")
        card("Discovery is ready", "Find advertised debugging endpoints on your local network. Discovered services are unverified and may belong to any Android device.")
        button("Discover devices") { showDiscovery() }
        button("Pair using a TV code") { showPairing() }
        button("Connect and read inventory") { showInventory() }
        button("Explore profiles · ${currentProfile.displayName}") { showProfiles() }
        button("Check package safety") { showSafety() }
        button("TV setup guide") { showInstructions() }
        card("What comes next", "Pair with your TV, then connect to read package, RAM and storage information. Package changes and reboot are not available in this build.")
    }

    private fun showDiscovery(start: Boolean = true) {
        reset("discovery", "Discover devices", "Use the same local network as your TV and enable Wireless Debugging. Pairing and connection services can have different ports.")
        scanStatus = text("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        scanProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).also {
            it.isIndeterminate = true
            content.addView(it, LinearLayout.LayoutParams(-1, dp(4)))
        }
        scanButton = button("Scan again") {
            if (scan.scanning) discovery.stop() else discovery.start()
        }
        scanResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; content.addView(this) }
        button("Back to home") { showHome() }
        if (start) discovery.start() else renderScan()
    }
    private fun renderScan() {
        scanStatus?.text = getString(R.string.scan_status, scan.message,
            resources.getQuantityString(R.plurals.discovered_services, scan.endpoints.size, scan.endpoints.size))
        scanButton?.text = if (scan.scanning) "Stop scan" else "Scan again"
        scanProgress?.visibility = if (scan.scanning) View.VISIBLE else View.GONE
        val results = scanResults ?: return
        results.removeAllViews()
        if (scan.endpoints.isEmpty()) {
            card(if (scan.scanning) "Looking for advertisements…" else "No results to show",
                "Keep Wireless Debugging open on the TV. If nothing appears, check Wi-Fi, guest network isolation and VPN settings, then scan again.", results)
        }
        scan.endpoints.forEach { endpoint ->
            card(endpoint.kind.label, "${endpoint.name}\n${endpoint.address}\nUnverified advertisement", results)
            if (endpoint.kind == EndpointKind.PAIRING) {
                button("Pair with ${endpoint.address}", results) { showPairing(endpoint) }
            } else if (endpoint.kind == EndpointKind.CONNECT) {
                button("Connect to ${endpoint.address}", results) { showInventory(endpoint) }
            }
        }
        if (!scan.scanning && scan.endpoints.isNotEmpty()) {
            text("Results are from the last scan and may have changed.", 14f, results)
        }
        scan.errors.forEach { card("Scan issue", it, results) }
    }

    private fun showProfiles() {
        reset("profiles", "Choose a starting point", "Presets describe future proposals. Choosing one does not approve any package changes.")
        val profiles = Profile.entries
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, profiles.map { it.displayName })
            contentDescription = "Cleanup profile"
            minimumHeight = dp(52)
            setSelection(profiles.indexOf(currentProfile))
        }
        content.addView(spinner)
        val details = text(currentProfile.description)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentProfile = profiles[position]
                preferences.edit().putString("profile", currentProfile.name).apply()
                details.text = currentProfile.description
            }
        }
        card("Protected in every profile", "Core services, launchers, keyboards, TV inputs and known dependencies stay locked. Uncertain packages stay enabled. Inventory readings never approve package changes or replace device dependency analysis.")
        button("Back to home") { showHome() }
    }

    private fun showSafety() {
        reset("safety", "Understand package safety", "Check the baseline policy for a package identifier. This is not an inspection of your TV.")
        val input = EditText(this).apply {
            hint = "com.vendor.package"
            contentDescription = "Package identifier"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
        }
        content.addView(input, LinearLayout.LayoutParams(-1, -2))
        val result = text("Enter a package identifier to see its safety rationale.").apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        button("Explain safety") {
            val assessment = Safety.assess(input.text.toString().trim())
            result.text = getString(R.string.safety_result, assessment.risk.name, assessment.reason)
        }
        card("Device evidence comes first", "An active launcher or keyboard can use an unfamiliar package name. Authenticated inventory and dependency checks are required before any package can be proposed for cleanup.")
        button("Back to home") { showHome() }
    }

    private fun showInstructions() {
        reset("setup", "Prepare your TV", "Menu names vary by manufacturer and Android version.")
        card("1 · Use your local network", "Connect the phone and TV to the same trusted network. Guest network isolation can prevent discovery.")
        card("2 · Enable developer options", "On the TV, open Settings → System / Device Preferences → About. Select Build repeatedly until developer options are enabled.")
        card("3 · Open Wireless Debugging", "Enable Wireless Debugging in developer options. Choose Pair device with pairing code to advertise the pairing service. Older TVs may only support legacy ADB.")
        card("4 · Discover services", "Select a pairing service or enter the TV address and pairing port manually. Enter the fresh six-digit code only in the pairing screen. The pairing port differs from the connection port.")
        card("When you finish", "Turn off Wireless Debugging when you no longer need it.")
        button("Discover devices") { showDiscovery() }
        button("Pair using a TV code") { showPairing() }
        button("Back to home") { showHome() }
    }

    private fun showPairing(endpoint: DebugEndpoint? = null) {
        reset("pairing", "Pair with your TV", "On Android 11+ TVs, open Wireless Debugging → Pair device with pairing code. Use the IP address, pairing port and fresh six-digit code from that dialog.")
        fun field(label: String, initial: String = "", numeric: Boolean = false): EditText = EditText(this).also {
            it.hint = label
            it.contentDescription = label
            it.setSingleLine(true)
            it.isSaveEnabled = false
            it.inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER else
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            it.setText(initial)
            content.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        val host = field("TV IP address", endpoint?.host ?: "")
        val port = field("Pairing port", endpoint?.port?.toString() ?: "", true)
        val code = field("Six-digit pairing code", numeric = true).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        pairingCode = code
        val approve = CheckBox(this).apply {
            setText(R.string.pairing_approval)
            content.addView(this)
        }
        pairingInputs = listOf(host, port, code, approve)
        pairingStatus = text("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        pairButton = button("Pair with this TV") {
            try {
                val target = PairingTarget.parse(host.text.toString(), port.text.toString())
                val chars = CharArray(code.length()) { code.text[it] }
                code.text.clear()
                pairing.start(target, chars, approve.isChecked)
            } catch (e: IllegalArgumentException) {
                pairingStatus?.text = e.message
            } catch (_: IllegalStateException) {
                pairingStatus?.setText(R.string.pairing_already_running)
            }
        }
        cancelPairButton = button("Cancel pairing") { pairing.cancel() }
        card("Pairing is an authorization", "Pairing lets this TV trust the app debugging key. The key stays in AndroidKeyStore. The code is not saved. Pairing does not open an ADB session, inspect apps or authorize cleanup.")
        pairedRecords = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; content.addView(this) }
        renderSavedPairings()
        button("Back to home") { showHome() }
        renderPairing(pairing.state)
    }
    private fun renderSavedPairings() {
        val box = pairedRecords ?: return
        box.removeAllViews()
        val remembered = try { pairedDevices.list() } catch (_: Exception) {
            text("Saved pairing metadata could not be read. No connection will be attempted.", parent = box)
            emptyList()
        }
        remembered.forEach { device ->
            card("Previously paired", "${device.host}\nDevice identity: ${device.guid}\nSaved pairing record; current connection has not been checked.", box)
            button("Read inventory for ${device.host}", box) { showInventory(selectedGuid = device.guid) }
            button("Forget ${device.host}", box) {
                AlertDialog.Builder(this).setTitle("Forget local pairing record?")
                    .setMessage("This removes the saved record from this phone. To revoke access, remove Tunerquet from the TV paired devices in Wireless Debugging. The app key is shared with other paired TVs.")
                    .setPositiveButton("Forget record") { _, _ ->
                        try { pairedDevices.forget(device.guid); showPairing() }
                        catch (_: Exception) { pairingStatus?.setText(R.string.pairing_forget_failed) }
                    }.setNegativeButton("Keep", null).show()
            }
        }
    }

    private fun renderPairing(state: PairingState) {
        pairingStatus?.text = state.message
        val running = state.phase == PairingPhase.PAIRING
        pairButton?.isEnabled = !running
        cancelPairButton?.isEnabled = running
        cancelPairButton?.visibility = if (running) View.VISIBLE else View.GONE
        pairingInputs.forEach { it.isEnabled = !running }
        if (state.phase == PairingPhase.PAIRED) renderSavedPairings()
    }

    private fun showInventory(endpoint: DebugEndpoint? = null, selectedGuid: String? = null) {
        reset("inventory", "Read your TV inventory", "Select a paired TV and enter the connection port from Wireless Debugging. This is different from the pairing port. No packages or settings will be changed.")
        val devices = try { pairedDevices.list() } catch (_: Exception) { emptyList() }
        if (devices.isEmpty()) {
            card("Pair with a TV first", "Inventory requires a saved pairing record. Discovery alone cannot authorize a connection.")
            button("Pair using a TV code") { showPairing() }
            button("Back to home") { showHome() }
            return
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, devices.map { "${it.host} · ${it.guid}" })
            contentDescription = "Paired TV"
            minimumHeight = dp(52)
            setSelection(devices.indexOfFirst { it.guid == selectedGuid || (selectedGuid == null && it.host == endpoint?.host) }.coerceAtLeast(0))
        }
        content.addView(spinner)
        fun field(label: String): EditText = EditText(this).also {
            it.hint = label
            it.contentDescription = label
            it.setSingleLine(true)
            it.isSaveEnabled = false
            content.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        val host = field("TV IP address")
        val port = field("Connection port").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        fun selectDevice() {
            val device = devices[spinner.selectedItemPosition]
            host.setText(endpoint?.host ?: device.host)
            port.setText(endpoint?.port?.toString() ?: device.connectionPort?.toString() ?: "")
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selectDevice() }
        }
        selectDevice()
        val approve = CheckBox(this).apply { setText(R.string.inventory_approval); content.addView(this) }
        inventoryInputs = listOf(spinner, host, port, approve)
        inventoryStatus = text("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        inventoryStart = button("Connect and read inventory") {
            try {
                inventory.start(devices[spinner.selectedItemPosition].guid,
                    ConnectionTarget.parse(host.text.toString(), port.text.toString()), approve.isChecked)
            } catch (e: IllegalArgumentException) { inventoryStatus?.text = e.message }
            catch (_: IllegalStateException) { inventoryStatus?.setText(R.string.inventory_running) }
        }
        inventoryCancel = button("Cancel and disconnect") { inventory.cancel() }
        inventoryTrust = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; content.addView(this) }
        inventoryResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; content.addView(this) }
        button("Back to home") { showHome() }
        renderInventory(inventory.state)
    }

    private fun renderInventory(state: InventoryState) {
        inventoryStatus?.text = state.message
        val running = state.phase in setOf(InventoryPhase.CONNECTING, InventoryPhase.TRUST_REQUIRED, InventoryPhase.READING)
        inventoryInputs.forEach { it.isEnabled = !running }
        inventoryStart?.isEnabled = !running
        inventoryCancel?.visibility = if (running) View.VISIBLE else View.GONE
        inventoryTrust?.apply {
            removeAllViews()
            if (state.phase == InventoryPhase.TRUST_REQUIRED) {
                card("Approve the first server key", "Android pairing authorizes the app key on the TV; it does not verify this server key. Confirm the IP on your TV and use a trusted local network before accepting. No inventory command has been sent.\n\nSHA-256 server public-key fingerprint:\n${state.serverKey}\n\nA changed key will block future reads. Android can replace this key after a restart; pair and trust again if that happens.", this)
                button("Trust this server key and read inventory", this) {
                    try { inventory.approveFirstServerKey() }
                    catch (_: IllegalStateException) { inventoryStatus?.setText(R.string.inventory_trust_expired) }
                }
            }
        }
        val results = inventoryResults ?: return
        results.removeAllViews()
        val snapshot = state.snapshot ?: return
        val time = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(snapshot.completedAt))
        card(if (snapshot.complete) "Inventory snapshot" else "Partial inventory snapshot", "${snapshot.address}\n$time\nConnection closed. Readings can change; refresh to collect a new snapshot.\nCleanup stays locked pending device dependency analysis.", results)
        val model = snapshot.properties["ro.product.model"] ?: "Unknown model"
        val vendor = snapshot.properties["ro.product.manufacturer"] ?: "Unknown manufacturer"
        val sdk = snapshot.properties["ro.build.version.sdk"] ?: "Unknown"
        card("Device", "$vendor · $model\nAndroid API $sdk\nPaired identity: ${snapshot.guid}", results)
        fun mib(value: Long?) = value?.let { String.format(java.util.Locale.getDefault(), "%.1f MiB", it / 1048576.0) } ?: "Unknown"
        snapshot.ram?.let { ram ->
            card("RAM", "Total: ${mib(ram.totalBytes)}\nAvailable: ${mib(ram.availableBytes)}\nFree: ${mib(ram.freeBytes)}\nCached: ${mib(ram.cachedBytes)}\nCached and free memory are separate readings; they are not storage space or promised cleanup gains.", results)
        } ?: card("RAM", "Unavailable; no reading was inferred.", results)
        snapshot.storage?.let { storage ->
            card("Data storage · disk", "Total: ${mib(storage.totalBytes)}\nUsed: ${mib(storage.usedBytes)}\nAvailable: ${mib(storage.availableBytes)}\nReported mount: ${storage.mountPoint}\nThis is data filesystem space, separate from RAM.", results)
        } ?: card("Data storage · disk", "Unavailable; no reading was inferred.", results)
        snapshot.warnings.forEach { card("Reading issue", it, results) }
        text("Installed packages · user 0 · ${snapshot.packages.size}", 20f, results, true)
        val search = EditText(this).apply { hint = "Filter package names"; contentDescription = "Filter package names"; setSingleLine(true) }
        results.addView(search)
        val packages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; results.addView(this) }
        fun showPackages() {
            packages.removeAllViews()
            val filtered = snapshot.packages.filter { it.name.contains(search.text.toString().trim(), ignoreCase = true) }
            text("Showing ${minOf(50, filtered.size)} of ${filtered.size} matches. Narrow the filter to see more.", 14f, packages)
            filtered.take(50).forEach { pkg ->
                val system = when (pkg.systemApp) { true -> "System app"; false -> "User app"; null -> "App type unknown" }
                val enabled = when (pkg.disabled) { true -> "Disabled"; false -> "Enabled"; null -> "Enabled state unknown" }
                val safety = Safety.assess(pkg.name)
                card(pkg.name, "$system · $enabled\nVersion: ${pkg.versionName ?: "Unknown"} (${pkg.versionCode ?: "Unknown"})\nUID: ${pkg.uid}\n${pkg.apkPath}\n${safety.risk}: ${safety.reason}", packages)
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { showPackages() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        showPackages()
        text("Read diagnostics", 20f, results, true)
        snapshot.readings.forEach { reading ->
            button("${reading.command.title} · ${if (reading.problem == null) "read" else "issue"}", results) {
                val result = reading.result
                val detail = "${reading.command.command}\n${reading.problem ?: "Completed"}\nExit: ${result?.exitCode ?: "Unknown"}\n\nSTDOUT\n${result?.stdout.orEmpty()}\n\nSTDERR\n${result?.stderr.orEmpty()}"
                val preview = if (detail.length > 12000) detail.take(12000) + "\n[Preview clipped at 12,000 characters]" else detail
                val view = TextView(this).apply { text = preview; setPadding(dp(20), dp(12), dp(20), dp(12)); setTextIsSelectable(true) }
                AlertDialog.Builder(this).setTitle(reading.command.title)
                    .setView(ScrollView(this).apply { addView(view) }).setPositiveButton("Close", null).show()
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen)
        super.onSaveInstanceState(outState)
    }
    @Suppress("DEPRECATION")
    @Deprecated("Legacy back dispatch for the API 26 Activity")
    override fun onBackPressed() {
        if (screen != "home") showHome() else super.onBackPressed()
    }
    override fun onStop() { inventory.cancel(); pairingCode?.text?.clear(); pairing.cancel(); discovery.stop("Scan stopped while the app was in the background."); super.onStop() }
    override fun onDestroy() { inventory.close(); pairing.close(); discovery.stop(); super.onDestroy() }
}
