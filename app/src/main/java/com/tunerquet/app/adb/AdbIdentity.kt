package com.tunerquet.app.adb

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/** The private key never leaves AndroidKeyStore and is excluded from app backups. */
class AdbIdentity {
    data class Credentials(val privateKey: PrivateKey, val certificate: X509Certificate)
    @Synchronized fun loadOrCreate(): Credentials {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val now = System.currentTimeMillis()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    // Conscrypt delegates pre-padded RSA-PSS as a raw RSA signing operation.
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setCertificateSubject(X500Principal("CN=Tunerquet ADB"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(now - 86_400_000))
                    .setCertificateNotAfter(Date(now + 10L * 365 * 86_400_000))
                    .build())
            }.generateKeyPair()
        }
        val entry = store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return Credentials(entry.privateKey, entry.certificate as X509Certificate)
    }
    companion object { private const val ALIAS = "tunerquet.adb.identity.v2" }
}
