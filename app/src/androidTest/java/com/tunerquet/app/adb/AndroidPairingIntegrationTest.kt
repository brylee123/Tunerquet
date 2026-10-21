package com.tunerquet.app.adb

import androidx.test.platform.app.InstrumentationRegistry
import io.github.muntashirakon.crypto.spake2.Spake2Context
import io.github.muntashirakon.crypto.spake2.Spake2Role
import org.conscrypt.Conscrypt
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class AndroidPairingIntegrationTest {
    @Test fun identityPersistsWithoutExportablePrivateKey() {
        val first = AdbIdentity().loadOrCreate()
        val second = AdbIdentity().loadOrCreate()
        assertNull(first.privateKey.encoded)
        assertArrayEquals(first.certificate.encoded, second.certificate.encoded)
    }

    @Test fun pairedMetadataSurvivesStoreRecreationAndCanBeForgotten() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = PairedDevice("test-guid", "192.168.1.20", 37123, 123456789)
        val store = AndroidPairedDeviceStore(context)
        try {
            store.save(device)
            assertEquals(device, AndroidPairedDeviceStore(context).list().first { it.guid == device.guid })
            store.forget(device.guid)
            assertFalse(AndroidPairedDeviceStore(context).list().any { it.guid == device.guid })
        } finally { store.forget(device.guid) }
    }

    /** Exercises the shipping Conscrypt provider and an opaque AndroidKeyStore RSA key over TLS 1.3. */
    @Test(timeout = 30000) fun tlsPairingAuthenticatesMockServerUsingKeystoreKey() {
        val identity = AdbIdentity()
        identity.loadOrCreate()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, null) }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { require(!chain.isNullOrEmpty()) }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) { error("Server only") }
        }
        val context = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply {
            init(managers.keyManagers, arrayOf(trust), SecureRandom())
        }
        val server = context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        server.needClientAuth = true
        server.enabledProtocols = arrayOf("TLSv1.3")
        server.soTimeout = 10000
        val worker = Executors.newSingleThreadExecutor()
        val result = worker.submit {
            (server.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 10000
                socket.startHandshake()
                assertEquals("TLSv1.3", socket.session.protocol)
                assertTrue(socket.session.peerCertificates.isNotEmpty())
                val binding = Conscrypt.exportKeyingMaterial(socket, "adb-label\u0000", null, 64)!!
                val bob = Spake2Context(Spake2Role.Bob, PairingProtocol.serverName, PairingProtocol.clientName)
                try {
                    val message = bob.generateMessage("123456".toByteArray() + binding)
                    val input = DataInputStream(socket.inputStream)
                    val output = DataOutputStream(socket.outputStream)
                    val theirs = PairingProtocol.read(input, 0, 32)
                    PairingProtocol.write(output, 0, message)
                    val material = bob.processMessage(theirs)
                    val key = PairingProtocol.deriveKey(material)
                    val clientInfo = PairingProtocol.crypt(Cipher.DECRYPT_MODE, key, PairingProtocol.read(input, 1, 8208))
                    assertEquals(0, clientInfo[0].toInt())
                    val peer = ByteArray(8192).apply { this[0] = 1; "tls-mock-guid".toByteArray().copyInto(this, 1) }
                    PairingProtocol.write(output, 1, PairingProtocol.crypt(Cipher.ENCRYPT_MODE, key, peer))
                    key.fill(0)
                    material.fill(0)
                } finally { binding.fill(0); bob.destroy() }
            }
        }
        try {
            TlsPairingClient(identity).use { client ->
                assertEquals("tls-mock-guid", client.pair(PairingTarget.parse("127.0.0.1", server.localPort.toString()), "123456".toByteArray()))
            }
            result.get(15, TimeUnit.SECONDS)
        } finally {
            try { result.get(15, TimeUnit.SECONDS) }
            finally { server.close(); worker.shutdownNow() }
        }
    }
}
