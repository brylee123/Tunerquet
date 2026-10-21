package com.tunerquet.app.inventory

import org.junit.Assert.*
import org.junit.Test

class InventoryParserTest {
    @Test fun parsesApkPathsWithEqualsAndSharedUids() {
        val packages = InventoryParser.packages("package:/data/app/~~abc==/base.apk=com.vendor.app uid:10123\npackage:/system/framework/framework-res.apk=android uid:1000\nmalformed")
        assertEquals(listOf("android", "com.vendor.app"), packages.map { it.name })
        assertEquals("/data/app/~~abc==/base.apk", packages[1].apkPath)
        assertNull(packages[1].systemApp)
        assertNull(packages[1].disabled)
    }
    @Test fun ignoresInvalidPackageRows() {
        assertTrue(InventoryParser.packages("package:/path=a;reboot uid:1\npackage:/path=com.foo uid:999999999999\npackage:com.foo\npackage:relative=com.foo uid:1").isEmpty())
    }
    @Test fun memorySeparatesAvailableFreeAndCached() {
        val reading = InventoryParser.ram("MemTotal:        10000 kB\nMemAvailable:     4000 kB\nMemFree:          1000 kB\nCached:           2000 kB\n")!!
        assertEquals(10000L * 1024, reading.totalBytes)
        assertEquals(4000L * 1024, reading.availableBytes)
        assertEquals(1000L * 1024, reading.freeBytes)
        assertEquals(2000L * 1024, reading.cachedBytes)
    }
    @Test fun missingMemoryIsNotZeroAndDoesNotSubstituteFreeForAvailable() {
        assertNull(InventoryParser.ram("Permission denied"))
        val reading = InventoryParser.ram("MemTotal: 1000 kB\nMemFree: 100 kB\nCached: 200 kB")!!
        assertNull(reading.availableBytes)
        assertNull(InventoryParser.ram("MemTotal: 99999999999999999 kB"))
        assertNull(InventoryParser.ram("MemTotal: 0 kB"))
    }
    @Test fun readsDataFilesystemAndWrappedDfRows() {
        val regular = InventoryParser.storage("Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/root 999 99 900 10% /\n/dev/block/dm-1 10000 4000 6000 40% /data")!!
        val wrapped = InventoryParser.storage("Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/very-long-name\n10000 4000 6000 40% /data")!!
        assertEquals(regular, wrapped)
        assertEquals(10000L * 1024, regular.totalBytes)
        assertNull(InventoryParser.storage("/dev/root 1000 100 900 10% /"))
        assertNull(InventoryParser.storage("/dev/dm 1 2 3 1% /data"))
    }
    @Test fun acceptsDataBindMountReturnedByRealAndroidDf() {
        val reading = InventoryParser.storage("Filesystem      1K-blocks    Used Available Use% Mounted on\n/dev/block/dm-6   6082144 1961580   3978352  34% /data/user/0")!!
        assertEquals("/data/user/0", reading.mountPoint)
        assertEquals(6082144L * 1024, reading.totalBytes)
        assertNull(InventoryParser.storage("/dev/root 1000 100 900 10% /database"))
    }    @Test fun parsesOnlyPackageVersionSection() {
        val versions = InventoryParser.versions("Queries:\n  Package [com.fake] (abc):\n    versionName=fake\nPackages:\n  Package [com.vendor.app] (123):\n    versionCode=123 minSdk=26 targetSdk=35\n    versionName=1.2.3\nDexopt state:\n  Package [com.fake] (abc):\n    versionName=bad")
        assertEquals(mapOf("com.vendor.app" to ("1.2.3" to "123")), versions)
    }
    @Test fun failedPackageStateRemainsUnknown() {
        val readings = listOf(
            InventoryReading(InventoryCommand.PACKAGES, ShellResult("package:/path=com.vendor.app uid:10001", "", 0)),
            InventoryReading(InventoryCommand.DISABLED, ShellResult("Permission Denial", "", 0)),
            InventoryReading(InventoryCommand.SYSTEM, null, "failed"))
        val snapshot = InventoryParser.snapshot("guid", "host", "key", 1, readings)
        assertFalse(snapshot.complete)
        assertNull(snapshot.packages.single().systemApp)
        assertNull(snapshot.packages.single().disabled)
        assertNull(snapshot.ram)
        assertTrue(snapshot.warnings.isNotEmpty())
    }
    @Test fun propertiesPreserveBracketedValues() {
        assertEquals(mapOf("ro.product.model" to "TV [2026]", "persist.adb.wifi.guid" to "guid"),
            InventoryParser.properties("[ro.product.model]: [TV [2026]]\n[persist.adb.wifi.guid]: [guid]\ninvalid"))
    }
}
