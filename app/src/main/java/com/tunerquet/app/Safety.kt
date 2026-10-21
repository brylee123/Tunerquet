package com.tunerquet.app

enum class Risk { PROTECTED, UNKNOWN, LOW, REVIEW }
enum class Profile { CONSERVATIVE, BALANCED, AGGRESSIVE, ESSENTIALS_ONLY, CUSTOM }
data class TvPackage(val packageName: String, val label: String, val systemApp: Boolean, val risk: Risk)

/** Evidence must come from an authenticated inventory, never from a profile or user risk label. */
data class DeviceSafetyContext(
    val inventoryVerified: Boolean = false,
    val activeLaunchers: Set<String> = emptySet(),
    val inputMethods: Set<String> = emptySet(),
    val requiredPackages: Set<String> = emptySet(),
    val dependencyPackages: Set<String> = emptySet(),
    val verifiedOptionalPackages: Set<String> = emptySet()
)

data class SafetyAssessment(val risk: Risk, val reason: String)

/** Fail-closed starting point; the eventual release needs vendor-specific dependency inspection too. */
object Safety {
    private val exact = setOf(
        "com.google.android.gms", "com.android.vending", "com.android.settings",
        "com.android.systemui", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
        "com.android.inputdevices", "com.android.bluetooth", "com.android.providers.settings",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.tv.settings", "com.google.android.tv.settings"
    )
    private val criticalTokens = listOf(
        "setupwizard", "systemui", "settings", "launcher", "inputmethod", "keyboard",
        "bluetooth", "hdmi", "tvinput", "tuner", "permissioncontroller", "packageinstaller",
        "connectivity", "networkstack", "wifi", "framework", "telephony", "providers", "adbd"
    )
    private val packagePattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")

    fun assess(pkg: String, context: DeviceSafetyContext = DeviceSafetyContext()): SafetyAssessment {
        if (!packagePattern.matches(pkg)) {
            return SafetyAssessment(Risk.PROTECTED, "Invalid package identifier; no action is allowed.")
        }
        val normalized = pkg.lowercase()
        if (normalized in exact || criticalTokens.any { it in normalized }) {
            return SafetyAssessment(Risk.PROTECTED, "Core system or TV component; cleanup is locked.")
        }
        val role = when (pkg) {
            in context.activeLaunchers -> "An active launcher"
            in context.inputMethods -> "An installed input method"
            in context.requiredPackages -> "A required device component"
            in context.dependencyPackages -> "A dependency of a required component"
            else -> null
        }
        if (role != null) return SafetyAssessment(Risk.PROTECTED, "$role on this device; cleanup is locked.")
        if (!context.inventoryVerified) {
            return SafetyAssessment(Risk.UNKNOWN, "Device inventory and dependencies have not been verified; leave enabled.")
        }
        if (pkg in context.verifiedOptionalPackages) {
            return SafetyAssessment(Risk.LOW, "Verified optional for this device; explicit approval is still required.")
        }
        return SafetyAssessment(Risk.UNKNOWN, "No device-specific evidence that this package is optional; leave enabled.")
    }

    fun classify(pkg: String): Risk = assess(pkg).risk

    fun allowDisable(
        pkg: TvPackage,
        approved: Boolean,
        context: DeviceSafetyContext = DeviceSafetyContext()
    ): Boolean = approved && pkg.systemApp && pkg.risk == Risk.LOW && assess(pkg.packageName, context).risk == Risk.LOW
}
