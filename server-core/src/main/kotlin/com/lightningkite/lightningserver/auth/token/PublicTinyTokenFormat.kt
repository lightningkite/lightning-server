package com.lightningkite.lightningserver.auth.token

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.encryption.TokenException
import com.lightningkite.lightningserver.serialization.Serialization
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.util.Base64

class PublicTinyTokenFormat(
    val hasher: () -> SecureHasher,
    val expiration: Duration = Duration.ofMinutes(5),
): TokenFormat {
    val resultSize by lazy { hasher().sign(byteArrayOf(1, 2, 3)).size }

    override fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> create(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        auth: RequestAuth<SUBJECT>
    ): String = handler.name + "/" + run {
        val out = ByteArrayOutputStream()
        out.write(Serialization.javaData.encodeToByteArray(RequestAuthSerializable.serializer(), auth.serializable(
            Instant.now().plus(expiration))))
        val r = out.toByteArray()
        hasher().sign(r) + r
    }.let { Base64.getUrlEncoder().encodeToString(it) }

    companion object {
        fun readUnsafe(skip: Int, data: String): RequestAuthSerializable = readUnsafe(skip, Base64.getUrlDecoder().decode(data))
        fun readUnsafe(skip: Int, data: ByteArray): RequestAuthSerializable {
            return Serialization.javaData.decodeFromByteArray(RequestAuthSerializable.serializer(), data.sliceArray(skip until data.size))
        }
    }

    override fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> read(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        value: String
    ): RequestAuth<SUBJECT>? {
        if(!value.startsWith(handler.name + "/")) return null
        val decoded = Base64.getUrlDecoder().decode(value.substringAfter('/'))
        val signature = decoded.sliceArray(0 until resultSize)
        val data = decoded.sliceArray(resultSize until decoded.size)
        if(!hasher().verify(data, signature)) throw TokenException("Incorrect signature")
        val decompressed = data
        @Suppress("UNCHECKED_CAST")
        return Serialization.javaData.decodeFromByteArray(RequestAuthSerializable.serializer(), decompressed).real(handler) as? RequestAuth<SUBJECT>
    }
}