package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.definition.MapRegistryExtension
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.getValue
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public interface PrincipalType<SUBJECT: HasId<ID>, ID: Comparable<ID>> {
    public val idSerializer: KSerializer<ID>
    public val subjectSerializer: KSerializer<SUBJECT>

    public val name: String get() = subjectSerializer.descriptor.serialName

    public suspend fun fetch(serverRuntime: ServerRuntime, id: ID): SUBJECT

    public fun hasProperty(property: String): Boolean = subjectSerializer.descriptor.getElementIndex(property) != CompositeDecoder.UNKNOWN_NAME

    context(server: ServerRuntime)
    public fun getProperty(principal: SUBJECT, property: String): String =
        if (property == "$name/_id") server.internalSerialization.json.encodeToString(idSerializer, principal._id)
        else server.internalSerialization.formDataFormat.encodeToMap(subjectSerializer, principal)[property]!!

    context(server: ServerRuntime)
    public suspend fun permitMasquerade(
        from: Authentication<*, *>,
        into: Authentication<SUBJECT, ID>
    ): Boolean = false
}

private object PrincipalTypeRegistry : MapRegistryExtension<String, PrincipalType<*, *>>

public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> ServerBuilder.register(type: PrincipalType<SUBJECT, ID>) {
    extensions[PrincipalTypeRegistry].register(type.name, type)
}
public val ServerDefinition.principalTypes: Map<String, PrincipalType<*, *>> by PrincipalTypeRegistry

