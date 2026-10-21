package com.tunerquet.app.adb

import android.annotation.SuppressLint
import org.conscrypt.Conscrypt
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** Pairing-only TLS bootstrap. Trust is established by the code + channel-bound SPAKE2 proof. */
class TlsPairingClient(private val identity: AdbIdentity) : PairingClient {
    private val lock = Any()
    private var socket: Socket? = null
    private var closed = false

    override fun pair(target: PairingTarget, code: ByteArray): String {
        val credentials = identity.loadOrCreate()
        val plain = Socket()
        synchronized(lock) {
            if (closed) { plain.close(); throw IOException("Pairing cancelled") }
            socket = plain // Register before connect so cancellation interrupts every network stage.
        }
        plain.connect(InetSocketAddress(target.host, target.port), 8_000)
        plain.soTimeout = 10_000
        plain.tcpNoDelay = true
        val context = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply {
            init(arrayOf(ClientKeyManager(credentials)), arrayOf(PairingBootstrapTrust()), SecureRandom())
        }
        val tls = context.socketFactory.createSocket(plain, target.host, target.port, true) as SSLSocket
        synchronized(lock) {
            if (closed) { tls.close(); throw IOException("Pairing cancelled") }
            // Keep the raw socket registered so closing it aborts TLS without close-notify.
        }
        tls.use {
            it.enabledProtocols = arrayOf("TLSv1.3")
            it.soTimeout = 10_000
            it.startHandshake()
            val binding = Conscrypt.exportKeyingMaterial(it, "adb-label\u0000", null, 64)
                ?: throw IOException("TLS channel binding unavailable")
            val password = code + binding
            try {
                return PairingProtocol.exchange(DataInputStream(it.inputStream), DataOutputStream(it.outputStream),
                    password, credentials.certificate.publicKey as RSAPublicKey)
            } finally { binding.fill(0); password.fill(0) }
        }
    }

    override fun close() {
        val active = synchronized(lock) { closed = true; socket.also { socket = null } }
        active?.close()
    }

    internal class ClientKeyManager(private val credentials: AdbIdentity.Credentials) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            if (keyType == "RSA") arrayOf("adb") else null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
            if (keyType?.contains("RSA") == true) "adb" else null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == "adb") arrayOf(credentials.certificate) else null
        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == "adb") credentials.privateKey else null
    }

    /** AOSP pairing accepts self-signed certificates; this trust manager must never be reused for ADB sessions. */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private class PairingBootstrapTrust : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Pairing client does not accept incoming connections")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("Missing pairing certificate")
            // Do not mark a target paired here. Only the subsequent SPAKE2/AEAD exchange can do that.
        }
    }
}
