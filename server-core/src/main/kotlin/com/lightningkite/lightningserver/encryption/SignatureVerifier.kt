package com.lightningkite.lightningserver.encryption

import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.Security
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

interface SignatureVerifier {
    fun verify(signature: ByteArray, expected: ByteArray, publicKey: ByteArray): Boolean

    companion object {
        init {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    class ES256 : SignatureVerifier {
        override fun verify(signature: ByteArray, expected: ByteArray, publicKey: ByteArray): Boolean {
            val keyspec = X509EncodedKeySpec(publicKey)
            val factory = KeyFactory.getInstance("DSA")
            val key = factory.generatePublic(keyspec)

            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(expected)
                return verify(signature)
            }
        }
    }

    class EdDSA : SignatureVerifier {
        override fun verify(signature: ByteArray, expected: ByteArray, publicKey: ByteArray): Boolean {
            val keyspec = X509EncodedKeySpec(publicKey)
            val factory = KeyFactory.getInstance("DSA")
            val key = factory.generatePublic(keyspec)

            Signature.getInstance("Ed25519", "BC").run {
                initVerify(key)
                update(expected)
                return verify(signature)
            }
        }
    }
}