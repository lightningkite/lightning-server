package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.definition.MapRegistryExtension
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.DuplicateRegistrationError
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.getValue
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.serializerOrContextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlin.time.Duration.Companion.minutes

public interface PrincipalType<SUBJECT : HasId<ID>, ID : Comparable<ID>> {
    public val idSerializer: KSerializer<ID>
    public val subjectSerializer: KSerializer<SUBJECT>

    public val name: String get() = subjectSerializer.descriptor.serialName.substringAfterLast('.')

    context(server: ServerRuntime)
    public suspend fun fetch(id: ID): SUBJECT

    public val subjectCacheKey: SerializableCache.Key<SUBJECT>
        get() = SerializableCache.Key(
            "$name-subject",
            subjectSerializer,
            expireAfter = 5.minutes,
            localOnly = true
        )

    public fun hasProperty(property: String): Boolean =
        subjectSerializer.descriptor.getElementIndex(property) != CompositeDecoder.UNKNOWN_NAME

    context(server: ServerRuntime)
    public fun getProperty(principal: SUBJECT, property: String): String? =
        if (property == "$name/_id") idString(principal._id)
        else server.internalSerialization.formDataFormat.encodeToMap(subjectSerializer, principal)[property]

    context(server: ServerRuntime)
    public suspend fun fetchByProperty(property: String, value: String): SUBJECT? {
        return when (property) {
            "$name/_id" -> fetch(server.internalSerialization.stringArrayFormat.decodeFromString(idSerializer, value))
            else -> null
        }
    }

    context(server: ServerRuntime)
    public suspend fun permitMasquerade(
        from: Authentication<*>,
        into: Authentication<SUBJECT>,
    ): Boolean = false

    public val precache: List<AuthCacheKey<SUBJECT, *>> get() = emptyList()

    public companion object;
}

private object PrincipalTypeRegistry : MapRegistryExtension<String, PrincipalType<*, *>>

public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> ServerBuilder.register(type: PrincipalType<SUBJECT, ID>) {
    val registry = extensions[PrincipalTypeRegistry]
    registry[type.name]?.let {
        if (it.subjectSerializer != type.subjectSerializer) throw DuplicateRegistrationError("Encountered two PrincipalTypes with the same name: ${type.name}", it, type)
        return
    }
    registry.register(type.name, type)
}

public val ServerDefinition.principalTypes: Map<String, PrincipalType<*, *>> by PrincipalTypeRegistry

@Suppress("UNCHECKED_CAST")
context(server: ServerRuntime)
public inline fun <reified SUBJECT : HasId<ID>, ID : Comparable<ID>> principalTypeFor(): PrincipalType<SUBJECT, ID> {
    val name = serializerOrContextual<SUBJECT>().descriptor.serialName
    return server.server.principalTypes.values
        .firstOrNull { it.subjectSerializer.descriptor.serialName == name }
            as? PrincipalType<SUBJECT, ID>
        ?: throw IllegalArgumentException("Principal type for $name not found")
}

