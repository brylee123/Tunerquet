package com.tunerquet.app.adb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded ADB 1.0.1 framing; this client deliberately has no legacy/plaintext fallback. */
internal object AdbWire {
    const val VERSION = 0x01000001
    const val TLS_VERSION = 0x01000000
    const val MAX_PAYLOAD = 1024 * 1024
    fun command(name: String): Int = ByteBuffer.wrap(name.toByteArray(Charsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).int
    val CNXN = command("CNXN")
    val STLS = command("STLS")
    val OPEN = command("OPEN")
    val OKAY = command("OKAY")
    val WRTE = command("WRTE")
    val CLSE = command("CLSE")
    data class Packet(val command: Int, val arg0: Int = 0, val arg1: Int = 0, val payload: ByteArray = byteArrayOf())

    fun write(output: DataOutputStream, packet: Packet, skipChecksum: Boolean = true) {
        require(packet.payload.size <= MAX_PAYLOAD)
        val checksum = if (skipChecksum) 0 else packet.payload.sumOf { it.toInt() and 255 }
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(packet.command).putInt(packet.arg0).putInt(packet.arg1).putInt(packet.payload.size)
            .putInt(checksum).putInt(packet.command.inv()).array()
        output.write(header)
        output.write(packet.payload)
        output.flush()
    }
    fun read(input: DataInputStream, maxPayload: Int = MAX_PAYLOAD, skipChecksum: Boolean = true): Packet {
        val header = ByteBuffer.wrap(ByteArray(24).also(input::readFully)).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.int
        val arg0 = header.int
        val arg1 = header.int
        val size = header.int
        val checksum = header.int
        val magic = header.int
        if (magic != command.inv() || size < 0 || size > maxPayload || size > MAX_PAYLOAD) throw IOException("Invalid ADB packet header")
        val payload = ByteArray(size).also(input::readFully)
        if (!skipChecksum && checksum != payload.sumOf { it.toInt() and 255 }) throw IOException("Invalid ADB checksum")
        return Packet(command, arg0, arg1, payload)
    }
}
