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
        return "tt/${principal.name}/" + server.internalSerialization.kotlinBytesFormat
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
        val prefix = "tt/${principal.name}/"
        if (!value.startsWith(prefix)) return null

        val decoded = Base64.UrlSafe.decode(value.drop(prefix.length))

        val signature = decoded.sliceArray(0 until resultSize.await())
        val data = decoded.sliceArray(resultSize.await() until decoded.size)

        // Good: Signature verification happens before deserialization, preventing tampering
        if (!hasher.await().verify(data, signature)) throw TokenException("Incorrect signature")

        val auth = try {
            server.internalSerialization.kotlinBytesFormat.decodeFromByteArray(
                Authentication.serializer(principal.subjectSerializer),
                data
            )
        } catch (e: Exception) {
            throw TokenException("Invalid Token Data", e)
        }

        if (auth.expiration != null && now() > auth.expiration!!) throw TokenException("Token has expired")
        return auth
    }
}