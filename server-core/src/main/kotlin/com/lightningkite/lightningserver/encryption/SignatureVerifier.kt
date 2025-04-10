package com.lightningkite.lightningserver.encryption

import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.PublicKeyFactory
import org.bouncycastle.jcajce.provider.asymmetric.util.EC5Util
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.Security
import java.security.Signature
import java.security.spec.ECPublicKeySpec

interface SignatureVerifier {
    fun verify(signature: ByteArray, expected: ByteArray, publicKey: ByteArray): Boolean

    companion object {
        init {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    class ES256 : SignatureVerifier {
        override fun verify(signature: ByteArray, expected: ByteArray, publicKey: ByteArray): Boolean {
            val keyParam = PublicKeyFactory.createKey(publicKey)
            val ecPublicKeyParameters = keyParam as ECPublicKeyParameters
            val ecParameterSpec = EC5Util.convertToSpec(ecPublicKeyParameters.parameters)
            val ecPoint = EC5Util.convertPoint(ecPublicKeyParameters.q)
            val ecPublicKeySpec = ECPublicKeySpec(ecPoint, ecParameterSpec)
            val key = KeyFactory.getInstance("EC").generatePublic(ecPublicKeySpec)

            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(expected)
                return verify(signature)
            }
        }
    }

    class EdDSA : SignatureVerifier {
        override fun verify(signature: ByteArray, expected: ByteArray, publicKey: ByteArray): Boolean {
            val keyParam = PublicKeyFactory.createKey(publicKey)
            return Ed25519Signer().run {
                init(false, keyParam)
                update(expected, 0, expected.size)
                verifySignature(signature)
            }
        }
    }
}