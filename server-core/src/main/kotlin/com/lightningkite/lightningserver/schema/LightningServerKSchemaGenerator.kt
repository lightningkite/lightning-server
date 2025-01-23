package com.lightningkite.lightningserver.schema

import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.kabobCase
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.titleCase
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.lightningserver.typed.docGroup
import com.lightningkite.registerShared
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.elementNames


val lightningServerKSchema: LightningServerKSchema by lazy {
    val registry = SerializationRegistry(Serialization.module).also {
        it.registerShared()
    }
    Documentable.endpoints.forEach {
        try {
            registry.registerVirtualDeep(it.inputType)
            registry.registerVirtualDeep(it.outputType)
            it.route.path.serializers.forEach { serializer ->
                registry.registerVirtualDeep(serializer)
            }
        } catch(e: Exception) {
            throw IllegalStateException("Failed to generate schema for endpoint ${it.route.endpoint}", e)
        }
    }
    Documentable.websockets.forEach {
        try {
            registry.registerVirtualDeep(it.inputType)
            registry.registerVirtualDeep(it.outputType)
            it.path.serializers.forEach { serializer ->
                registry.registerVirtualDeep(serializer)
            }
        } catch(e: Exception) {
            throw IllegalStateException("Failed to generate schema for websocket ${it.path}", e)
        }
    }
    @Suppress("UNCHECKED_CAST")
    LightningServerKSchema(
        baseUrl = generalSettings().publicUrl,
        baseWsUrl = generalSettings().wsUrl,
        endpoints = Documentable.endpoints.map {
            LightningServerKSchemaEndpoint(
                group = it.docGroup,
                method = it.route.method.toString(),
                path = it.path.path.toString(),
                routes = it.route.path.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }.zip(it.route.path.serializers.map { it.virtualTypeReference(registry) }).associate { it },
                input = it.inputType.virtualTypeReference(registry),
                output = it.outputType.virtualTypeReference(registry),
                summary = it.summary,
                description = it.description,
                docGroup = it.docGroup,
                belongsToInterface = it.belongsToInterface?.let {
                    VirtualTypeReference(it.name, it.subtypes.map { it.virtualTypeReference(registry) }, false)
                },
            )
        }.toList() + Documentable.websockets.map {
            LightningServerKSchemaEndpoint(
                group = it.docGroup,
                method = "WEBSOCKET",
                path = it.path.path.toString(),
                routes = it.path.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }.zip(it.path.serializers.map { it.virtualTypeReference(registry) }).associate { it },
                input = it.inputType.virtualTypeReference(registry),
                output = it.outputType.virtualTypeReference(registry),
                summary = it.summary,
                description = it.description,
                docGroup = it.docGroup,
                belongsToInterface = it.belongsToInterface?.let {
                    VirtualTypeReference(it.name, it.subtypes.map { it.virtualTypeReference(registry) }, false)
                },
            )
        },
        interfaces = Documentable.interfaces.map {
            LightningServerKSchemaInterface(
                path = it.path.toString(),
                matches = VirtualTypeReference(it.name, it.subtypes.map { it.virtualTypeReference(registry) }, false)
            )
        }.toList(),
        enums = registry.virtualTypes.filterValues { it is VirtualEnum } as Map<String, VirtualEnum>,
        structures = registry.virtualTypes.filterValues { it is VirtualStruct } as Map<String, VirtualStruct>,
        aliases = registry.virtualTypes.filterValues { it is VirtualAlias } as Map<String, VirtualAlias>,
    )
}