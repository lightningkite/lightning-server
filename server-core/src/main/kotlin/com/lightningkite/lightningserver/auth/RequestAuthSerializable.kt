package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.Description
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.encodeUnwrappingString
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class RequestAuthSerializable(
    val subjectType: String,
    val id: String,
    @Contextual val issuedAt: Instant,
    @Description("The scopes permitted.  Null indicates root access.")
    val scopes: Set<String>? = null,
    val cachedRaw: Map<String, String> = mapOf(),
    val thirdParty: String? = null,
)

@Suppress("UNCHECKED_CAST")
fun RequestAuth<*>.serializable() = RequestAuthSerializable(
    subjectType = subject.name,
    id = Serialization.json.encodeUnwrappingString(subject.idSerializer as KSerializer<Any>, rawId),
    issuedAt = issuedAt,
    scopes = scopes,
    cachedRaw = cachedRaw,
    thirdParty = thirdParty,
)

fun RequestAuthSerializable.real(): RequestAuth<*> {
    val subject = Authentication.subjects.values.find { it.name == this.subjectType } as? Authentication.SubjectHandler<HasId<Comparable<Any>>, Comparable<Any>>
        ?: throw IllegalArgumentException("Auth type ${subjectType} not known.")
    return RequestAuth(
        subject = subject,
        rawId = id,
        issuedAt = issuedAt,
        scopes = scopes,
        cachedRaw = cachedRaw,
        thirdParty = thirdParty,
    )
}