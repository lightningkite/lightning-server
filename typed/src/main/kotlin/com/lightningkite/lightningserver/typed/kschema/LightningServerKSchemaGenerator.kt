package com.lightningkite.lightningserver.typed.kschema

import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.LightningServerKSchema
import com.lightningkite.lightningserver.typed.LightningServerKSchemaEndpoint
import com.lightningkite.lightningserver.typed.LightningServerKSchemaInterface
import com.lightningkite.lightningserver.typed.interfaces
import com.lightningkite.lightningserver.typed.locationedApiHttpHandlers
import com.lightningkite.lightningserver.typed.locationedApiWebsocketHandlers
import com.lightningkite.services.database.SerializationRegistry
import com.lightningkite.services.database.VirtualAlias
import com.lightningkite.services.database.VirtualEnum
import com.lightningkite.services.database.VirtualStruct
import com.lightningkite.services.database.VirtualTypeReference
import com.lightningkite.services.database.virtualTypeReference


public context(runtime: ServerRuntime)
val lightningServerKSchema: LightningServerKSchema
    get() {
    val registry = SerializationRegistry(runtime.externalSerialization.serializersModule).also {
        it.registerShared()
    }
    runtime.server.locationedApiHttpHandlers.forEach {
        try {
            registry.registerVirtualDeep(it.value.inputType)
            registry.registerVirtualDeep(it.value.outputType)
            it.key.path.wildcards.forEach { it ->
                registry.registerVirtualDeep(it.serializer)
            }
        } catch(e: Exception) {
            throw IllegalStateException("Failed to generate schema for endpoint ${it.location}", e)
        }
    }
    runtime.server.locationedApiWebsocketHandlers.forEach {
        try {
            registry.registerVirtualDeep(it.value.inputType)
            registry.registerVirtualDeep(it.value.outputType)
            it.key.wildcards.forEach { it ->
                registry.registerVirtualDeep(it.serializer)
            }
        } catch(e: Exception) {
            throw IllegalStateException("Failed to generate schema for websocket ${it.location}", e)
        }
    }
    @Suppress("UNCHECKED_CAST")
    return LightningServerKSchema(
        baseUrl = generalSettings().publicUrl,
        baseWsUrl = generalSettings().wsUrl,
        endpoints = runtime.server.locationedApiHttpHandlers.map {
            val docGroup: String? = null // TODO
            LightningServerKSchemaEndpoint(
                group = docGroup,
                method = it.location.method.toString(),
                path = it.location.path.toString(),
                routes = it.location.path.wildcards.associate { it.name to it.serializer.virtualTypeReference(registry) },
                input = it.value.inputType.virtualTypeReference(registry),
                output = it.value.outputType.virtualTypeReference(registry),
                summary = it.value.summary,
                description = it.value.description,
                docGroup = docGroup,
                belongsToInterface = it.value.belongsToInterface?.let {
                    VirtualTypeReference(it.fullyQualifiedName, it.typeArguments.map { it.virtualTypeReference(registry) }, false)
                },
            )
        }.toList() + runtime.server.locationedApiWebsocketHandlers.map {
            val docGroup: String? = null // TODO
            LightningServerKSchemaEndpoint(
                group = docGroup,
                method = "WEBSOCKET",
                path = it.location.toString(),
                routes = it.location.wildcards.associate { it.name to it.serializer.virtualTypeReference(registry) },
                input = it.value.inputType.virtualTypeReference(registry),
                output = it.value.outputType.virtualTypeReference(registry),
                summary = it.value.summary,
                description = it.value.description,
                docGroup = docGroup,
                belongsToInterface = it.value.belongsToInterface?.let {
                    VirtualTypeReference(it.fullyQualifiedName, it.typeArguments.map { it.virtualTypeReference(registry) }, false)
                },
            )
        },
        interfaces = runtime.server.interfaces.map {
            LightningServerKSchemaInterface(
                path = TODO("Was: it.path.toString()"),
                docGroup = TODO("Was: it.path.docGroup"),
                matches = VirtualTypeReference(it.fullyQualifiedName, it.typeArguments.map { it.virtualTypeReference(registry) }, false)
            )
        }.toList(),
        enums = registry.virtualTypes.filterValues { it is VirtualEnum } as Map<String, VirtualEnum>,
        structures = registry.virtualTypes.filterValues { it is VirtualStruct } as Map<String, VirtualStruct>,
        aliases = registry.virtualTypes.filterValues { it is VirtualAlias } as Map<String, VirtualAlias>,
    )
}