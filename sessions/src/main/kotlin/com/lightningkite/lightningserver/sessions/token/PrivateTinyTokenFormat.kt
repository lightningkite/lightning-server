package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.Encryptor
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.services.database.HasId
import kotlin.time.Duration
import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlin.time.Duration.Companion.minutes

public class PrivateTinyTokenFormat(
    public val encryptor: RuntimeDeferred<Encryptor> = secretBasis.encryptor("tinyToken"),
    public val expiration: Duration = 5.minutes,
): TokenFormat {

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> create(
        handler: PrincipalType<SUBJECT, ID>,
        auth: Authentication<SUBJECT, ID>
    ): String {
        TODO()
    }

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> read(
        handler: PrincipalType<SUBJECT, ID>,
        value: String
    ): Authentication<SUBJECT, ID>? {
        TODO("Not yet implemented")
    }
}