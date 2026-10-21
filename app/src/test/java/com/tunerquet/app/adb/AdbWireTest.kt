package com.tunerquet.app.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbWireTest {
    @Test fun packetRoundTripsAndUsesLittleEndian() {
        val packet = AdbWire.Packet(AdbWire.CNXN, AdbWire.VERSION, 4096, "host::".toByteArray())
        val out = ByteArrayOutputStream()
        AdbWire.write(DataOutputStream(out), packet, false)
        assertEquals("CNXN", out.toByteArray().take(4).toByteArray().toString(Charsets.US_ASCII))
        val read = AdbWire.read(DataInputStream(out.toByteArray().inputStream()), skipChecksum = false)
        assertEquals(packet.command, read.command)
        assertEquals(packet.arg0, read.arg0)
        assertArrayEquals(packet.payload, read.payload)
    }
    @Test fun rejectsUnboundedLengthsAndBadMagicBeforeAllocation() {
        for (length in listOf(-1, Int.MAX_VALUE, AdbWire.MAX_PAYLOAD + 1)) {
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(AdbWire.WRTE)
                .putInt(1).putInt(2).putInt(length).putInt(0).putInt(AdbWire.WRTE.inv()).array()
            assertThrows(IOException::class.java) { AdbWire.read(DataInputStream(header.inputStream())) }
        }
        assertThrows(IOException::class.java) { AdbWire.read(DataInputStream(ByteArray(24).inputStream())) }
    }
    @Test fun rejectsChecksumAndTruncatedPayload() {
        val bytes = ByteArrayOutputStream().also { AdbWire.write(DataOutputStream(it), AdbWire.Packet(AdbWire.WRTE, 1, 2, byteArrayOf(8)), false) }.toByteArray()
        bytes[24] = 9
        assertThrows(IOException::class.java) { AdbWire.read(DataInputStream(bytes.inputStream()), skipChecksum = false) }
        assertThrows(EOFException::class.java) { AdbWire.read(DataInputStream(bytes.copyOf(24).inputStream())) }
    }
    private fun frame(type: Int, bytes: ByteArray) = ByteBuffer.allocate(5 + bytes.size).order(ByteOrder.LITTLE_ENDIAN).put(type.toByte()).putInt(bytes.size).put(bytes).array()
    @Test fun shellSeparatesStderrAndChecksExit() {
        val result = ShellV2.decode(frame(1, "output".toByteArray()) + frame(2, "error".toByteArray()) + frame(3, byteArrayOf(1)), 100)
        assertEquals("output", result.stdout)
        assertEquals("error", result.stderr)
        assertEquals(1, result.exitCode)
    }
    @Test fun shellRejectsMissingExitTrailingDataAndOversizedOutput() {
        assertThrows(IOException::class.java) { ShellV2.decode(frame(1, byteArrayOf(1)), 100) }
        assertThrows(IOException::class.java) { ShellV2.decode(frame(3, byteArrayOf(0)) + frame(1, byteArrayOf(1)), 100) }
        assertThrows(IOException::class.java) { ShellV2.decode(frame(1, ByteArray(10)) + frame(2, ByteArray(10)) + frame(3, byteArrayOf(0)), 19) }
        assertThrows(IOException::class.java) { ShellV2.decode(frame(9, byteArrayOf(1)), 100) }
        assertThrows(IOException::class.java) { ShellV2.decode(frame(3, ByteArray(4)), 100) }
        assertThrows(IOException::class.java) { ShellV2.decode(byteArrayOf(1, -1, -1, -1, -1), 100) }
    }
}
