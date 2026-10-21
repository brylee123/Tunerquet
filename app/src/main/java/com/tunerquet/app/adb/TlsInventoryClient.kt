package com.tunerquet.app.adb

import android.annotation.SuppressLint
import com.tunerquet.app.inventory.InventoryCommand
import com.tunerquet.app.inventory.ShellResult
import org.conscrypt.Conscrypt
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Only TLS/STLS + shell-v2 are supported. No AUTH/public-key enrollment or plaintext fallback. */
class TlsInventoryClient(private val identity: AdbIdentity) : InventoryClient {
    private val lock = Any()
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private var socket: Socket? = null
    private var tlsSocket: SSLSocket? = null
    private var closed = false
    @Volatile private var approved = false
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var maxPayload = AdbWire.MAX_PAYLOAD
    private var nextStream = 1
    private var connected = false

    override fun connect(target: ConnectionTarget, expectedServerKey: String?): ConnectionInfo {
        check(!connected) { "Already connected" }
        require(expectedServerKey == null || expectedServerKey.matches(Regex("[0-9a-f]{64}")))
        val timeout = deadlines.schedule({ close() }, 20, TimeUnit.SECONDS)
        try {
            val credentials = identity.loadOrCreate()
            val plain = Socket()
            synchronized(lock) {
                if (closed) { plain.close(); throw IOException("Connection cancelled") }
                socket = plain
            }
            plain.connect(InetSocketAddress(target.host, target.port), 8000)
            plain.soTimeout = 10000
            plain.tcpNoDelay = true
            val plainIn = DataInputStream(plain.getInputStream())
            val plainOut = DataOutputStream(plain.getOutputStream())
            AdbWire.write(plainOut, AdbWire.Packet(AdbWire.CNXN, AdbWire.VERSION, AdbWire.MAX_PAYLOAD,
                "host::features=shell_v2;\u0000".toByteArray(Charsets.UTF_8)), skipChecksum = false)
            val upgrade = AdbWire.read(plainIn, 4096)
            if (upgrade.command != AdbWire.STLS || upgrade.arg0 != AdbWire.TLS_VERSION ||
                upgrade.arg1 != 0 || upgrade.payload.isNotEmpty()) throw IOException("Secure ADB TLS upgrade required")
            AdbWire.write(plainOut, AdbWire.Packet(AdbWire.STLS, AdbWire.TLS_VERSION))
            val trust = ServerKeyTrust(expectedServerKey)
            val context = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply {
                init(arrayOf(TlsPairingClient.ClientKeyManager(credentials)), arrayOf(trust), SecureRandom())
            }
            val tls = context.socketFactory.createSocket(plain, target.host, target.port, true) as SSLSocket
            synchronized(lock) {
                if (closed) { tls.close(); throw IOException("Connection cancelled") }
                tlsSocket = tls
            }
            tls.enabledProtocols = arrayOf("TLSv1.3")
            tls.soTimeout = 10000
            tls.startHandshake()
            val secureIn = DataInputStream(tls.inputStream)
            val secureOut = DataOutputStream(tls.outputStream)
            // TLS 1.3 can appear to complete before the server rejects a client certificate.
            // Only its encrypted CNXN acknowledges that this ADB session was accepted.
            val accepted = AdbWire.read(secureIn, 64 * 1024)
            if (accepted.command != AdbWire.CNXN || accepted.arg0 != AdbWire.VERSION || accepted.arg1 !in 1..AdbWire.MAX_PAYLOAD) {
                throw IOException("ADB authentication was not confirmed")
            }
            val banner = accepted.payload.toString(Charsets.UTF_8).trimEnd('\u0000')
            if (!banner.startsWith("device::")) throw IOException("Target is not in normal Android device mode")
            val features = Regex("(?:^|;)features=([^;]*)").find(banner.substringAfter("::"))?.groupValues?.get(1)?.split(',') ?: emptyList()
            if ("shell_v2" !in features) throw IOException("Target does not support verified shell exit status")
            input = secureIn
            output = secureOut
            maxPayload = accepted.arg1
            connected = true
            return ConnectionInfo(trust.observedKey ?: throw IOException("Server identity unavailable"), banner)
        } catch (e: Exception) { close(); throw e }
        finally { timeout.cancel(false) }
    }

    override fun approveServerKey() {
        synchronized(lock) {
            check(connected && !closed) { "Connection is closed" }
            approved = true
        }
    }

    override fun read(command: InventoryCommand): ShellResult {
        check(connected && approved && !closed) { "An approved authenticated connection is required" }
        val timeout = deadlines.schedule({ close() }, 30, TimeUnit.SECONDS)
        try {
            val source = input ?: throw IOException("Disconnected")
            val sink = output ?: throw IOException("Disconnected")
            val local = nextStream++
            val destination = "shell,v2,raw:${command.command}\u0000".toByteArray(Charsets.UTF_8)
            if (destination.size > maxPayload) throw IOException("Peer payload limit too small")
            AdbWire.write(sink, AdbWire.Packet(AdbWire.OPEN, local, 0, destination))
            val opened = AdbWire.read(source, maxPayload)
            if (opened.command != AdbWire.OKAY || opened.arg1 != local || opened.arg0 == 0 || opened.payload.isNotEmpty()) {
                throw IOException("ADB read-only service was rejected")
            }
            val remote = opened.arg0
            val bytes = ByteArrayOutputStream()
            while (true) {
                val packet = AdbWire.read(source, maxPayload)
                if (packet.arg0 != remote || packet.arg1 != local) throw IOException("Unexpected ADB stream identity")
                when (packet.command) {
                    AdbWire.WRTE -> {
                        if (bytes.size().toLong() + packet.payload.size > command.maxBytes.toLong() + 1024 * 1024) throw IOException("Inventory output limit exceeded")
                        bytes.write(packet.payload)
                        AdbWire.write(sink, AdbWire.Packet(AdbWire.OKAY, local, remote))
                    }
                    AdbWire.CLSE -> {
                        if (packet.payload.isNotEmpty()) throw IOException("Invalid ADB close packet")
                        AdbWire.write(sink, AdbWire.Packet(AdbWire.CLSE, local, remote))
                        return ShellV2.decode(bytes.toByteArray(), command.maxBytes)
                    }
                    else -> throw IOException("Unexpected packet in inventory stream")
                }
            }
        } catch (e: Exception) { close(); throw e }
        finally { timeout.cancel(false) }
    }

    override fun close() {
        val active = synchronized(lock) {
            closed = true
            approved = false
            val pair = socket to tlsSocket
            socket = null
            tlsSocket = null
            pair
        }
        try { active.first?.close() }
        finally {
            try { active.second?.close() }
            finally { deadlines.shutdownNow() }
        }
    }

    /** First-contact certificates are inspected only. Session approval gates every OPEN packet. */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private class ServerKeyTrust(private val expected: String?) : X509TrustManager {
        var observedKey: String? = null; private set
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw CertificateException("Client only") }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("Missing server certificate")
            val hash = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded).joinToString("") { "%02x".format(it) }
            if (expected != null && !MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), hash.toByteArray(Charsets.US_ASCII))) {
                throw CertificateException("Server key changed. Pair and trust the TV again.")
            }
            observedKey = hash
        }
    }
}
