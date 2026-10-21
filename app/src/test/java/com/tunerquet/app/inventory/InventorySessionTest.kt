package com.tunerquet.app.inventory

import com.tunerquet.app.adb.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class InventorySessionTest {
    private val key = "a".repeat(64)
    private val device = PairedDevice("guid", "192.168.1.20", 37123, 1)
    private val target = ConnectionTarget.parse(device.host, "39555")
    private class Store(device: PairedDevice) : PairedDeviceStore {
        private var record: PairedDevice? = device
        @Synchronized override fun list() = listOfNotNull(record)
        @Synchronized override fun save(device: PairedDevice) { record = device }
        @Synchronized override fun forget(guid: String) { record = null }
    }
    private class Client(val key: String, val body: (InventoryCommand) -> ShellResult) : InventoryClient {
        val commands = CopyOnWriteArrayList<InventoryCommand>()
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var approved = false
        override fun connect(target: ConnectionTarget, expectedServerKey: String?): ConnectionInfo { entered.countDown(); return ConnectionInfo(key, "device::features=shell_v2") }
        override fun approveServerKey() { approved = true }
        override fun read(command: InventoryCommand): ShellResult { check(approved); commands.add(command); return body(command) }
        override fun close() { closed.countDown() }
    }
    private fun result(command: InventoryCommand): ShellResult = ShellResult(when (command) {
        InventoryCommand.PROPERTIES -> "[persist.adb.wifi.guid]: [guid]\n[ro.product.model]: [TV]"
        InventoryCommand.PACKAGES -> "package:/system/app/base.apk=com.vendor.app uid:10001"
        InventoryCommand.SYSTEM -> "package:com.vendor.app"
        InventoryCommand.DISABLED -> ""
        InventoryCommand.PACKAGE_DETAILS -> "Packages:\n  Package [com.vendor.app] (123):\n    versionCode=1 minSdk=26\n    versionName=1.0"
        InventoryCommand.MEMORY -> "MemTotal: 10000 kB\nMemAvailable: 4000 kB\nMemFree: 1000 kB\nCached: 2000 kB"
        InventoryCommand.STORAGE -> "/dev/block/data 10000 4000 6000 40% /data"
        InventoryCommand.PROCESS_MEMORY -> "Total RAM: 10,000K"
    }, "", 0)
    private fun await(latch: CountDownLatch) { assertTrue(latch.await(5, TimeUnit.SECONDS)) }
    private fun session(store: Store, client: Client, changed: (InventoryState) -> Unit = {}, deadline: Long = 120000) =
        InventorySession({ client }, store, Executor { it.run() }, changed, deadlineMillis = deadline)

    @Test fun unapprovedAndUnpairedRequestsNeverCreateTransport() {
        val calls = AtomicInteger()
        val store = Store(device)
        InventorySession({ calls.incrementAndGet(); Client(key, ::result) }, store, Executor { it.run() }, {}).use {
            assertThrows(IllegalArgumentException::class.java) { it.start(device.guid, target, false) }
            assertThrows(IllegalArgumentException::class.java) { it.start("unknown", target, true) }
            assertEquals(0, calls.get())
        }
    }
    @Test fun firstContactDoesNotSendCommandsBeforeKeyApproval() {
        val store = Store(device)
        val client = Client(key, ::result)
        val trust = CountDownLatch(1)
        val done = CountDownLatch(1)
        session(store, client, { if (it.phase == InventoryPhase.TRUST_REQUIRED) trust.countDown(); if (it.snapshot != null) done.countDown() }).use {
            it.start(device.guid, target, true)
            await(trust)
            assertTrue(client.commands.isEmpty())
            assertNull(store.list().single().serverKeySha256)
            it.approveFirstServerKey()
            await(done)
            assertEquals(InventoryPhase.COMPLETE, it.state.phase)
            assertEquals(InventoryCommand.entries.toList(), client.commands.toList())
            assertEquals(key, store.list().single().serverKeySha256)
            assertEquals(39555, store.list().single().connectionPort)
            await(client.closed)
        }
    }
    @Test fun pinnedKeyCanReadButChangedKeyNeverOpensService() {
        val store = Store(device.copy(serverKeySha256 = key))
        val client = Client("b".repeat(64), ::result)
        val done = CountDownLatch(1)
        session(store, client, { if (it.phase == InventoryPhase.FAILED) done.countDown() }).use {
            it.start(device.guid, target, true)
            await(done)
            assertTrue(client.commands.isEmpty())
            assertEquals(key, store.list().single().serverKeySha256)
        }
    }
    @Test fun forgottenRecordCannotBePinnedByWaitingConnection() {
        val store = Store(device)
        val client = Client(key, ::result)
        val trust = CountDownLatch(1)
        val failed = CountDownLatch(1)
        session(store, client, { if (it.phase == InventoryPhase.TRUST_REQUIRED) trust.countDown(); if (it.phase == InventoryPhase.FAILED) failed.countDown() }).use {
            it.start(device.guid, target, true)
            await(trust)
            store.forget(device.guid)
            it.approveFirstServerKey()
            await(failed)
            assertTrue(client.commands.isEmpty())
            assertTrue(store.list().isEmpty())
        }
    }
    @Test fun mismatchedDeviceGuidStopsBeforePackageRequests() {
        val store = Store(device.copy(serverKeySha256 = key))
        val client = Client(key) { result(it).copy(stdout = "[persist.adb.wifi.guid]: [different]") }
        val done = CountDownLatch(1)
        session(store, client, { if (it.phase == InventoryPhase.FAILED) done.countDown() }).use {
            it.start(device.guid, target, true)
            await(done)
            assertEquals(listOf(InventoryCommand.PROPERTIES), client.commands.toList())
            assertNull(it.state.snapshot)
        }
    }
    @Test fun nonzeroExitProducesPartialInventoryAndUnknownPackageState() {
        val store = Store(device.copy(serverKeySha256 = key))
        val client = Client(key) { if (it == InventoryCommand.DISABLED) ShellResult("", "denied", 1) else result(it) }
        val done = CountDownLatch(1)
        session(store, client, { if (it.snapshot != null) done.countDown() }).use {
            it.start(device.guid, target, true)
            await(done)
            assertEquals(InventoryPhase.PARTIAL, it.state.phase)
            assertNull(it.state.snapshot!!.packages.single().disabled)
            assertNotNull(it.state.snapshot!!.ram)
        }
    }
    @Test fun brokenStreamStopsAllSubsequentCommands() {
        val store = Store(device.copy(serverKeySha256 = key))
        val client = Client(key) { if (it == InventoryCommand.PACKAGES) throw IOException("broken") else result(it) }
        val done = CountDownLatch(1)
        session(store, client, { if (it.snapshot != null) done.countDown() }).use {
            it.start(device.guid, target, true)
            await(done)
            assertEquals(listOf(InventoryCommand.PROPERTIES, InventoryCommand.PACKAGES), client.commands.toList())
            assertEquals(InventoryPhase.PARTIAL, it.state.phase)
        }
    }
    @Test fun deadlineAbortsWhileAwaitingFirstKeyApproval() {
        val store = Store(device)
        val client = Client(key, ::result)
        val done = CountDownLatch(1)
        session(store, client, { if (it.phase == InventoryPhase.TIMED_OUT) done.countDown() }, deadline = 200).use {
            it.start(device.guid, target, true)
            await(done)
            await(client.closed)
            assertTrue(client.commands.isEmpty())
            assertNull(store.list().single().serverKeySha256)
            assertThrows(IllegalStateException::class.java) { it.approveFirstServerKey() }
        }
    }
    @Test fun cancellationDiscardsLateReadAndPreventsNextCommand() {
        val store = Store(device.copy(serverKeySha256 = key))
        val entered = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        lateinit var client: Client
        client = Client(key) { entered.countDown(); await(client.closed); result(it) }
        InventorySession({ client }, store, Executor { it.run() }, {}, worker = worker).use {
            it.start(device.guid, target, true)
            await(entered)
            it.cancel()
            worker.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(InventoryPhase.CANCELLED, it.state.phase)
            assertNull(it.state.snapshot)
            assertEquals(listOf(InventoryCommand.PROPERTIES), client.commands.toList())
        }
    }
}
