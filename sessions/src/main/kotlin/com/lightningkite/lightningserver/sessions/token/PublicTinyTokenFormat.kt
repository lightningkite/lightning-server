package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.database.HasId
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Signed, unencrypted tokens: `tw/<principal>/<base64 of signature + Authentication in KotlinWireFormat>`.
 * Tokens from before KotlinWireFormat (`tt/<principal>/`, OldKotlinBytesFormat) are still read.
 */
public class PublicTinyTokenFormat(
    public val hasher: RuntimeDeferred<Signer> = secretBasis.signer("public-tiny-token"),
    public override val expiration: Duration = 5.minutes,
) : TokenFormat {
    public val resultSize: RuntimeDeferred<Int> = hasher.mapSuspending { it.sign(byteArrayOf(1, 2, 3)).size }

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> create(
        principal: PrincipalType<SUBJECT, ID>,
        auth: Authentication<SUBJECT>,
    ): String {
        val now = now()
        return "tw/${principal.name}/" + server.internalSerialization.kotlinWireFormat
            .encodeToByteArray(
                Authentication.serializer(principal.subjectSerializer),
                auth.copy(
                    tokenIssuedAt = now,
                    expiration = now.plus(expiration)
                )
            )
            .let { hasher.await().sign(it) + it }
            .let(Base64.UrlSafe::encode)
    }

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> read(
        principal: PrincipalType<SUBJECT, ID>,
        value: String,
    ): Authentication<SUBJECT>? {
        val wirePrefix = "tw/${principal.name}/"
        val oldPrefix = "tt/${principal.name}/"
        val prefix = when {
            value.startsWith(wirePrefix) -> wirePrefix
            value.startsWith(oldPrefix) -> oldPrefix
            else -> return null
        }

        val decoded = Base64.UrlSafe.decode(value.drop(prefix.length))

        val signature = decoded.sliceArray(0 until resultSize.await())
        val data = decoded.sliceArray(resultSize.await() until decoded.size)

        // Good: Signature verification happens before deserialization, preventing tampering
        if (!hasher.await().verify(data, signature)) throw TokenException("Incorrect signature")

        val format = if (prefix == oldPrefix) server.internalSerialization.oldKotlinBytesFormat
        else server.internalSerialization.kotlinWireFormat
        val auth = try {
            format.decodeFromByteArray(Authentication.serializer(principal.subjectSerializer), data)
        } catch (e: Exception) {
            throw TokenException("Invalid Token Data", e)
        }
        if (prefix == oldPrefix) auth.markCachesAsOldFormat()

        if (auth.expiration != null && now() > auth.expiration!!) throw TokenException("Token has expired")
        return auth
    }
}
