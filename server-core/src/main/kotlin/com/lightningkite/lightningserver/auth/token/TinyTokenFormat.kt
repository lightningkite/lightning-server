package com.lightningkite.lightningserver.auth.token

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.encryption.TokenException
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.sign

class TinyTokenFormat(
    val hasher: () -> SecureHasher,
): TokenFormat {
    val resultSize by lazy { hasher().sign(byteArrayOf(1, 2, 3)).size }

    override fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> create(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        auth: RequestAuth<SUBJECT>
    ): String = run {
        val out = ByteArrayOutputStream()
        out.write(Serialization.javaData.encodeToByteArray(RequestAuthSerializable.serializer(), auth.serializable()))
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
    ): RequestAuth<SUBJECT>? = try {
        val decoded = Base64.getUrlDecoder().decode(value)
        val signature = decoded.sliceArray(0 until resultSize)
        val data = decoded.sliceArray(resultSize until decoded.size)
        if(!hasher().verify(data, signature)) throw TokenException("Incorrect signature")
        val decompressed = data
        @Suppress("UNCHECKED_CAST")
        Serialization.javaData.decodeFromByteArray(RequestAuthSerializable.serializer(), decompressed).real(handler) as? RequestAuth<SUBJECT>
    } catch( e: Exception) {
        null
    }
}