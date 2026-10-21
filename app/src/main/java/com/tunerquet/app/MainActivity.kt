package com.tunerquet.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.ViewGroup
import android.widget.*

/** Read-only prototype. Does not pair with ADB or modify TV packages. */
class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private var discovery: Discovery? = null
    private val endpoints = linkedSetOf<String>()
    private var currentProfile = Profile.CONSERVATIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 30, 32, 32) }
        scroll.addView(content)
        setContentView(scroll)
        showHome()
    }
    private fun title(s: String) = TextView(this).also {
        it.text = s; it.textSize = 24f; it.setTextColor(Color.rgb(20, 40, 50)); it.setPadding(0, 12, 0, 18)
        content.addView(it)
    }
    private fun paragraph(s: String) = TextView(this).also {
        it.text = s; it.textSize = 15f; it.setPadding(0, 5, 0, 18); content.addView(it)
    }
    private fun button(text: String, action: () -> Unit) = Button(this).also { it.text = text; content.addView(it); it.setOnClickListener { action() } }
    private fun reset() = content.removeAllViews()

    private fun showHome() {
        reset(); title("Tunerquet")
        paragraph("Android TV / Google TV optimization · v0.1 discovery prototype")
        paragraph("Read-only mode. This build does not pair with ADB, inspect installed apps, disable packages, or restart a TV.")
        button("Scan local network for debugging endpoints") { showDiscovery() }
        button("Explore cleanup profiles") { showProfiles() }
        button("Safety and setup instructions") { showInstructions() }
    }
    private fun showDiscovery() {
        discovery?.stop(); reset(); title("Discover TVs")
        paragraph("Connect your phone and TV to the same network. Enable Developer Options and Wireless Debugging on the TV. Discovery is limited to advertised ADB endpoints; a TV may not appear until debugging is enabled.")
        val status = TextView(this).also { content.addView(it); it.text = "Scanning…" }
        endpoints.clear()
        discovery = Discovery(this) { endpoint ->
            runOnUiThread {
                if (endpoints.add(endpoint)) status.text = endpoints.joinToString("\n\n")
            }
        }
        discovery?.start()
        button("Stop scan") { discovery?.stop(); status.append("\n\nScan stopped.") }
        button("Back") { discovery?.stop(); showHome() }
    }
    private fun showProfiles() {
        reset(); title("Cleanup profiles")
        paragraph("Profiles are planning presets, not an authorization to disable packages. Actual cleanup must always be preceded by inventory, dependency analysis and explicit approvals.")
        val profiles = Profile.entries.toTypedArray()
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profiles.map { it.name.replace('_', ' ') })
        content.addView(spinner)
        val details = TextView(this).also { it.setPadding(0, 22, 0, 14); content.addView(it) }
        fun update(i: Int) {
            currentProfile = profiles[i]
            details.text = when (currentProfile) {
                Profile.CONSERVATIVE -> "Conservative: high-confidence optional bloatware candidates only."
                Profile.BALANCED -> "Balanced: additional optional components, with user review."
                Profile.AGGRESSIVE -> "Aggressive: more extensive proposals; never touches protected services."
                Profile.ESSENTIALS_ONLY -> "HIGH RISK: minimum verified functional TV features only. Unknown dependencies must remain enabled."
                Profile.CUSTOM -> "AT YOUR OWN RISK: user-defined package selection; known critical system services remain locked."
            }
        }
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { update(position) }
        }
        update(0)
        button("Back") { showHome() }
    }
    private fun showInstructions() {
        reset(); title("Pairing prerequisites")
        paragraph("On the TV: Settings → System / Device Preferences → About → tap Build repeatedly to unlock Developer Options. Locate Wireless Debugging, enable it, and choose Pair device with pairing code. Menu names vary by manufacturer and Android version.")
        paragraph("A future version will pair using the displayed pairing code and pairing port, securely store device keys, authenticate ADB, then perform a read-only package and memory scan. Never enter the pairing code into an unrelated website.")
        paragraph("Hard safety policy: do not disable Google Play services, Play Store, Settings, System UI, setup, keyboard, Bluetooth, HDMI, TV input, tuner or boot-essential services. Unknown packages stay enabled.")
        button("Back") { showHome() }
    }
    override fun onDestroy() { discovery?.stop(); super.onDestroy() }
}
