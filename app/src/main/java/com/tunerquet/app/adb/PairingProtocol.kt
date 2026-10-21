package com.tunerquet.app.adb

import io.github.muntashirakon.crypto.spake2.Spake2Context
import io.github.muntashirakon.crypto.spake2.Spake2Role
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AOSP pairing protocol v1. Crypto primitives come from SPAKE2-Java and JCA. */
internal object PairingProtocol {
    const val PEER_SIZE = 8192
    val clientName: ByteArray get() = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
    val serverName: ByteArray get() = "adb pair server\u0000".toByteArray(Charsets.UTF_8)

    fun read(input: DataInputStream, type: Int, size: Int): ByteArray {
        val version = input.readUnsignedByte()
        val receivedType = input.readUnsignedByte()
        val length = input.readInt() // Network byte order; validate before allocation.
        if (version != 1 || receivedType != type || length != size) throw IOException("Invalid pairing frame")
        return ByteArray(length).also(input::readFully)
    }
    fun write(output: DataOutputStream, type: Int, payload: ByteArray) {
        require(type in 0..1 && payload.size in 1..(PEER_SIZE * 2))
        output.writeByte(1)
        output.writeByte(type)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    fun exchange(input: DataInputStream, output: DataOutputStream, password: ByteArray, publicKey: RSAPublicKey): String {
        val spake = Spake2Context(Spake2Role.Alice, clientName, serverName)
        var material: ByteArray? = null
        var key: ByteArray? = null
        try {
            write(output, 0, spake.generateMessage(password))
            material = spake.processMessage(read(input, 0, 32)) ?: throw IOException("Invalid pairing proof")
            key = deriveKey(material)
            val ours = ByteArray(PEER_SIZE) // type 0 = ADB RSA public key
            val text = (Base64.getEncoder().encodeToString(encodePublicKey(publicKey)) + " Tunerquet\u0000").toByteArray(Charsets.UTF_8)
            text.copyInto(ours, 1)
            write(output, 1, crypt(Cipher.ENCRYPT_MODE, key, ours))
            val theirs = crypt(Cipher.DECRYPT_MODE, key, read(input, 1, PEER_SIZE + 16))
            return guid(theirs)
        } finally {
            material?.fill(0)
            key?.fill(0)
            spake.destroy()
        }
    }

    /** RFC 5869 HKDF-SHA256 with AOSP's info and empty salt, yielding one 16-byte key. */
    fun deriveKey(material: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val extracted = mac.doFinal(material)
        try {
            mac.init(SecretKeySpec(extracted, "HmacSHA256"))
            val expanded = mac.doFinal("adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8) + byteArrayOf(1))
            return expanded.copyOf(16).also { expanded.fill(0) }
        } finally { extracted.fill(0) }
    }

    /** This protocol exchanges exactly one message in each direction (sequence zero). */
    fun crypt(mode: Int, key: ByteArray, data: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ByteArray(12)))
        doFinal(data)
    }

    fun guid(peer: ByteArray): String {
        if (peer.size != PEER_SIZE || peer[0] != 1.toByte()) throw IOException("Expected authenticated device GUID")
        val end = (1 until peer.size).firstOrNull { peer[it] == 0.toByte() } ?: throw IOException("Unterminated GUID")
        if (end !in 2..129) throw IOException("Invalid GUID length")
        val guid = String(peer, 1, end - 1, Charsets.US_ASCII)
        if (!guid.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) throw IOException("Invalid GUID")
        return guid
    }

    /** Android's 524-byte RSA public-key format, not DER or an OpenSSH key. */
    fun encodePublicKey(key: RSAPublicKey): ByteArray {
        require(key.modulus.bitLength() == 2048 && key.publicExponent == BigInteger.valueOf(65537))
        fun littleEndian(value: BigInteger): ByteArray {
            val bytes = value.toByteArray().reversedArray()
            return bytes.copyOf(256)
        }
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(key.modulus.mod(r32).modInverse(r32)).toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(key.modulus)
        return ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(64).putInt(n0inv).put(littleEndian(key.modulus)).put(littleEndian(rr)).putInt(65537).array()
    }
}
