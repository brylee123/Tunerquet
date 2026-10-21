package com.tunerquet.app.cleanup

import com.tunerquet.app.Safety
import com.tunerquet.app.Risk
import com.tunerquet.app.adb.InventoryClient
import com.tunerquet.app.inventory.InventoryParser
import com.tunerquet.app.inventory.InstalledPackage
import com.tunerquet.app.inventory.ShellResult

enum class CleanupRead(val command: String) {
    CURRENT_USER("am get-current-user"),
    LAUNCHERS("cmd package query-activities --components --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME"),
    INPUT_METHODS("ime list -s --user 0")
}
enum class PackageChange(val verb: String) { DISABLE("disable-user"), RESTORE_DEFAULT("default-state"), RESTORE_ENABLED("enable") }

/** No free-form shell commands. Every package argument is validated before reaching the transport. */
interface CleanupClient : InventoryClient {
    fun inspect(read: CleanupRead): ShellResult
    fun packageDetails(name: String): ShellResult
    fun change(name: String, action: PackageChange): ShellResult
}

data class PackageEvidence(val pkg: InstalledPackage, val enabledState: Int)
data class CleanupCandidate(val evidence: PackageEvidence, val label: String, val consequence: String)
enum class ChangeStatus { PENDING, DISABLED, RESTORING, RESTORED }
data class ChangeRecord(
    val id: String, val guid: String, val serverKey: String, val address: String,
    val evidence: PackageEvidence, val createdAt: Long, val status: ChangeStatus
)
interface CleanupJournal {
    fun list(): List<ChangeRecord>
    fun put(record: ChangeRecord)
}

object CleanupPolicy {
    // Deliberately small: user-installed standalone entertainment clients, never OEM/system packages.
    val optionalMedia = mapOf(
        "com.netflix.mediaclient" to "Netflix",
        "com.netflix.ninja" to "Netflix for TV",
        "com.disney.disneyplus" to "Disney+",
        "com.amazon.avod.thirdpartyclient" to "Prime Video",
        "com.amazon.amazonvideo.livingroom" to "Prime Video for TV",
        "com.hulu.plus" to "Hulu",
        "com.spotify.tv.android" to "Spotify for TV",
        "com.google.android.youtube.tv" to "YouTube for TV"
    )
    fun validateName(name: String) {
        require(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+").matches(name)) { "Invalid package identifier" }
    }
    fun checked(result: ShellResult): String {
        check(result.exitCode == 0 && result.stderr.isBlank()) { "Device rejected a required cleanup check" }
        check(!Regex("(?im)^(Error:|Failure|Permission Denial:|Security exception:|/system/bin/sh:)").containsMatchIn(result.stdout.trim())) { "Device returned an error" }
        return result.stdout
    }
    fun names(output: String, allowEmpty: Boolean): Set<String> {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val names = InventoryParser.packageNames(output)
        check((allowEmpty || names.isNotEmpty()) && lines.size == names.size &&
            lines.all { it.startsWith("package:") && runCatching { if (it.removePrefix("package:") != "android") validateName(it.removePrefix("package:")); true }.getOrDefault(false) }) { "Incomplete package-state list" }
        return names
    }
    fun components(output: String): Set<String> {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        check(lines.isNotEmpty()) { "Device roles unavailable" }
        return lines.map { line ->
            val parts = line.split('/')
            check(parts.size == 2 && parts[1].matches(Regex("[a-zA-Z0-9_.$]+"))) { "Unrecognized device role" }
            validateName(parts[0])
            parts[0]
        }.toSet()
    }
    fun evidence(pkg: InstalledPackage, output: String): PackageEvidence {
        validateName(pkg.name)
        val headers = Regex("(?m)^  Package \\[([^]]+)] .*").findAll(output).toList()
        check(headers.size == 1 && headers.single().groupValues[1] == pkg.name) { "Package identity unavailable" }
        val users = Regex("(?m)^    User 0: ([^\\r\\n]+)").findAll(output).toList()
        check(users.size == 1) { "User-0 state unavailable" }
        val user = users.single().groupValues[1]
        check(Regex("(?:^| )installed=true(?: |$)").containsMatchIn(user) &&
            Regex("(?:^| )hidden=false(?: |$)").containsMatchIn(user) &&
            Regex("(?:^| )suspended=false(?: |$)").containsMatchIn(user)) { "App is not normally installed for user 0" }
        val state = Regex("(?:^| )enabled=([0-4])(?: |$)").find(user)?.groupValues?.get(1)?.toInt() ?: error("Unknown enabled state")
        val versions = InventoryParser.versions(output)[pkg.name] ?: error("Missing package version")
        check(!versions.first.isNullOrBlank() && !versions.second.isNullOrBlank()) { "Unknown package version" }
        check(!Regex("(?i)(sharedUser=|sharedUserId=|libraryNames=|staticSharedLibName=|sdkLibName=|requiredForAllUsers=true)").containsMatchIn(output)) { "Shared or required package stays enabled" }
        return PackageEvidence(pkg.copy(versionName = versions.first, versionCode = versions.second), state)
    }
    fun candidate(evidence: PackageEvidence, all: List<InstalledPackage>, roles: Set<String>): CleanupCandidate? {
        val pkg = evidence.pkg
        val label = optionalMedia[pkg.name] ?: return null
        if (Safety.assess(pkg.name).risk == Risk.PROTECTED || pkg.name in roles ||
            pkg.systemApp != false || pkg.disabled != false || evidence.enabledState !in 0..1 ||
            pkg.uid < 10000 || all.count { it.uid == pkg.uid } != 1 || !pkg.apkPath.startsWith("/data/app/")) return null
        return CleanupCandidate(evidence, label, "Disables $label for user 0. Playback, background work and app integrations stop until restored. App data stays installed.")
    }
}
