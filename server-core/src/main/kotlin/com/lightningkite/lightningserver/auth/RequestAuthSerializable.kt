@file:UseContextualSerialization(Instant::class, UUID::class)
package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.Description
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.encryption.TokenException
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.decodeUnwrappingString
import com.lightningkite.lightningserver.serialization.encodeUnwrappingString
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.SettingsSerializer
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.Instant
import java.util.*

@Serializable
data class RequestAuthSerializable(
    val subjectType: String,
    val sessionId: UUID?,
    val id: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    @Description("The scopes permitted.  Null indicates root access.")
    val scopes: Set<String>? = null,
    val cache: CacheKeyMap = CacheKeyMap(mapOf()),
    val thirdParty: String? = null,
)

@Suppress("UNCHECKED_CAST")
fun RequestAuth<*>.serializable(expiresAt: Instant) = RequestAuthSerializable(
    subjectType = subject.name,
    id = Serialization.json.encodeUnwrappingString(subject.idSerializer as KSerializer<Any>, rawId),
    issuedAt = issuedAt,
    scopes = scopes,
    expiresAt = expiresAt,
    cache = cacheKeyMap(),
    sessionId = sessionId,
    thirdParty = thirdParty,
)

fun RequestAuthSerializable.real(subjectHandler: Authentication.SubjectHandler<*, *>? = null): RequestAuth<*> {
    val subject = subjectHandler ?: Authentication.subjects.values.find { it.name == this.subjectType } as? Authentication.SubjectHandler<HasId<Comparable<Any>>, Comparable<Any>>
        ?: throw TokenException("Auth type ${subjectType} not known.")
    if(subjectHandler != null && this.subjectType != subjectHandler.name) throw TokenException("Subject type mismatch")
    if(this.expiresAt < Instant.now()) throw TokenException("Authorization has expired.")
    return RequestAuth(
        subject = subject,
        rawId = Serialization.json.decodeUnwrappingString(subject.idSerializer, id),
        issuedAt = issuedAt,
        scopes = scopes,
        sessionId = sessionId,
        thirdParty = thirdParty,
    ).cacheKeyMap(cache)
}

@Suppress("UNCHECKED_CAST")
fun RequestAuth<*>.cacheKeyMap() = CacheKeyMap(cache as Map<RequestAuth.CacheKey<*, *, *>, RequestAuth.ExpiringValue<*>>)
fun RequestAuth<*>.cacheKeyMap(cache: CacheKeyMap): RequestAuth<*> {
    @Suppress("UNCHECKED_CAST")
    (this as RequestAuth<HasId<Comparable<Any>>>).cache.putAll(cache.map as Map<RequestAuth.CacheKey<HasId<Comparable<Any>>, *, *>, RequestAuth.ExpiringValue<*>>)
    return this
}

object CacheKeyMapSerializer: KSerializer<CacheKeyMap> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CacheKeyMap") {
        for (key in RequestAuth.CacheKey.allCacheKeys) {
            element(key.name, RequestAuth.ExpiringValue.serializer(key.serializer).descriptor, isOptional = true)
        }
    }

    override fun deserialize(decoder: Decoder): CacheKeyMap {
        val map = HashMap<RequestAuth.CacheKey<*, *, *>, RequestAuth.ExpiringValue<*>>()
        decoder.decodeStructure(descriptor) {
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                if (index == CompositeDecoder.UNKNOWN_NAME) throw SerializationException()
                val key = RequestAuth.CacheKey.allCacheKeys[index]
                map[key] = decodeSerializableElement(descriptor, index, RequestAuth.ExpiringValue.serializer(key.serializer))
            }
        }
        return CacheKeyMap(map)
    }

    override fun serialize(encoder: Encoder, value: CacheKeyMap) {
        encoder.encodeStructure(descriptor) {
            for ((key, value) in value.map) {
                val index = RequestAuth.CacheKey.allCacheKeys.indexOf(key)
                if(index == -1) {
                    println("WARNING: Key not registered!")
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                encodeSerializableElement(
                    descriptor,
                    index,
                    RequestAuth.ExpiringValue.serializer(key.serializer as KSerializer<Any?>),
                    value as RequestAuth.ExpiringValue<Any?>
                )
            }
        }
    }
}

@Serializable(CacheKeyMapSerializer::class)
data class CacheKeyMap(val map: Map<RequestAuth.CacheKey<*, *, *>, RequestAuth.ExpiringValue<*>>)