package com.tunerquet.app.adb

import com.tunerquet.app.inventory.InventoryCommand
import com.tunerquet.app.inventory.ShellResult

class ConnectionTarget private constructor(val host: String, val port: Int) {
    val address: String get() = if (':' in host) "[$host]:$port" else "$host:$port"
    companion object {
        fun parse(host: String, port: String): ConnectionTarget {
            val endpoint = try { PairingTarget.parse(host, port) }
            catch (e: IllegalArgumentException) { throw IllegalArgumentException(e.message?.replace("pairing port", "connection port")) }
            return ConnectionTarget(endpoint.host, endpoint.port)
        }
    }
}

data class ConnectionInfo(val serverKeySha256: String, val banner: String)

/** One authenticated TLS connection with a fixed, read-only command surface. */
interface InventoryClient : java.io.Closeable {
    fun connect(target: ConnectionTarget, expectedServerKey: String?): ConnectionInfo
    fun approveServerKey()
    fun read(command: InventoryCommand): ShellResult
}
