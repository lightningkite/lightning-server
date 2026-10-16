@file:OptIn(ExperimentalUnsignedTypes::class)

package com.lightningkite.lightningserver.typed.kschema

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.contract.diffApiContract
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SDK.sdk
import com.lightningkite.services.database.*

private fun InterfaceInfo.virtualTypeReference(registry: SerializationRegistry): VirtualTypeReference =
    VirtualTypeReference(
        type.qualifiedName
            ?: throw NullPointerException("InterfaceInfo $this has no qualified name for virtual type reference"),
        typeParameters.map { it.virtualTypeReference(registry) },
        isNullable = false
    )

/**
 * Captures the raw [LightningServerKSchema] for this server offline.
 *
 * Spins up a throwaway runtime with default settings (no port bound, no services connected) and captures the kschema.
 * No normalization is applied — [diffApiContract] handles ordering/documentation insensitivity itself. Safe to run in CI.
 */
public val ServerBuilder.lightningServerKSchemaFromDefaultRuntime: LightningServerKSchema get() = SDK.withDefaultRuntime(this) { lightningServerKSchema }


public context(runtime: Engine)
val lightningServerKSchema: LightningServerKSchema
    get() {
        val registry = SerializationRegistry(runtime.externalSerialization.serializersModule).apply {
//        register(ServerFile.serializer())
            register(com.lightningkite.lightningserver.LSError.serializer())
        }

        runtime.server.endpoints.forEach { (path, endpoints) ->
            path.wildcards.forEach { registry.registerVirtualDeep(it.serializer) }
            endpoints.http.entries.forEach { (_, handler) ->
                if (handler is ApiHttpHandler<*, *, *, *>) {
                    registry.registerVirtualDeep(handler.inputType)
                    registry.registerVirtualDeep(handler.outputType)
                }
            }
            endpoints.webSocket?.let { handler ->
                if (handler is ApiWebSocketHandler<*, *, *, *, *>) {
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
                val docGroup = node.docGroup

                node.layer.endpoints.flatMap { (interfaceInfo, pathSpecMap) ->
                    val interfaceType = interfaceInfo?.item?.virtualTypeReference(registry)

                    pathSpecMap.asSequence().flatMap { (relativePath, endpoints) ->
                        val routes =
                            relativePath.wildcards.associate { it.name to it.serializer.virtualTypeReference(registry) }
                        val path = (node.absolutePath + relativePath).toString()

                        val http = endpoints.http.map { (method, endpoint) ->
                            LightningServerKSchemaEndpoint(
                                docGroup = docGroup,
                                method = method.toString(),
                                path = path,
                                scopes = endpoint.auth.requiredScopes(),
                                routes = routes,
                                input = endpoint.inputType.virtualTypeReference(registry),
                                output = endpoint.outputType.virtualTypeReference(registry),
                                summary = endpoint.summary,
                                description = endpoint.description,
                                belongsToInterface = interfaceType
                            )
                        }

                        val webSocket = endpoints.webSocket?.let {
                            LightningServerKSchemaEndpoint(
                                docGroup = docGroup,
                                method = HttpMethod.WEBSOCKET.toString(),
                                path = path,
                                scopes = it.auth.requiredScopes(),
                                routes = routes,
                                input = it.inputType.virtualTypeReference(registry),
                                output = it.outputType.virtualTypeReference(registry),
                                summary = it.summary,
                                description = it.description,
                                belongsToInterface = interfaceType
                            )
                        }

                        http + listOfNotNull(webSocket)
                    }
                }
            }.toList(),
            interfaces = sdk.asSequence().flatMap { node ->
                val docGroup = node.docGroup

                node.layer.endpoints.mapNotNull { (interfaceInfo, _) ->
                    if (interfaceInfo == null) return@mapNotNull null

                    LightningServerKSchemaInterface(
                        interfaceInfo.item.virtualTypeReference(registry),
                        docGroup = docGroup,
                        path = (node.absolutePath + interfaceInfo.location).toString()
                    )
                }
            }.toList(),
            enums = registry.virtualTypes.filterValues { it is VirtualEnum } as Map<String, VirtualEnum>,
            structures = registry.virtualTypes.filterValues { it is VirtualStruct } as Map<String, VirtualStruct>,
            sealedStructures = registry.virtualTypes.filterValues { it is VirtualSealed } as Map<String, VirtualSealed>,
            aliases = registry.virtualTypes.filterValues { it is VirtualAlias } as Map<String, VirtualAlias>,
        )
    }