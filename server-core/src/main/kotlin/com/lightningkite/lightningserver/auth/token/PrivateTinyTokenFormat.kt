package com.lightningkite.lightningserver.auth.token

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.encryption.Encryptor
import com.lightningkite.lightningserver.encryption.TokenException
import com.lightningkite.lightningserver.encryption.encryptor
import com.lightningkite.lightningserver.encryption.secretBasis
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.now
import kotlin.time.Duration
import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlin.time.Duration.Companion.minutes

class PrivateTinyTokenFormat(
    val encryptor: () -> Encryptor = secretBasis.encryptor("tinyToken"),
    val expiration: Duration = 5.minutes,
): TokenFormat {
    override fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> create(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        auth: RequestAuth<SUBJECT>
    ): String = handler.name + "/" + run {
        encryptor().encrypt(Serialization.javaData.encodeToByteArray(RequestAuthSerializable.serializer(), auth.serializable(
            now().plus(expiration))))
    }.let { Base64.getUrlEncoder().encodeToString(it) }

    override fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> read(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        value: String
    ): RequestAuth<SUBJECT>? {
        if(!value.startsWith(handler.name + "/")) return null
        try {
            val decoded = Base64.getUrlDecoder().decode(value.substringAfter('/'))
            val plain = encryptor().decrypt(decoded)
            @Suppress("UNCHECKED_CAST")
            return Serialization.javaData.decodeFromByteArray(RequestAuthSerializable.serializer(), plain)
                .real(handler) as? RequestAuth<SUBJECT>
        } catch(e: AEADBadTagException) {
            throw TokenException("Invalid token")
        }
    }
}