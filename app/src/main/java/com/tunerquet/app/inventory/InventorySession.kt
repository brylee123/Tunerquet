package com.tunerquet.app.inventory

import com.tunerquet.app.adb.ConnectionInfo
import com.tunerquet.app.adb.ConnectionTarget
import com.tunerquet.app.adb.InventoryClient
import com.tunerquet.app.adb.PairedDevice
import com.tunerquet.app.adb.PairedDeviceStore
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

enum class InventoryPhase { IDLE, CONNECTING, TRUST_REQUIRED, READING, COMPLETE, PARTIAL, FAILED, CANCELLED, TIMED_OUT }
data class InventoryState(
    val phase: InventoryPhase = InventoryPhase.IDLE, val message: String = "Select a paired TV to read its inventory.",
    val serverKey: String? = null, val snapshot: InventorySnapshot? = null
)

/** Owns one approved device, one TLS session and one ephemeral inventory snapshot. */
class InventorySession(
    private val clients: () -> InventoryClient,
    private val store: PairedDeviceStore,
    private val callbacks: Executor,
    private val onChanged: (InventoryState) -> Unit,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val deadlineMillis: Long = 120000
) : java.io.Closeable {
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private var active: InventoryClient? = null
    private var selected: PairedDevice? = null
    private var target: ConnectionTarget? = null
    private var info: ConnectionInfo? = null
    private var timeout: ScheduledFuture<*>? = null
    @Volatile var state = InventoryState(); private set
    private val running get() = state.phase in setOf(InventoryPhase.CONNECTING, InventoryPhase.TRUST_REQUIRED, InventoryPhase.READING)

    fun start(guid: String, target: ConnectionTarget, approved: Boolean) {
        require(approved) { "Confirm the selected TV and read-only inventory request." }
        synchronized(lock) {
            check(!closed && !running) { "An inventory request is already running or closed." }
            // Resolve trusted records here, never from discovery results or caller-provided risk data.
            val device = store.list().singleOrNull { it.guid == guid } ?: throw IllegalArgumentException("Pair with this TV first.")
            val run = ++generation
            selected = device
            this.target = target
            info = null
            emit(InventoryState(InventoryPhase.CONNECTING, "Authenticating ${target.address}…"))
            timeout = timer.schedule({ cancel(InventoryPhase.TIMED_OUT, "Inventory timed out. The connection was closed; retry from the TV's current connection port.", run) }, deadlineMillis, TimeUnit.MILLISECONDS)
            worker.execute {
                try {
                    val client = synchronized(lock) {
                        if (run != generation || closed) return@execute
                        clients().also { active = it }
                    }
                    val connected = client.connect(target, device.serverKeySha256)
                    synchronized(lock) {
                        if (run != generation || closed) return@execute
                        check(store.list().singleOrNull { it.guid == device.guid } == device) { "Pairing record changed" }
                        info = connected
                        if (device.serverKeySha256 == null) {
                            emit(InventoryState(InventoryPhase.TRUST_REQUIRED,
                                "No inventory command has been sent. Verify the TV address and approve this first server key on a trusted local network.", connected.serverKeySha256))
                        } else {
                            if (connected.serverKeySha256 != device.serverKeySha256) throw SecurityException("Server key changed")
                            beginRead(run)
                        }
                    }
                } catch (e: Exception) { fail(run, e) }
            }
        }
    }

    fun approveFirstServerKey() {
        synchronized(lock) {
            check(!closed && state.phase == InventoryPhase.TRUST_REQUIRED) { "No server key awaits approval." }
            beginRead(generation)
        }
    }
    private fun beginRead(run: Long) {
        emit(InventoryState(InventoryPhase.READING, "Preparing read-only inventory…", info?.serverKeySha256))
        worker.execute {
            try {
                val client: InventoryClient
                val device: PairedDevice
                val endpoint: ConnectionTarget
                val connection: ConnectionInfo
                synchronized(lock) {
                    if (run != generation || closed) return@execute
                    endpoint = target ?: error("Missing target")
                    connection = info ?: error("Missing connection")
                    device = store.pinServerKey(selected ?: error("Missing pairing"), connection.serverKeySha256, endpoint.port)
                    selected = device
                    client = active ?: error("Disconnected")
                    client.approveServerKey()
                }
                val start = System.currentTimeMillis()
                val readings = mutableListOf<InventoryReading>()
                var disconnected = false
                for (command in InventoryCommand.entries) {
                    synchronized(lock) {
                        if (run != generation || closed) return@execute
                        check(store.list().singleOrNull { it.guid == device.guid } == device) { "Pairing record changed" }
                        emit(InventoryState(InventoryPhase.READING, "Reading ${readings.size + 1}/${InventoryCommand.entries.size}: ${command.title}", connection.serverKeySha256))
                    }
                    if (disconnected) {
                        readings.add(InventoryReading(command, null, "Not read after connection failure."))
                        continue
                    }
                    try {
                        val result = client.read(command)
                        val problem = when {
                            result.exitCode != 0 -> "Command exited with status ${result.exitCode}."
                            result.stderr.isNotBlank() -> "The device reported a diagnostic on stderr."
                            result.stdout.isBlank() && command != InventoryCommand.DISABLED -> "The device returned no data."
                            Regex("^(Error:|Permission Denial:|Security exception:|/system/bin/sh:)").containsMatchIn(result.stdout.trimStart()) -> "The device reported an error in its output."
                            else -> null
                        }
                        readings.add(InventoryReading(command, result, problem))
                        if (command == InventoryCommand.PROPERTIES && problem == null) {
                            val reported = InventoryParser.properties(result.stdout)["persist.adb.wifi.guid"]
                            if (!reported.isNullOrBlank() && reported != device.guid) throw SecurityException("Device GUID does not match pairing")
                        }
                    } catch (e: SecurityException) { throw e }
                    catch (_: IOException) {
                        readings.add(InventoryReading(command, null, "The connection failed or returned invalid/truncated data."))
                        disconnected = true
                        client.close()
                    }
                }
                val snapshot = InventoryParser.snapshot(device.guid, endpoint.address, connection.serverKeySha256, start, readings)
                synchronized(lock) {
                    if (run != generation || closed) return@execute
                    client.close()
                    finish(InventoryState(if (snapshot.complete) InventoryPhase.COMPLETE else InventoryPhase.PARTIAL,
                        if (snapshot.complete) "Inventory captured. Connection closed." else "Partial inventory captured. Connection closed; review the missing readings.", connection.serverKeySha256, snapshot))
                }
            } catch (e: Exception) { fail(run, e) }
        }
    }
    private fun fail(run: Long, error: Exception) {
        val client = synchronized(lock) {
            if (run != generation || closed) return
            val client = active
            val message = if (error is SecurityException) "Device identity did not match. No further inventory requests were sent. Pair and trust the TV again."
            else "Connection or inventory could not be confirmed. Check the connection port and TV authorization. If its server key changed after a restart, pair and trust it again."
            finish(InventoryState(InventoryPhase.FAILED, message))
            client
        }
        try { client?.close() } catch (_: IOException) { }
    }
    private fun finish(result: InventoryState) {
        timeout?.cancel(false)
        timeout = null
        active = null
        emit(result)
    }
    private fun emit(result: InventoryState) {
        state = result
        callbacks.execute { synchronized(lock) { if (!closed && state === result) onChanged(result) } }
    }
    fun cancel() = cancel(InventoryPhase.CANCELLED, "Inventory cancelled. Connection closed; old readings were discarded.")
    private fun cancel(phase: InventoryPhase, message: String, expected: Long? = null) {
        val client = synchronized(lock) {
            if (!running || (expected != null && expected != generation)) return
            generation++
            val client = active
            finish(InventoryState(phase, message))
            client
        }
        try { client?.close() } catch (_: IOException) { }
    }
    fun reset() { cancel(); synchronized(lock) { if (!closed) emit(InventoryState()) } }
    override fun close() {
        cancel()
        synchronized(lock) { closed = true; generation++ }
        worker.shutdownNow()
        timer.shutdownNow()
    }
}
