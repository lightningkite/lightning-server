package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.cipher
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.database.HasId
import dev.whyoleg.cryptography.operations.Cipher
import java.lang.Exception
import javax.crypto.AEADBadTagException
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public class PrivateTinyTokenFormat(
    public val cipher: RuntimeDeferred<Cipher> = secretBasis.cipher("tinyToken"),
    public val expiration: Duration = 5.minutes,
): TokenFormat {
    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> create(
        handler: PrincipalType<SUBJECT, ID>,
        auth: Authentication<SUBJECT>
    ): String =
        handler.name + '/' + cipher.await().encrypt(
            server.internalSerialization.kotlinBytesFormat.encodeToByteArray(
                Authentication.serializer(handler.subjectSerializer),
                auth.copy(expiration = now().plus(expiration))
            )
        ).let(Base64.UrlSafe::encode)

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> read(
        handler: PrincipalType<SUBJECT, ID>,
        value: String
    ): Authentication<SUBJECT>? {
        if (!value.startsWith(handler.name + '/')) return null
        try {
            val decoded = Base64.UrlSafe.decode(value.substringAfter('/'))
            val decrypted = cipher.await().decrypt(decoded)

            val auth = server.internalSerialization.kotlinBytesFormat.decodeFromByteArray(
                Authentication.serializer(handler.subjectSerializer),
                decrypted
            )
            if (auth.expiration != null && now() > auth.expiration!!) throw TokenException("Token has expired")
            return auth
        } catch (e: AEADBadTagException) {
            throw TokenException("Invalid Token", e)
        }
    }
}