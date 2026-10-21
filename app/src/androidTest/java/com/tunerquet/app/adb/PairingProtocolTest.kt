package com.tunerquet.app.adb

import io.github.muntashirakon.crypto.spake2.Spake2Context
import io.github.muntashirakon.crypto.spake2.Spake2Role
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

class PairingProtocolTest {
    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey

    @Test(timeout = 10000) fun primitiveSequentialAgreement() {
        val alice = Spake2Context(Spake2Role.Alice, PairingProtocol.clientName, PairingProtocol.serverName)
        val bob = Spake2Context(Spake2Role.Bob, PairingProtocol.serverName, PairingProtocol.clientName)
        try {
            val a = alice.generateMessage("test-password".toByteArray())
            val b = bob.generateMessage("test-password".toByteArray())
            assertArrayEquals(alice.processMessage(b), bob.processMessage(a))
        } finally { alice.destroy(); bob.destroy() }
    }
    @Test(timeout = 10000) fun hkdfMatchesIndependentDotNetFixture() {
        // Independently calculated with .NET HMACSHA256, bytes 0..63 as input key material.
        assertEquals("5e234c26fa41fb42e5d493b262c0def1",
            PairingProtocol.deriveKey(ByteArray(64) { it.toByte() }).joinToString("") { "%02x".format(it) })
    }
    @Test(timeout = 10000) fun publicKeyUsesAndroidMontgomeryRepresentation() {
        val buffer = ByteBuffer.wrap(PairingProtocol.encodePublicKey(rsa)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buffer.int)
        val inverse = buffer.int.toLong() and 0xffffffffL
        val modulus = BigInteger(1, ByteArray(256).also(buffer::get).reversedArray())
        val rr = BigInteger(1, ByteArray(256).also(buffer::get).reversedArray())
        assertEquals(rsa.modulus, modulus)
        val r32 = BigInteger.ONE.shiftLeft(32)
        assertEquals(r32.subtract(BigInteger.ONE), modulus.multiply(BigInteger.valueOf(inverse)).mod(r32))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(modulus), rr)
        assertEquals(65537, buffer.int)
        assertEquals(0, buffer.remaining())
    }
    @Test(timeout = 10000) fun rejectsInvalidHeadersBeforeReadingPayload() {
        val headers = listOf(
            Triple(0, 0, 32), Triple(2, 0, 32), Triple(1, 1, 32),
            Triple(1, 0, -1), Triple(1, 0, Int.MAX_VALUE), Triple(1, 0, 0), Triple(1, 0, 31))
        headers.forEach { (version, type, size) ->
            val bytes = ByteArrayOutputStream().also { out ->
                DataOutputStream(out).apply { writeByte(version); writeByte(type); writeInt(size) }
            }.toByteArray()
            assertThrows(IOException::class.java) { PairingProtocol.read(DataInputStream(bytes.inputStream()), 0, 32) }
        }
    }
    @Test(timeout = 10000) fun rejectsTruncatedFrames() {
        val bytes = byteArrayOf(1, 0, 0, 0, 0, 32, 10)
        assertThrows(EOFException::class.java) { PairingProtocol.read(DataInputStream(bytes.inputStream()), 0, 32) }
    }
    @Test(timeout = 10000) fun rejectsWrongPeerTypeAndInvalidIdentity() {
        assertThrows(IOException::class.java) { PairingProtocol.guid(ByteArray(8192)) }
        assertThrows(IOException::class.java) { PairingProtocol.guid(ByteArray(8191)) }
        assertThrows(IOException::class.java) { PairingProtocol.guid(ByteArray(8192).apply { this[0] = 1 }) }
        assertThrows(IOException::class.java) { PairingProtocol.guid(ByteArray(8192).apply { this[0] = 1; this[1] = 10 }) }
    }
    @Test(timeout = 10000) fun authenticatesMockPeerAndExchangesPublicKey() {
        assertEquals("adb-device-guid", exchangeWithPeer("123456"))
    }
    @Test(timeout = 10000) fun wrongCodeCannotAuthenticatePeer() {
        assertThrows(AEADBadTagException::class.java) { exchangeWithPeer("654321") }
    }
    @Test(timeout = 10000) fun tamperedPeerInfoCannotAuthenticate() {
        assertThrows(AEADBadTagException::class.java) { exchangeWithPeer("123456", tamper = true) }
    }

    /** No network and no Android APIs. Runs the actual client exchange against a mock SPAKE2 Bob. */
    private fun exchangeWithPeer(serverCode: String, tamper: Boolean = false): String {
        val input = PipedInputStream(32768)
        val serverOutput = PipedOutputStream(input)
        val serverInput = PipedInputStream(32768)
        val output = PipedOutputStream(serverInput)
        val executor = Executors.newSingleThreadExecutor()
        val binding = ByteArray(64) { (it + 10).toByte() }
        val server = executor.submit {
            val bob = Spake2Context(Spake2Role.Bob, PairingProtocol.serverName, PairingProtocol.clientName)
            try {
                val theirMessage = PairingProtocol.read(DataInputStream(serverInput), 0, 32)
                PairingProtocol.write(DataOutputStream(serverOutput), 0, bob.generateMessage(serverCode.toByteArray() + binding))
                val material = bob.processMessage(theirMessage)
                val key = PairingProtocol.deriveKey(material)
                val clientInfo = PairingProtocol.read(DataInputStream(serverInput), 1, PairingProtocol.PEER_SIZE + 16)
                if (serverCode == "123456") {
                    val plain = PairingProtocol.crypt(Cipher.DECRYPT_MODE, key, clientInfo)
                    assertEquals(0, plain[0].toInt())
                    val end = (1 until plain.size).first { plain[it] == 0.toByte() }
                    val publicText = String(plain, 1, end - 1, Charsets.US_ASCII).substringBefore(' ')
                    assertArrayEquals(PairingProtocol.encodePublicKey(rsa), Base64.getDecoder().decode(publicText))
                }
                val peer = ByteArray(8192).apply {
                    this[0] = 1
                    "adb-device-guid".toByteArray().copyInto(this, 1)
                }
                val encrypted = PairingProtocol.crypt(Cipher.ENCRYPT_MODE, key, peer)
                if (tamper) encrypted[10] = (encrypted[10].toInt() xor 1).toByte()
                PairingProtocol.write(DataOutputStream(serverOutput), 1, encrypted)
                key.fill(0)
                material.fill(0)
            } finally { bob.destroy(); serverInput.close(); serverOutput.close() }
        }
        try {
            return PairingProtocol.exchange(DataInputStream(input), DataOutputStream(output), "123456".toByteArray() + binding, rsa)
        } finally {
            try { server.get(5, TimeUnit.SECONDS) }
            finally { input.close(); output.close(); serverInput.close(); serverOutput.close(); executor.shutdownNow() }
        }
    }
}
