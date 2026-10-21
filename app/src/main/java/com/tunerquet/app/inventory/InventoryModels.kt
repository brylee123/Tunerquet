package com.tunerquet.app.inventory

/** Fixed read-only requests. There is no arbitrary command-string entry point. All package state is for user 0. */
enum class InventoryCommand(val command: String, val title: String, val maxBytes: Int = 1024 * 1024) {
    PROPERTIES("getprop", "Device information"),
    PACKAGES("pm list packages -f -U --user 0", "Installed packages"),
    DISABLED("pm list packages -d --user 0", "Disabled packages"),
    SYSTEM("pm list packages -s --user 0", "System packages"),
    PACKAGE_DETAILS("dumpsys package", "Package details", 12 * 1024 * 1024),
    MEMORY("cat /proc/meminfo", "RAM readings"),
    STORAGE("df -k /data", "Data storage"),
    PROCESS_MEMORY("dumpsys meminfo", "Process memory summary", 4 * 1024 * 1024)
}

data class ShellResult(val stdout: String, val stderr: String, val exitCode: Int)

data class InventoryReading(val command: InventoryCommand, val result: ShellResult?, val problem: String? = null)

data class InstalledPackage(
    val name: String, val apkPath: String, val uid: Int, val systemApp: Boolean?, val disabled: Boolean?,
    val versionName: String? = null, val versionCode: String? = null
)
data class RamReading(val totalBytes: Long, val availableBytes: Long?, val freeBytes: Long?, val cachedBytes: Long?)
data class StorageReading(val totalBytes: Long, val usedBytes: Long, val availableBytes: Long, val mountPoint: String = "/data")
data class InventorySnapshot(
    val guid: String, val address: String, val serverKey: String, val startedAt: Long, val completedAt: Long,
    val properties: Map<String, String>, val packages: List<InstalledPackage>, val ram: RamReading?,
    val storage: StorageReading?, val readings: List<InventoryReading>, val warnings: List<String>, val complete: Boolean
)
