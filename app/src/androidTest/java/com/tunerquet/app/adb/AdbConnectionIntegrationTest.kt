package com.tunerquet.app.adb

import com.tunerquet.app.inventory.InventoryCommand
import com.tunerquet.app.cleanup.*
import org.conscrypt.Conscrypt
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/** A mock adbd peer over real TLS 1.3 and the shipping AndroidKeyStore key. No physical TV is contacted. */
class AdbConnectionIntegrationTest {
    private class Peer(
        private val plaintext: Boolean = false,
        private val rejectClient: Boolean = false,
        private val wrongStream: Boolean = false,
        private val banner: String = "device::ro.product.model=Test TV;features=shell_v2;",
        private val omitAcceptance: Boolean = false,
        private val cleanupServices: Set<String> = emptySet()
    ) : java.io.Closeable {
        val identity = AdbIdentity()
        private val credentials = identity.loadOrCreate()
        val hash = MessageDigest.getInstance("SHA-256").digest(credentials.certificate.publicKey.encoded).joinToString("") { "%02x".format(it) }
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10000 }
        val target = ConnectionTarget.parse("127.0.0.1", listener.localPort.toString())
        val opened = CopyOnWriteArrayList<String>()
        val handshake = CountDownLatch(1)
        private val worker = Executors.newSingleThreadExecutor()
        private var accepted: Socket? = null
        private val result = worker.submit {
            listener.accept().use { raw ->
                accepted = raw
                raw.soTimeout = 10000
                val input = DataInputStream(raw.getInputStream())
                val output = DataOutputStream(raw.getOutputStream())
                val connect = AdbWire.read(input, skipChecksum = false)
                assertEquals(AdbWire.CNXN, connect.command)
                if (plaintext) {
                    AdbWire.write(output, AdbWire.Packet(AdbWire.CNXN, AdbWire.VERSION, 4096, banner.toByteArray()))
                    return@submit
                }
                AdbWire.write(output, AdbWire.Packet(AdbWire.STLS, AdbWire.TLS_VERSION))
                assertEquals(AdbWire.STLS, AdbWire.read(input).command)
                val manager = object : X509ExtendedKeyManager() {
                    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("test")
                    override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "test"
                    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("test")
                    override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = "test"
                    override fun getCertificateChain(alias: String?) = arrayOf(credentials.certificate)
                    override fun getPrivateKey(alias: String?): PrivateKey = credentials.privateKey
                }
                val trust = object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) { error("Server only") }
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        if (rejectClient || chain.isNullOrEmpty() || !chain[0].publicKey.encoded.contentEquals(credentials.certificate.publicKey.encoded)) throw CertificateException("Unapproved client key")
                    }
                }
                val context = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply {
                    init(arrayOf(manager), arrayOf(trust), SecureRandom())
                }
                (context.socketFactory.createSocket(raw, "127.0.0.1", listener.localPort, true) as SSLSocket).use { tls ->
                    tls.useClientMode = false
                    tls.needClientAuth = true
                    tls.enabledProtocols = arrayOf("TLSv1.3")
                    tls.soTimeout = 10000
                    tls.startHandshake()
                    handshake.countDown()
                    val secureIn = DataInputStream(tls.inputStream)
                    val secureOut = DataOutputStream(tls.outputStream)
                    if (omitAcceptance) {
                        try { secureIn.readByte() } catch (_: IOException) { }
                        return@submit
                    }
                    AdbWire.write(secureOut, AdbWire.Packet(AdbWire.CNXN, AdbWire.VERSION, 4096, banner.toByteArray()))
                    while (true) {
                        val request = try { AdbWire.read(secureIn) } catch (_: IOException) { break }
                        assertEquals(AdbWire.OPEN, request.command)
                        val service = request.payload.toString(Charsets.UTF_8).trimEnd('\u0000')
                        opened.add(service)
                        assertTrue(service in cleanupServices || InventoryCommand.entries.any { service == "shell,v2,raw:${it.command}" })
                        val local = request.arg0
                        val remote = 17
                        AdbWire.write(secureOut, AdbWire.Packet(AdbWire.OKAY, remote, local))
                        val stdout = "test output for $service\n".toByteArray()
                        fun shell(type: Int, data: ByteArray) = ByteBuffer.allocate(5 + data.size).order(ByteOrder.LITTLE_ENDIAN).put(type.toByte()).putInt(data.size).put(data).array()
                        val bytes = shell(1, stdout) + shell(2, "diagnostic".toByteArray()) + shell(3, byteArrayOf(7))
                        // Fragment both the shell header and payload across ADB WRTE boundaries.
                        for (chunk in listOf(bytes.copyOfRange(0, 2), bytes.copyOfRange(2, 11), bytes.copyOfRange(11, bytes.size))) {
                            AdbWire.write(secureOut, AdbWire.Packet(AdbWire.WRTE, if (wrongStream) remote + 1 else remote, local, chunk))
                            if (wrongStream) return@submit
                            val ack = AdbWire.read(secureIn)
                            assertEquals(AdbWire.OKAY, ack.command)
                            assertEquals(local, ack.arg0)
                            assertEquals(remote, ack.arg1)
                        }
                        AdbWire.write(secureOut, AdbWire.Packet(AdbWire.CLSE, remote, local))
                        assertEquals(AdbWire.CLSE, AdbWire.read(secureIn).command)
                    }
                }
            }
        }
        fun verify() { result.get(15, TimeUnit.SECONDS) }
        override fun close() {
            listener.close()
            accepted?.close()
            worker.shutdownNow()
        }
    }

    @Test(timeout = 30000) fun tlsAcceptanceAndApprovalPrecedeEveryRead() {
        Peer().use { peer ->
            TlsInventoryClient(peer.identity).use { client ->
                val info = client.connect(peer.target, null)
                assertEquals(peer.hash, info.serverKeySha256)
                assertThrows(IllegalStateException::class.java) { client.read(InventoryCommand.PROPERTIES) }
                assertTrue(peer.opened.isEmpty())
                client.approveServerKey()
                for (command in listOf(InventoryCommand.PROPERTIES, InventoryCommand.MEMORY)) {
                    val read = client.read(command)
                    assertTrue(read.stdout.contains(command.command))
                    assertEquals("diagnostic", read.stderr)
                    assertEquals(7, read.exitCode)
                }
            }
            peer.verify()
            assertEquals(2, peer.opened.size)
        }
    }
    @Test(timeout = 30000) fun typedCleanupCommandsStayBehindTlsAndApproval() {
        val name = "com.netflix.ninja"
        val commands = setOf("shell,v2,raw:pm disable-user --user 0 $name",
            "shell,v2,raw:pm default-state --user 0 $name", "shell,v2,raw:pm enable --user 0 $name",
            "shell,v2,raw:dumpsys package $name") + CleanupRead.entries.map { "shell,v2,raw:${it.command}" }
        Peer(cleanupServices = commands).use { peer ->
            TlsInventoryClient(peer.identity).use { client ->
                client.connect(peer.target, peer.hash)
                assertThrows(IllegalStateException::class.java) { client.change(name, PackageChange.DISABLE) }
                assertTrue(peer.opened.isEmpty())
                client.approveServerKey()
                assertThrows(IllegalArgumentException::class.java) { client.change("$name;reboot", PackageChange.DISABLE) }
                assertThrows(IllegalArgumentException::class.java) { client.change("com.android.settings", PackageChange.DISABLE) }
                assertTrue(peer.opened.isEmpty())
                client.packageDetails(name)
                CleanupRead.entries.forEach { client.inspect(it) }
                PackageChange.entries.forEach { client.change(name, it) }
            }
            peer.verify()
            assertEquals(commands, peer.opened.toSet())
            assertEquals(commands.size, peer.opened.size)
        }
    }
    @Test(timeout = 30000) fun wrongPinnedServerKeyCannotOpenInventory() {
        Peer().use { peer ->
            TlsInventoryClient(peer.identity).use { client ->
                assertThrows(IOException::class.java) { client.connect(peer.target, "0".repeat(64)) }
            }
            assertTrue(peer.opened.isEmpty())
        }
    }
    @Test(timeout = 30000) fun rejectedClientCertificateNeverBecomesConnected() {
        Peer(rejectClient = true).use { peer ->
            TlsInventoryClient(peer.identity).use { client ->
                assertThrows(IOException::class.java) { client.connect(peer.target, peer.hash) }
                assertThrows(IllegalStateException::class.java) { client.approveServerKey() }
            }
            assertTrue(peer.opened.isEmpty())
        }
    }
    @Test(timeout = 30000) fun plaintextAcceptanceCannotDowngradeTls() {
        Peer(plaintext = true).use { peer ->
            TlsInventoryClient(peer.identity).use { client ->
                assertThrows(IOException::class.java) { client.connect(peer.target, null) }
            }
            peer.verify()
            assertTrue(peer.opened.isEmpty())
        }
    }
    @Test(timeout = 30000) fun unsupportedShellDoesNotOpenInventory() {
        Peer(banner = "device::features=cmd").use { peer ->
            TlsInventoryClient(peer.identity).use { client ->
                assertThrows(IOException::class.java) { client.connect(peer.target, peer.hash) }
            }
            peer.verify()
            assertTrue(peer.opened.isEmpty())
        }
    }
    @Test(timeout = 30000) fun wrongStreamIdentityAbortsConnection() {
        Peer(wrongStream = true).use { peer ->
            TlsInventoryClient(peer.identity).use { client ->
                client.connect(peer.target, peer.hash)
                client.approveServerKey()
                assertThrows(IOException::class.java) { client.read(InventoryCommand.PROPERTIES) }
                assertThrows(IllegalStateException::class.java) { client.read(InventoryCommand.MEMORY) }
            }
            peer.verify()
        }
    }
    @Test(timeout = 30000) fun cancellationAbortsWaitingForEncryptedAcceptance() {
        Peer(omitAcceptance = true).use { peer ->
            val worker = Executors.newSingleThreadExecutor()
            TlsInventoryClient(peer.identity).use { client ->
                val result = worker.submit<Boolean> { try { client.connect(peer.target, peer.hash); false } catch (_: IOException) { true } }
                assertTrue(peer.handshake.await(10, TimeUnit.SECONDS))
                client.close()
                assertTrue(result.get(5, TimeUnit.SECONDS))
                assertThrows(IllegalStateException::class.java) { client.approveServerKey() }
            }
            worker.shutdownNow()
            peer.verify()
            assertTrue(peer.opened.isEmpty())
        }
    }
}
