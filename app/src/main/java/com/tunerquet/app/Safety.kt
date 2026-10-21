package com.tunerquet.app

enum class Risk { PROTECTED, UNKNOWN, LOW, REVIEW }
enum class Profile { CONSERVATIVE, BALANCED, AGGRESSIVE, ESSENTIALS_ONLY, CUSTOM }
data class TvPackage(val packageName: String, val label: String, val systemApp: Boolean, val risk: Risk)

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
    fun classify(pkg: String): Risk {
        val normalized = pkg.lowercase()
        if (normalized in exact || criticalTokens.any { it in normalized }) return Risk.PROTECTED
        return Risk.UNKNOWN // No package is automatically declared safe without device-specific evidence.
    }
    fun allowDisable(pkg: TvPackage, approved: Boolean): Boolean =
        approved && pkg.systemApp && pkg.risk == Risk.LOW && classify(pkg.packageName) != Risk.PROTECTED
}
