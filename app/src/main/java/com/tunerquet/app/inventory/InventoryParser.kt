package com.tunerquet.app.inventory

/** Parses device output conservatively. Missing/invalid readings remain unknown, never zero. */
object InventoryParser {
    private val packageName = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+|android")
    fun properties(output: String): Map<String, String> = output.lineSequence().mapNotNull {
        Regex("^\\[([^]]+)]: \\[(.*)]$").matchEntire(it.trim())?.destructured?.let { (name, value) -> name to value }
    }.toMap()

    fun packageNames(output: String): Set<String> = output.lineSequence().map(String::trim).filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").substringBefore(' ') }.filter(packageName::matches).toSet()

    fun packages(output: String): List<InstalledPackage> = output.lineSequence().mapNotNull { raw ->
        val line = raw.trim().removePrefix("package:")
        val uidMatch = Regex(" uid:(\\d+)$").find(line) ?: return@mapNotNull null
        val uid = uidMatch.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val pathAndName = line.substring(0, uidMatch.range.first)
        val split = pathAndName.lastIndexOf('=')
        if (split <= 0) return@mapNotNull null
        val path = pathAndName.substring(0, split)
        val name = pathAndName.substring(split + 1)
        if (!raw.trim().startsWith("package:") || !path.startsWith('/') || !packageName.matches(name)) return@mapNotNull null
        InstalledPackage(name, path, uid, null, null)
    }.distinctBy { it.name }.sortedBy { it.name }.toList()

    fun ram(output: String): RamReading? {
        val values = output.lineSequence().mapNotNull { line ->
            Regex("^([A-Za-z_]+):\\s+(\\d+) kB\\s*$").matchEntire(line)?.destructured?.let { (name, amount) ->
                amount.toLongOrNull()?.takeIf { it <= Long.MAX_VALUE / 1024 }?.let { name to it * 1024 }
            }
        }.toMap()
        val total = values["MemTotal"]?.takeIf { it > 0 } ?: return null
        fun reading(name: String) = values[name]?.takeIf { it in 0..total }
        return RamReading(total, reading("MemAvailable"), reading("MemFree"), reading("Cached"))
    }

    fun storage(output: String): StorageReading? {
        // df may wrap a long filesystem name onto its own line. Select the /data row, not another mount.
        val row = output.lineSequence().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.lastOrNull() == "/data" || it.lastOrNull()?.startsWith("/data/") == true } ?: return null
        if (row.size < 5) return null
        val tail = row.takeLast(5)
        val values = tail.take(3).map { it.toLongOrNull()?.takeIf { value -> value in 0..Long.MAX_VALUE / 1024 } ?: return null }
        val (total, used, available) = values
        if (total == 0L || used > total || available > total) return null
        return StorageReading(total * 1024, used * 1024, available * 1024, row.last())
    }

    fun versions(output: String): Map<String, Pair<String?, String?>> {
        val result = linkedMapOf<String, Pair<String?, String?>>()
        var inPackages = false
        var current: String? = null
        for (line in output.lineSequence()) {
            if (line == "Packages:") { inPackages = true; continue }
            if (!inPackages) continue
            if (line.isNotBlank() && !line.startsWith(' ')) { inPackages = false; current = null; continue }
            val header = Regex("^  Package \\[([^]]+)] .*\\s*$").matchEntire(line)
            if (header != null) { current = header.groupValues[1]; result.putIfAbsent(current, null to null); continue }
            val name = current ?: continue
            val old = result[name] ?: (null to null)
            val trimmed = line.trim()
            if (trimmed.startsWith("versionName=")) result[name] = trimmed.removePrefix("versionName=") to old.second
            if (trimmed.startsWith("versionCode=")) result[name] = old.first to trimmed.removePrefix("versionCode=").substringBefore(' ')
        }
        return result
    }

    fun snapshot(guid: String, address: String, key: String, startedAt: Long, readings: List<InventoryReading>): InventorySnapshot {
        val warnings = readings.mapNotNull { reading -> reading.problem?.let { "${reading.command.title}: $it" } }.toMutableList()
        fun output(command: InventoryCommand): String? = readings.firstOrNull { it.command == command && it.problem == null }?.result?.stdout
        val properties = output(InventoryCommand.PROPERTIES)?.let(::properties) ?: emptyMap()
        if (properties["persist.adb.wifi.guid"].isNullOrBlank()) warnings.add("The TV did not expose its pairing GUID; this snapshot is bound to the approved server key.")
        val packageOutput = output(InventoryCommand.PACKAGES)
        val base = packageOutput?.let(::packages) ?: emptyList()
        if (packageOutput != null && (base.isEmpty() || packageOutput.lineSequence().count { it.isNotBlank() } != base.size)) warnings.add("Installed package output was empty, malformed or duplicated; the package list may be incomplete.")
        fun checkedNames(command: InventoryCommand): Set<String>? {
            val value = output(command) ?: return null
            val lines = value.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val names = packageNames(value)
            if (lines.size != names.size || lines.any { !it.startsWith("package:") || !packageName.matches(it.removePrefix("package:")) } || (command == InventoryCommand.SYSTEM && names.isEmpty())) {
                warnings.add("${command.title}: invalid package-state output; state is unknown.")
                return null
            }
            return names
        }
        val disabled = checkedNames(InventoryCommand.DISABLED)
        val system = checkedNames(InventoryCommand.SYSTEM)
        val details = output(InventoryCommand.PACKAGE_DETAILS)?.let(::versions) ?: emptyMap()
        if (base.any { it.name !in details }) warnings.add("Some package details were unavailable; versions may be unknown.")
        if ((disabled.orEmpty() + system.orEmpty()).any { name -> base.none { it.name == name } }) warnings.add("Package lists changed during collection or contain unmatched entries.")
        val packages = base.map { pkg -> pkg.copy(systemApp = system?.contains(pkg.name), disabled = disabled?.contains(pkg.name),
            versionName = details[pkg.name]?.first, versionCode = details[pkg.name]?.second) }
        val ram = output(InventoryCommand.MEMORY)?.let(::ram)
        if (ram == null) warnings.add("RAM readings are unavailable or invalid.")
        else if (ram.availableBytes == null) warnings.add("Available RAM is not exposed; free and cached RAM are not substitutes for it.")
        val storage = output(InventoryCommand.STORAGE)?.let(::storage)
        if (storage == null) warnings.add("Data-storage readings are unavailable or invalid.")
        val allRead = readings.size == InventoryCommand.entries.size && readings.all { it.problem == null }
        return InventorySnapshot(guid, address, key, startedAt, System.currentTimeMillis(), properties, packages, ram, storage,
            readings.toList(), warnings, allRead && warnings.isEmpty())
    }
}
