package com.tunerquet.app.adb

import java.net.Inet6Address
import java.net.InetAddress

/** Only an explicit IP and pairing port are accepted; no DNS lookup or port guessing. */
class PairingTarget private constructor(val host: String, val port: Int) {
    val address: String get() = if (':' in host) "[$host]:$port" else "$host:$port"
    companion object {
        fun parse(host: String, port: String): PairingTarget {
            val ip = host.trim().removeSurrounding("[", "]")
            val valid = if (':' in ip) {
                ip.matches(Regex("[0-9a-fA-F:]+(%[a-zA-Z0-9_.-]+)?")) &&
                    runCatching { InetAddress.getByName(ip) is Inet6Address }.getOrDefault(false)
            } else {
                val parts = ip.split('.')
                parts.size == 4 && parts.all {
                    it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255
                }
            }
            require(valid) { "Enter the TV's IPv4 or IPv6 address, without a port or URL." }
            val number = port.trim().toIntOrNull()
            require(number != null && number in 1..65535) { "Enter a pairing port between 1 and 65535." }
            return PairingTarget(ip, number)
        }
    }
}

data class PairedDevice(val guid: String, val host: String, val pairingPort: Int, val pairedAt: Long)

interface PairedDeviceStore {
    fun save(device: PairedDevice)
    fun list(): List<PairedDevice>
    fun forget(guid: String)
}

/** Pairing has no ADB shell/connection API. A successful result is an authenticated GUID. */
interface PairingClient : java.io.Closeable {
    fun pair(target: PairingTarget, code: ByteArray): String
}

enum class PairingPhase { IDLE, PAIRING, PAIRED, FAILED, CANCELLED, TIMED_OUT }
data class PairingState(val phase: PairingPhase = PairingPhase.IDLE, val message: String = "Ready to pair")
