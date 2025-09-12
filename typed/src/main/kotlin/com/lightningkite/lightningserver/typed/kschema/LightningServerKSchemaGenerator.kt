package com.lightningkite.lightningserver.typed.kschema

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ApiWebsocketHandler
import com.lightningkite.lightningserver.typed.LightningServerKSchema
import com.lightningkite.lightningserver.typed.LightningServerKSchemaEndpoint
import com.lightningkite.lightningserver.typed.LightningServerKSchemaInterface
import com.lightningkite.lightningserver.typed.sdk.InterfaceInfo
import com.lightningkite.lightningserver.typed.sdk.SDK.sdk
import com.lightningkite.lightningserver.typed.sdk.groupName
import com.lightningkite.lightningserver.typed.sdk.filterSafeEndpoints
import com.lightningkite.services.database.SerializationRegistry
import com.lightningkite.services.database.VirtualAlias
import com.lightningkite.services.database.VirtualEnum
import com.lightningkite.services.database.VirtualStruct
import com.lightningkite.services.database.VirtualTypeReference
import com.lightningkite.services.database.virtualTypeReference
import kotlin.collections.component1
import kotlin.collections.component2

private fun InterfaceInfo.virtualTypeReference(registry: SerializationRegistry): VirtualTypeReference =
    VirtualTypeReference(
        type.qualifiedName ?: throw NullPointerException("InterfaceInfo $this has no qualified name for virtual type reference"),
        typeParameters.map { it.virtualTypeReference(registry) },
        isNullable = false
    )

public context(runtime: ServerRuntime)
val lightningServerKSchema: LightningServerKSchema get() {
    val registry = SerializationRegistry(runtime.externalSerialization.serializersModule).also {
        it.registerShared()
    }

    runtime.server.endpoints.forEach { (path, endpoints) ->
        path.wildcards.forEach { registry.registerVirtualDeep(it.serializer) }
        endpoints.http.entries.forEach { (_, handler) ->
            if (handler is ApiHttpHandler<*, *, *, *>) {
                registry.registerVirtualDeep(handler.inputType)
                registry.registerVirtualDeep(handler.outputType)
            }
        }
        endpoints.websocket?.let { handler ->
            if (handler is ApiWebsocketHandler<*, *, *, *, *>) {
                registry.registerVirtualDeep(handler.inputType)
                registry.registerVirtualDeep(handler.outputType)
            }
        }
    }

    val sdk = runtime.server.sdk().filterSafeEndpoints()

    @Suppress("UNCHECKED_CAST")
    return LightningServerKSchema(
        baseUrl = generalSettings().publicUrl,
        baseWsUrl = generalSettings().wsUrl,
        endpoints = sdk.asSequence().flatMap { node ->
            val docGroup = node.groupName

            node.layer.endpoints.flatMap { (interfaceInfo, pathSpecMap) ->
                val interfaceType = interfaceInfo?.virtualTypeReference(registry)

                pathSpecMap.asSequence().flatMap { (path, endpoints) ->
                    val routes = path.wildcards.associate { it.name to it.serializer.virtualTypeReference(registry) }

                    val http = endpoints.http.map { (method, endpoint) ->
                        LightningServerKSchemaEndpoint(
                            docGroup = docGroup,
                            method = method.toString(),
                            path = path.toString(),
                            scopes = endpoint.auth.scopes,
                            routes = routes,
                            input = endpoint.inputType.virtualTypeReference(registry),
                            output = endpoint.outputType.virtualTypeReference(registry),
                            summary = endpoint.summary,
                            description = endpoint.description,
                            belongsToInterface = interfaceType
                        )
                    }

                    val websocket = endpoints.websocket?.let {
                        LightningServerKSchemaEndpoint(
                            docGroup = docGroup,
                            method = HttpMethod.WEBSOCKET.toString(),
                            path = path.toString(),
                            scopes = it.auth.scopes,
                            routes = routes,
                            input = it.inputType.virtualTypeReference(registry),
                            output = it.outputType.virtualTypeReference(registry),
                            summary = it.summary,
                            description = it.description,
                            belongsToInterface = interfaceType
                        )
                    }

                    http + listOfNotNull(websocket)
                }
            }
        }.toList(),
        interfaces = sdk.asSequence().flatMap { node ->
            val docGroup = node.groupName

            node.layer.endpoints.mapNotNull { (interfaceInfo, _) ->
                if (interfaceInfo == null) return@mapNotNull null

                LightningServerKSchemaInterface(
                    interfaceInfo.virtualTypeReference(registry),
                    docGroup = docGroup,
                    path = node.absolutePath.toString()
                )
            }
        }.toList(),
        enums = registry.virtualTypes.filterValues { it is VirtualEnum } as Map<String, VirtualEnum>,
        structures = registry.virtualTypes.filterValues { it is VirtualStruct } as Map<String, VirtualStruct>,
        aliases = registry.virtualTypes.filterValues { it is VirtualAlias } as Map<String, VirtualAlias>,
    )
}