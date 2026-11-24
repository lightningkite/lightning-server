package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.cipher
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.database.HasId
import dev.whyoleg.cryptography.operations.Cipher
import javax.crypto.AEADBadTagException
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Encrypted tokens: `w/<principal>/<base64 of the encrypted Authentication in KotlinWireFormat>`.
 * Tokens from before KotlinWireFormat (`<principal>/`, OldKotlinBytesFormat) are still read.
 */
public class PrivateTinyTokenFormat(
    public val cipher: RuntimeDeferred<Cipher> = secretBasis.cipher("tinyToken"),
    public override val expiration: Duration = 5.minutes,
) : TokenFormat {
    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> create(
        principal: PrincipalType<SUBJECT, ID>,
        auth: Authentication<SUBJECT>,
    ): String {
        val now = now()
        return "w/${principal.name}/" + cipher.await().encrypt(
            server.internalSerialization.kotlinWireFormat.encodeToByteArray(
                Authentication.serializer(principal.subjectSerializer),
                auth.copy(
                    tokenIssuedAt = now,
                    expiration = now.plus(expiration),
                )
            )
        ).let(Base64.UrlSafe::encode)
    }

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> read(
        principal: PrincipalType<SUBJECT, ID>,
        value: String,
    ): Authentication<SUBJECT>? {
        val wirePrefix = "w/${principal.name}/"
        val oldPrefix = "${principal.name}/"
        val prefix = when {
            value.startsWith(wirePrefix) -> wirePrefix
            value.startsWith(oldPrefix) -> oldPrefix
            else -> return null
        }
        try {
            val decoded = Base64.UrlSafe.decode(value.drop(prefix.length))
            val decrypted = cipher.await().decrypt(decoded)

            val format = if (prefix == oldPrefix) server.internalSerialization.oldKotlinBytesFormat
            else server.internalSerialization.kotlinWireFormat
            val auth = try {
                format.decodeFromByteArray(Authentication.serializer(principal.subjectSerializer), decrypted)
            } catch (e: Exception) {
                throw TokenException("Invalid Token Data", e)
            }
            if (prefix == oldPrefix) auth.markCachesAsOldFormat()

            if (auth.expiration != null && now() > auth.expiration!!) throw TokenException("Token has expired")
            return auth
        } catch (e: AEADBadTagException) {
            throw TokenException("Invalid Token", e)
        }
    }
}
