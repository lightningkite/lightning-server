package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.mapSuspending
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.services.database.HasId
import java.io.ByteArrayOutputStream
import kotlin.time.Duration
import java.util.Base64
import kotlin.time.Duration.Companion.minutes

public class PublicTinyTokenFormat(
    public val hasher: RuntimeDeferred<SecureHasher>,
    public val expiration: Duration = 5.minutes,
): TokenFormat {
    public val resultSize: RuntimeDeferred<Int> = hasher.mapSuspending { it.sign(byteArrayOf(1, 2, 3)).size }

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> create(
        handler: PrincipalType<SUBJECT, ID>,
        auth: Authentication<SUBJECT, ID>
    ): String {
        TODO("Not yet implemented")
    }

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> read(
        handler: PrincipalType<SUBJECT, ID>,
        value: String
    ): Authentication<SUBJECT, ID>? {
        TODO("Not yet implemented")
    }
}