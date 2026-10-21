package com.tunerquet.app.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PairingSessionTest {
    private val target = PairingTarget.parse("192.168.1.20", "37123")
    private class Store : PairedDeviceStore {
        val saved = CopyOnWriteArrayList<PairedDevice>()
        var fail = false
        override fun save(device: PairedDevice) { if (fail) throw IOException("disk full"); saved.add(device) }
        override fun list() = saved.toList()
        override fun forget(guid: String) { saved.removeAll { it.guid == guid } }
    }
    private class Client(private val action: (ByteArray) -> String) : PairingClient {
        val closed = CountDownLatch(1)
        override fun pair(target: PairingTarget, code: ByteArray) = action(code)
        override fun close() { closed.countDown() }
    }
    private fun await(latch: CountDownLatch) { assertTrue("Timed out waiting for worker", latch.await(5, TimeUnit.SECONDS)) }
    private fun session(store: Store, client: Client, terminal: CountDownLatch, timeout: Long = 30_000, worker: ExecutorService = Executors.newSingleThreadExecutor()) =
        PairingSession({ client }, store, Executor { it.run() }, {
            if (it.phase != PairingPhase.PAIRING) terminal.countDown()
        }, worker = worker, deadlineMillis = timeout)

    @Test fun recordsOnlyConfirmedPairingAndWipesCode() {
        val store = Store()
        var received: ByteArray? = null
        val client = Client { received = it; assertEquals("123456", it.toString(Charsets.US_ASCII)); "adb-guid-123" }
        val done = CountDownLatch(1)
        session(store, client, done).use { session ->
            val code = "123456".toCharArray()
            session.start(target, code, true)
            assertTrue(code.all { it == '\u0000' })
            await(done)
            await(client.closed)
            assertEquals(PairingPhase.PAIRED, session.state.phase)
            assertEquals("adb-guid-123", store.saved.single().guid)
            assertEquals(target.port, store.saved.single().pairingPort)
            assertTrue(received!!.all { it == 0.toByte() })
        }
    }
    @Test fun requiresExplicitApprovalWithoutCreatingTransport() {
        val calls = AtomicInteger()
        val store = Store()
        PairingSession({ calls.incrementAndGet(); Client { "guid" } }, store, Executor { it.run() }, {}).use {
            val code = "123456".toCharArray()
            assertThrows(IllegalArgumentException::class.java) { it.start(target, code, false) }
            assertTrue(code.all { c -> c == '\u0000' })
            assertEquals(0, calls.get())
            assertTrue(store.saved.isEmpty())
        }
    }
    @Test fun rejectsMalformedOrNonAsciiCodes() {
        val calls = AtomicInteger()
        PairingSession({ calls.incrementAndGet(); Client { "guid" } }, Store(), Executor { it.run() }, {}).use { session ->
            listOf("", "12345", "1234567", "123 56", "abcdef", "１２３４５６").forEach { invalid ->
                val chars = invalid.toCharArray()
                assertThrows(IllegalArgumentException::class.java) { session.start(target, chars, true) }
                assertTrue(chars.all { it == '\u0000' })
            }
            assertEquals(0, calls.get())
        }
    }
    @Test fun failureDoesNotSaveOrLeakExceptionDetails() {
        val done = CountDownLatch(1)
        val store = Store()
        session(store, Client { throw IOException("secret 123456") }, done).use {
            it.start(target, "123456".toCharArray(), true)
            await(done)
            assertEquals(PairingPhase.FAILED, it.state.phase)
            assertFalse(it.state.message.contains("123456"))
            assertTrue(store.saved.isEmpty())
        }
    }
    @Test fun storageFailureIsNotReportedAsPaired() {
        val done = CountDownLatch(1)
        val store = Store().apply { fail = true }
        session(store, Client { "guid" }, done).use {
            it.start(target, "123456".toCharArray(), true)
            await(done)
            assertEquals(PairingPhase.FAILED, it.state.phase)
        }
    }
    @Test fun cancellationRejectsLateSuccessAndClosesTransport() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val done = CountDownLatch(1)
        val store = Store()
        val client = Client { entered.countDown(); await(release); returned.countDown(); "late-guid" }
        val worker = Executors.newSingleThreadExecutor()
        session(store, client, done, worker = worker).use {
            it.start(target, "123456".toCharArray(), true)
            await(entered)
            it.cancel()
            await(client.closed)
            release.countDown()
            await(returned)
            worker.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(PairingPhase.CANCELLED, it.state.phase)
            assertTrue(store.saved.isEmpty())
        }
    }
    @Test fun deadlineClosesTransportAndDoesNotSave() {
        val entered = CountDownLatch(1)
        val done = CountDownLatch(1)
        val store = Store()
        lateinit var client: Client
        client = Client { entered.countDown(); await(client.closed); "late-guid" }
        session(store, client, done, 100).use {
            it.start(target, "123456".toCharArray(), true)
            await(entered)
            await(done)
            await(client.closed)
            assertEquals(PairingPhase.TIMED_OUT, it.state.phase)
            assertTrue(store.saved.isEmpty())
        }
    }
    @Test fun rejectsConcurrentAttempts() {
        val entered = CountDownLatch(1)
        val done = CountDownLatch(1)
        lateinit var client: Client
        client = Client { entered.countDown(); await(client.closed); "guid" }
        session(Store(), client, done).use {
            it.start(target, "123456".toCharArray(), true)
            await(entered)
            val second = "654321".toCharArray()
            assertThrows(IllegalStateException::class.java) { it.start(target, second, true) }
            assertTrue(second.all { c -> c == '\u0000' })
        }
    }
    @Test fun resetStartsANewFormWithoutClaimingOldSuccess() {
        val done = CountDownLatch(1)
        val store = Store()
        session(store, Client { "saved-guid" }, done).use {
            it.start(target, "123456".toCharArray(), true)
            await(done)
            it.reset()
            assertEquals(PairingPhase.IDLE, it.state.phase)
            assertEquals("saved-guid", store.saved.single().guid)
        }
    }
    @Test fun retryAfterCancellationCannotSaveOldAttempt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val success = CountDownLatch(1)
        val store = Store()
        val worker = Executors.newSingleThreadExecutor()
        val clients = ArrayDeque(listOf(
            Client { entered.countDown(); await(release); "old-guid" },
            Client { "new-guid" }))
        PairingSession({ clients.removeFirst() }, store, Executor { it.run() }, {
            if (it.phase == PairingPhase.PAIRED) success.countDown()
        }, worker = worker).use {
            it.start(target, "123456".toCharArray(), true)
            await(entered)
            it.cancel()
            it.start(target, "654321".toCharArray(), true)
            release.countDown()
            await(success)
            worker.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("new-guid"), store.saved.map { device -> device.guid })
            assertEquals(PairingPhase.PAIRED, it.state.phase)
        }
    }
    @Test fun malformedIdentityIsNotSaved() {
        val done = CountDownLatch(1)
        val store = Store()
        session(store, Client { "bad identity\n" }, done).use {
            it.start(target, "123456".toCharArray(), true)
            await(done)
            assertEquals(PairingPhase.FAILED, it.state.phase)
            assertTrue(store.saved.isEmpty())
        }
    }
}
