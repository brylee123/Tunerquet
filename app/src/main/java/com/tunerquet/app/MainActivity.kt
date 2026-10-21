package com.tunerquet.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import com.tunerquet.app.adb.*
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
        when (savedInstanceState?.getString("screen")) {
            "discovery" -> showDiscovery(false)
            "pairing" -> showPairing()
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
        button("Explore profiles · ${currentProfile.displayName}") { showProfiles() }
        button("Check package safety") { showSafety() }
        button("TV setup guide") { showInstructions() }
        card("What comes next", "Pair using a TV code, then continue toward authenticated connection and read-only inventory. Package changes and reboot are not available in this build.")
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
        card("Protected in every profile", "Core services, launchers, keyboards, TV inputs and known dependencies stay locked. Uncertain packages stay enabled. No inventory has been collected yet.")
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen)
        super.onSaveInstanceState(outState)
    }
    @Suppress("DEPRECATION")
    @Deprecated("Legacy back dispatch for the API 26 Activity")
    override fun onBackPressed() {
        if (screen != "home") showHome() else super.onBackPressed()
    }
    override fun onStop() { pairingCode?.text?.clear(); pairing.cancel(); discovery.stop("Scan stopped while the app was in the background."); super.onStop() }
    override fun onDestroy() { pairing.close(); discovery.stop(); super.onDestroy() }
}
