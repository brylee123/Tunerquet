package com.tunerquet.app.inventory

import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tunerquet.app.MainActivity
import com.tunerquet.app.adb.AndroidPairedDeviceStore
import com.tunerquet.app.adb.PairedDevice
import org.junit.Assert.*
import org.junit.Test

class AndroidInventoryIntegrationTest {
    /** Checks the parsers against actual Android output, independently of the mock ADB server fixtures. */
    @Test(timeout = 60000) fun parsesLiveAndroidPackageMemoryAndStorageFormats() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val readings = InventoryCommand.entries.map { command ->
            val output = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command.command)).use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    assertTrue("${command.title} exceeded fixture bound", bytes.size().toLong() + count <= command.maxBytes)
                    bytes.write(buffer, 0, count)
                }
                bytes.toString("UTF-8")
            }
            InventoryReading(command, ShellResult(output, "", 0))
        }
        val snapshot = InventoryParser.snapshot("emulator-fixture", "local test", "a".repeat(64), System.currentTimeMillis(), readings)
        assertFalse(snapshot.properties["ro.product.model"].isNullOrBlank())
        assertTrue(snapshot.packages.isNotEmpty())
        val framework = snapshot.packages.first { it.name == "android" }
        assertEquals(true, framework.systemApp)
        assertNotNull(framework.versionCode)
        assertNotNull(snapshot.ram)
        assertNotNull(snapshot.storage)
        assertTrue(snapshot.ram!!.totalBytes > 0)
        assertTrue(snapshot.storage!!.totalBytes > 0)
    }

    @Test fun approvedServerPinPersistsAndCannotBeReplacedByStaleRecord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidPairedDeviceStore(context)
        val original = PairedDevice("pin-persistence-test", "192.168.1.20", 37123, 123)
        try {
            store.save(original)
            val pinned = store.pinServerKey(original, "a".repeat(64), 39555)
            assertEquals(pinned, AndroidPairedDeviceStore(context).list().first { it.guid == original.guid })
            assertThrows(IllegalStateException::class.java) { store.pinServerKey(original, "b".repeat(64), 39555) }
            store.forget(original.guid)
            assertThrows(IllegalStateException::class.java) { store.pinServerKey(pinned, "a".repeat(64), 39555) }
        } finally { store.forget(original.guid) }
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun recreatedConnectionFormDoesNotAutomaticallyAuthorizeRead() {
        val store = AndroidPairedDeviceStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val device = PairedDevice("inventory-ui-test-guid", "192.168.1.20", 37123, 1, "a".repeat(64), 39555)
        store.save(device)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    descendants(activity.window.decorView).filterIsInstance<Button>()
                        .first { it.text.toString() == "Connect and read inventory" }.performClick()
                    val views = descendants(activity.window.decorView)
                    assertTrue(views.filterIsInstance<EditText>().any { it.hint.toString() == "Connection port" })
                    views.filterIsInstance<CheckBox>().single().isChecked = true
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val views = descendants(activity.window.decorView)
                    assertFalse(views.filterIsInstance<CheckBox>().single().isChecked)
                    assertTrue(views.filterIsInstance<Button>().first { it.text.toString() == "Connect and read inventory" }.isEnabled)
                }
            }
        } finally { store.forget(device.guid) }
    }
}
