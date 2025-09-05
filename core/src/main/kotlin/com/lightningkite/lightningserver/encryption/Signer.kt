@file:Suppress("FunctionName", "ClassName")

package com.lightningkite.lightningserver.encryption

import dev.whyoleg.cryptography.CryptographyAlgorithmId
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.Digest
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.SHA384
import dev.whyoleg.cryptography.algorithms.SHA512
import dev.whyoleg.cryptography.operations.SignatureGenerator
import dev.whyoleg.cryptography.operations.SignatureVerifier
import kotlin.io.encoding.Base64

public interface Signer {
    public val generator: SignatureGenerator
    public val verifier: SignatureVerifier
    public val name: String

    public data class HMAC(public val key: HMAC.Key, override val name: String) : Signer {
        override val generator: SignatureGenerator get() = key.signatureGenerator()
        override val verifier: SignatureVerifier get() = key.signatureVerifier()
    }

    public data class CMAC(public val key: AES.CMAC.Key, override val name: String) : Signer {
        override val generator: SignatureGenerator get() = key.signatureGenerator()
        override val verifier: SignatureVerifier get() = key.signatureVerifier()
    }

    public data class ECDSA(
        public val keyPair: ECDSA.KeyPair,
        public val digest: CryptographyAlgorithmId<Digest>,
        public val format: ECDSA.SignatureFormat,
        override val name: String
    ) : Signer {
        override val generator: SignatureGenerator get() = keyPair.privateKey.signatureGenerator(digest, format)
        override val verifier: SignatureVerifier get() = keyPair.publicKey.signatureVerifier(digest, format)
    }

    // Helper functions for this is not available, because the library RSA classes are all internal, so we cannot know
    // what algorithm was used to create the key, so we cannot provide a proper name. You must make this yourself
    public data class RSA_PSS(public val keyPair: RSA.PSS.KeyPair, override val name: String /*Example: RS256*/) : Signer {
        override val generator: SignatureGenerator get() = keyPair.privateKey.signatureGenerator()
        override val verifier: SignatureVerifier get() = keyPair.publicKey.signatureVerifier()

    }

    // Helper functions for this is not available, because the library RSA classes are all internal, so we cannot know
    // what algorithm was used to create the key, so we cannot provide a proper name. You must make this yourself
    public data class RSA_PKCS1(public val keyPair: RSA.PKCS1.KeyPair, override val name: String /*Example: PS256*/) : Signer {
        override val generator: SignatureGenerator get() = keyPair.privateKey.signatureGenerator()
        override val verifier: SignatureVerifier get() = keyPair.publicKey.signatureVerifier()
    }
}

public suspend fun Signer.sign(bytes: ByteArray): ByteArray = generator.generateSignature(bytes)
public suspend fun Signer.verify(bytes: ByteArray, signature: ByteArray): Boolean = verifier.tryVerifySignature(bytes, signature)

public fun Signer.signBlocking(bytes: ByteArray): ByteArray = generator.generateSignatureBlocking(bytes)
public fun Signer.verifyBlocking(bytes: ByteArray, signature: ByteArray): Boolean = verifier.tryVerifySignatureBlocking(bytes, signature)


public suspend fun Signer.sign(string: String): String = Base64.encode(sign(string.encodeToByteArray()))
public suspend fun Signer.verify(string: String, signature: String): Boolean = verify(string.encodeToByteArray(), signature.encodeToByteArray())

public fun ECDSA.KeyPair.ES256(format: ECDSA.SignatureFormat = ECDSA.SignatureFormat.RAW): Signer = Signer.ECDSA(this, SHA256, format, "ES256")
public fun ECDSA.KeyPair.ES384(format: ECDSA.SignatureFormat = ECDSA.SignatureFormat.RAW): Signer = Signer.ECDSA(this, SHA384, format, "ES384")
public fun ECDSA.KeyPair.ES512(format: ECDSA.SignatureFormat = ECDSA.SignatureFormat.RAW): Signer = Signer.ECDSA(this, SHA512, format, "ES512")
