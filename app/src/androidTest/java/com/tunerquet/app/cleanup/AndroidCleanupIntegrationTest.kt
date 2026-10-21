package com.tunerquet.app.cleanup

import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tunerquet.app.MainActivity
import com.tunerquet.app.adb.*
import com.tunerquet.app.inventory.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AndroidCleanupIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    @Test fun parsesLiveUserRolesAndExactEnabledState() {
        assertEquals("0", shell(CleanupRead.CURRENT_USER.command).trim())
        assertTrue(CleanupPolicy.components(shell(CleanupRead.LAUNCHERS.command)).isNotEmpty())
        assertTrue(CleanupPolicy.components(shell(CleanupRead.INPUT_METHODS.command)).isNotEmpty())
        val app = InventoryParser.packages(shell(InventoryCommand.PACKAGES.command)).single { it.name == context.packageName }
        val evidence = CleanupPolicy.evidence(app, shell("dumpsys package ${context.packageName}"))
        assertTrue(evidence.enabledState in 0..1)
        assertNotNull(evidence.pkg.versionCode)
        val systems = CleanupPolicy.names(shell(InventoryCommand.SYSTEM.command), false)
        assertTrue("android" in systems)
    }
    @Test fun atomicJournalSurvivesReopenAndDoesNotSilentlyDiscardCorruption() {
        // A separate test directory avoids touching the real journal.
        val testContext = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getNoBackupFilesDir() = File(context.cacheDir, "cleanup-journal-test").apply { mkdirs() }
        }
        val file = File(testContext.noBackupFilesDir, "cleanup-journal.json")
        file.delete(); File(file.path + ".bak").delete()
        try {
            val pkg = InstalledPackage("com.netflix.ninja", "/data/app/test/base.apk", 10001, false, false, "1.0", "1")
            val record = ChangeRecord("test", "guid", "a".repeat(64), "192.168.1.20:1234", PackageEvidence(pkg, 0), 1, ChangeStatus.PENDING)
            AndroidCleanupJournal(testContext).put(record)
            assertEquals(record, AndroidCleanupJournal(testContext).list().single())
            AndroidCleanupJournal(testContext).put(record.copy(status = ChangeStatus.DISABLED))
            assertEquals(ChangeStatus.DISABLED, AndroidCleanupJournal(testContext).list().single().status)
            file.writeText("corrupt")
            assertThrows(Exception::class.java) { AndroidCleanupJournal(testContext).list() }
        } finally { file.delete(); File(file.path + ".bak").delete() }
    }
    @Test(timeout = 60000) fun realDisableAndRestoreRoundTripOnInertEmulatorFixture() {
        val name = "com.netflix.ninja"
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = try {
            automation.adoptShellPermissionIdentity("android.permission.QUERY_ALL_PACKAGES")
            val fixture = runCatching { context.packageManager.getApplicationInfo(name, android.content.pm.PackageManager.GET_META_DATA) }.getOrNull()
            if (fixture?.metaData?.getBoolean("com.tunerquet.cleanup.fixture") == true) {
                require(context.packageManager.getApplicationLabel(fixture).toString() == "Tunerquet cleanup TEST FIXTURE")
                require(context.packageManager.checkSignatures(context.packageName, name) == android.content.pm.PackageManager.SIGNATURE_MATCH)
            }
            fixture
        } finally { automation.dropShellPermissionIdentity() }
        org.junit.Assume.assumeTrue(info?.metaData?.getBoolean("com.tunerquet.cleanup.fixture") == true)
        val key = "a".repeat(64)
        val guid = InventoryParser.properties(shell("getprop"))["persist.adb.wifi.guid"] ?: "dedicated-fixture"
        val device = PairedDevice(guid, "127.0.0.1", 1234, 1, key, 4321)
        val store = object : PairedDeviceStore {
            override fun list() = listOf(device)
            override fun save(device: PairedDevice) = error("No pairing changes allowed")
            override fun forget(guid: String) = error("No pairing changes allowed")
        }
        val testContext = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getNoBackupFilesDir() = File(context.cacheDir, "cleanup-roundtrip-test").apply { mkdirs() }
        }
        val journalFile = File(testContext.noBackupFilesDir, "cleanup-journal.json")
        journalFile.delete(); File(journalFile.path + ".bak").delete()
        val journal = AndroidCleanupJournal(testContext)
        fun client() = object : CleanupClient {
            override fun connect(target: ConnectionTarget, expectedServerKey: String?) = ConnectionInfo(key, "device::")
            override fun approveServerKey() = Unit
            override fun read(command: InventoryCommand) = ShellResult(shell(command.command), "", 0)
            override fun inspect(read: CleanupRead) = ShellResult(shell(read.command), "", 0)
            override fun packageDetails(name: String): ShellResult {
                CleanupPolicy.validateName(name)
                return ShellResult(shell("dumpsys package $name"), "", 0)
            }
            override fun change(name: String, action: PackageChange): ShellResult {
                require(name == "com.netflix.ninja")
                assertTrue(journal.list().any { it.status == ChangeStatus.PENDING || it.status == ChangeStatus.RESTORING })
                return ShellResult(shell("pm ${action.verb} --user 0 $name"), "", 0)
            }
            override fun close() = Unit
        }
        fun await(s: CleanupSession, phase: CleanupPhase) {
            val limit = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
            while (s.state.phase != phase && System.nanoTime() < limit) {
                if (s.state.phase == CleanupPhase.FAILED) fail(s.state.message)
                Thread.sleep(10)
            }
            assertEquals(s.state.message, phase, s.state.phase)
        }
        // Both commands below are limited to our signed, inert, emulator-only fixture.
        shell("pm default-state --user 0 $name")
        shell("run-as $name touch cleanup-retained-data")
        assertEquals("cleanup-retained-data", shell("run-as $name ls cleanup-retained-data").trim())
        val initial = InventoryParser.packages(shell(InventoryCommand.PACKAGES.command)).single { it.name == name }
        try {
            CleanupSession(::client, store, journal, java.util.concurrent.Executor { it.run() }, {}).use { s ->
                s.prepare(guid, ConnectionTarget.parse("127.0.0.1", "4321"), true)
                await(s, CleanupPhase.REVIEW)
                assertTrue(s.state.candidates.any { it.evidence.pkg.name == name })
                s.apply(setOf(name), true); await(s, CleanupPhase.COMPLETE)
            }
            assertEquals(ChangeStatus.DISABLED, journal.list().single().status)
            assertEquals(3, CleanupPolicy.evidence(initial, shell("dumpsys package $name")).enabledState)
            assertTrue(shell("pm path $name").contains("package:/data/app/"))
            assertEquals("cleanup-retained-data", shell("run-as $name ls cleanup-retained-data").trim())
            CleanupSession(::client, store, journal, java.util.concurrent.Executor { it.run() }, {}).use { s ->
                s.prepare(guid, ConnectionTarget.parse("127.0.0.1", "4321"), true)
                await(s, CleanupPhase.REVIEW)
                s.restore(journal.list().single().id, true); await(s, CleanupPhase.COMPLETE)
            }
            assertEquals(ChangeStatus.RESTORED, journal.list().single().status)
            assertEquals(0, CleanupPolicy.evidence(initial, shell("dumpsys package $name")).enabledState)
        } finally {
            shell("pm default-state --user 0 $name")
            shell("run-as $name rm -f cleanup-retained-data")
            journalFile.delete(); File(journalFile.path + ".bak").delete()
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    @Test fun cleanupRecreationNeverRestoresConnectionApproval() {
        val store = AndroidPairedDeviceStore(context)
        val device = PairedDevice("cleanup-ui-test", "192.168.1.20", 1234, 1, "a".repeat(64), 4321)
        store.save(device)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    descendants(activity.window.decorView).filterIsInstance<Button>()
                        .first { it.text.toString() == "Disable apps and restore" }.performClick()
                    val views = descendants(activity.window.decorView)
                    views.filterIsInstance<CheckBox>().single().isChecked = true
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val views = descendants(activity.window.decorView)
                    assertFalse(views.filterIsInstance<CheckBox>().single().isChecked)
                    assertTrue(views.filterIsInstance<Button>().first { it.text.toString() == "Prepare fresh cleanup check" }.isEnabled)
                }
            }
        } finally { store.forget(device.guid) }
    }
}
