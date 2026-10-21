package com.tunerquet.app.adb

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Owns one attempt. Completion, persistence and cancellation are serialized by the lock. */
class PairingSession(
    private val clients: () -> PairingClient,
    private val store: PairedDeviceStore,
    private val callbacks: Executor,
    private val onChanged: (PairingState) -> Unit,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val deadlineMillis: Long = 30_000
) : java.io.Closeable {
    private val lock = Any()
    private var client: PairingClient? = null
    private var timeout: ScheduledFuture<*>? = null
    private var generation = 0L
    private var closed = false
    private var secret: ByteArray? = null
    @Volatile var state = PairingState(); private set

    /** The caller's code is consumed and erased, including when validation fails. */
    fun start(target: PairingTarget, code: CharArray, approved: Boolean) {
        val password = try {
            require(approved) { "Confirm that you selected this TV before pairing." }
            require(code.size == 6 && code.all { it in '0'..'9' }) { "Enter the six-digit code currently shown on the TV." }
            ByteArray(code.size) { code[it].code.toByte() }
        } finally { code.fill('\u0000') }
        try {
            synchronized(lock) {
                check(!closed) { "Pairing session is closed." }
                check(state.phase != PairingPhase.PAIRING) { "A pairing attempt is already running." }
                secret = password
                val run = ++generation
                emit(PairingState(PairingPhase.PAIRING, "Pairing with ${target.address}…"))
                timeout = timer.schedule({ cancel(PairingPhase.TIMED_OUT, "Pairing timed out. Reopen the TV's pairing-code dialog and try again.", run) }, deadlineMillis, TimeUnit.MILLISECONDS)
                worker.execute {
                    var attempt: PairingClient? = null
                    try {
                        synchronized(lock) {
                            if (run != generation || closed) return@execute
                            attempt = clients()
                            client = attempt
                        }
                        val guid = attempt!!.pair(target, password)
                        synchronized(lock) {
                            if (run != generation || closed) return@execute
                            require(guid.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { "Invalid device identity." }
                            store.save(PairedDevice(guid, target.host, target.port, System.currentTimeMillis()))
                            finish(PairingState(PairingPhase.PAIRED, "Pairing confirmed. This TV now trusts Tunerquet's key. An ADB session has not been opened."))
                        }
                    } catch (e: Exception) {
                        synchronized(lock) {
                            if (run == generation && !closed) {
                                val result = if (e is SocketTimeoutException) {
                                    PairingState(PairingPhase.TIMED_OUT, "The TV did not respond in time. Reopen its pairing-code dialog and try again.")
                                } else {
                                    PairingState(PairingPhase.FAILED, "Pairing could not be confirmed or saved. Check the address, pairing port and fresh code. The TV may already trust this key; check its paired devices before retrying.")
                                }
                                finish(result)
                            }
                        }
                    } finally {
                        password.fill(0)
                        try { attempt?.close() } catch (_: IOException) { }
                    }
                }
            }
        } catch (e: Exception) {
            password.fill(0)
            throw e
        }
    }

    private fun finish(result: PairingState) {
        timeout?.cancel(false)
        timeout = null
        client = null
        secret?.fill(0)
        secret = null
        emit(result)
    }
    private fun emit(result: PairingState) {
        state = result
        callbacks.execute {
            synchronized(lock) { if (!closed && state === result) onChanged(result) }
        }
    }
    fun reset() {
        cancel()
        synchronized(lock) { if (!closed) emit(PairingState()) }
    }
    fun cancel() = cancel(PairingPhase.CANCELLED, "Pairing cancelled. If the exchange already reached the TV, revoke Tunerquet there to remove trust.")
    private fun cancel(phase: PairingPhase, message: String, expectedGeneration: Long? = null) {
        val active = synchronized(lock) {
            if (state.phase != PairingPhase.PAIRING || (expectedGeneration != null && generation != expectedGeneration)) return
            generation++
            val active = client
            finish(PairingState(phase, message))
            active
        }
        try { active?.close() } catch (_: IOException) { }
    }
    override fun close() {
        cancel()
        synchronized(lock) { closed = true; generation++ }
        worker.shutdownNow()
        timer.shutdownNow()
    }
}
