package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.mapSuspending
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.encryption.sign
import com.lightningkite.lightningserver.encryption.verify
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.database.HasId
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public class PublicTinyTokenFormat(
    public val hasher: RuntimeDeferred<Signer> = secretBasis.signer("public-tiny-token"),
    public val expiration: Duration = 5.minutes,
): TokenFormat {
    public val resultSize: RuntimeDeferred<Int> = hasher.mapSuspending { it.sign(byteArrayOf(1, 2, 3)).size }

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> create(
        principal: PrincipalType<SUBJECT, ID>,
        auth: Authentication<SUBJECT>
    ): String =
        "tt/${principal.name}/" + server.internalSerialization.kotlinBytesFormat
            .encodeToByteArray(
                Authentication.serializer(principal.subjectSerializer),
                auth.copy(expiration = now().plus(expiration))
            )
            .let { hasher.await().sign(it) + it }
            .let(Base64.UrlSafe::encode)

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> read(
        principal: PrincipalType<SUBJECT, ID>,
        value: String
    ): Authentication<SUBJECT>? {
        val prefix = "tt/${principal.name}/"
        if (!value.startsWith(prefix)) return null

        val decoded = Base64.UrlSafe.decode(value.drop(prefix.length))

        val signature = decoded.sliceArray(0 until resultSize.await())
        val data = decoded.sliceArray(resultSize.await() until decoded.size)

        if (!hasher.await().verify(data, signature)) throw TokenException("Incorrect signature")

        val auth = server.internalSerialization.kotlinBytesFormat.decodeFromByteArray(
            Authentication.serializer(principal.subjectSerializer),
            data
        )
        if (auth.expiration != null && now() > auth.expiration!!) throw TokenException("Token has expired")
        return auth
    }
}