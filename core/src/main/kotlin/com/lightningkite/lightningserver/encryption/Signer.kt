@file:Suppress("FunctionName", "ClassName")

package com.lightningkite.lightningserver.encryption

import dev.whyoleg.cryptography.CryptographyAlgorithmId
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.Digest
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA512
import dev.whyoleg.cryptography.operations.SignatureGenerator
import dev.whyoleg.cryptography.operations.SignatureVerifier
import kotlin.io.encoding.Base64

public interface Signer {
    public val generator: SignatureGenerator
    public val verifier: SignatureVerifier

    public data class HMAC(public val key: HMAC.Key) : Signer {
        override val generator: SignatureGenerator get() = key.signatureGenerator()
        override val verifier: SignatureVerifier get() = key.signatureVerifier()
    }

    public data class CMAC(public val key: AES.CMAC.Key) : Signer {
        override val generator: SignatureGenerator get() = key.signatureGenerator()
        override val verifier: SignatureVerifier get() = key.signatureVerifier()
    }

    public data class ECDSA(
        public val keyPair: ECDSA.KeyPair,
        public val digest: CryptographyAlgorithmId<Digest>,
        public val format: ECDSA.SignatureFormat
    ) : Signer {
        override val generator: SignatureGenerator get() = keyPair.privateKey.signatureGenerator(digest, format)
        override val verifier: SignatureVerifier get() = keyPair.publicKey.signatureVerifier(digest, format)
    }

    public data class RSA_PSS(public val keyPair: RSA.PSS.KeyPair) : Signer {
        override val generator: SignatureGenerator get() = keyPair.privateKey.signatureGenerator()
        override val verifier: SignatureVerifier get() = keyPair.publicKey.signatureVerifier()
    }

    public data class RSA_PKCS1(public val keyPair: RSA.PKCS1.KeyPair) : Signer {
        override val generator: SignatureGenerator get() = keyPair.privateKey.signatureGenerator()
        override val verifier: SignatureVerifier get() = keyPair.publicKey.signatureVerifier()
    }

    public data class WithId(public val signer: Signer, public val id: String) : Signer by signer
}

public suspend fun Signer.sign(bytes: ByteArray): ByteArray = generator.generateSignature(bytes)
public suspend fun Signer.verify(bytes: ByteArray, signature: ByteArray): Boolean = verifier.tryVerifySignature(bytes, signature)

public fun Signer.signBlocking(bytes: ByteArray): ByteArray = generator.generateSignatureBlocking(bytes)
public fun Signer.verifyBlocking(bytes: ByteArray, signature: ByteArray): Boolean = verifier.tryVerifySignatureBlocking(bytes, signature)


public fun HMAC.Key.signer(): Signer = Signer.HMAC(this)
public fun AES.CMAC.Key.signer(): Signer = Signer.CMAC(this)

public fun ECDSA.KeyPair.signer(
    digest: CryptographyAlgorithmId<Digest> = SHA512,
    format: ECDSA.SignatureFormat = ECDSA.SignatureFormat.RAW
): Signer = Signer.ECDSA(this, digest, format)

public fun RSA.PSS.KeyPair.signer(): Signer = Signer.RSA_PSS(this)
public fun RSA.PKCS1.KeyPair.signer(): Signer = Signer.RSA_PKCS1(this)

public suspend fun Signer.sign(string: String): String = Base64.encode(sign(string.encodeToByteArray()))
public suspend fun Signer.verify(string: String, signature: String): Boolean = verify(string.encodeToByteArray(), signature.encodeToByteArray())