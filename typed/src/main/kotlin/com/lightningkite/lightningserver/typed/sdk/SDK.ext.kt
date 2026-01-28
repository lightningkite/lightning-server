package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.definition.mapItems
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.pathing.buildPathSpecMap
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ApiWebsocketHandler
import com.lightningkite.services.database.nullElement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor

internal val SDK.Data.Node.docGroup: String?
    get() = (ancestors + layer).drop(1).takeUnless { it.isEmpty() }?.joinToString(".") { it.info.interfaceName }

public fun SDK.Data.filterSafeEndpoints(): SDK.Data = copy(
    layer = layer.copy(
        endpoints = layer.endpoints.mapValues { (_, endpoints) ->
            buildPathSpecMap {
                for ((path, endpoints) in endpoints.asSequence()) {
                    put(path, endpoints.filterSafeEndpoints())
                }
            }
        }
    ),
    children = children.mapItems { it.filterSafeEndpoints() }
)

public fun SDK.Module.ensureUniqueNames(): SDK.Module = copy(
    functions = declaredFunctions
        .groupBy { it.functionName to it.arguments }
        .values
        .flatMap { similar ->
            similar.mapIndexed { idx, it ->
                if (idx == 0) it
                else when (it) {
                    is SDK.Function.Endpoint -> it.copy(functionName = it.functionName + (idx + 1))
                    is SDK.Function.Websocket -> it.copy(functionName = it.functionName + (idx + 1))
                }
            }
        }
        .plus(functionOverrides),

    children = children
        .map { it.ensureUniqueNames() }
        .groupBy { it.info.interfaceName }
        .values
        .flatMap { similar ->
            similar.mapIndexed { idx, it ->
                if (idx == 0) it
                else it.copy(
                    info = it.info.copy(
                        interfaceName = it.info.interfaceName + (idx + 1),
                        valueName = it.info.valueName + (idx + 1)
                    )
                )
            }
        }
)

public class TypingGenerationException internal constructor(
    name: String,
    path: Any,
    type: KSerializer<*>,
    cause: Throwable
) : IllegalStateException(
    "Failed to generate typing for $name $path with type ${type.descriptor.serialName}",
    cause
)

public fun ServerRuntime.usedTypes(): Collection<KSerializer<*>> {
    val seen = HashMap<SerialDescriptor, KSerializer<*>>()

    fun registerRecursive(serializer: KSerializer<*>) {
        val real = (serializer.nullElement() ?: serializer).decontextualize()
        if (seen.containsKey(real.descriptor)) return
        seen[real.descriptor] = real
        real.subAndChildSerializers().forEach { registerRecursive(it) }
    }

    server.endpoints.asSequence().forEach { (path, endpoints) ->
        path.wildcards.forEach { arg ->
            try {
                registerRecursive(arg.serializer)
            } catch (e: Exception) {
                throw TypingGenerationException("$arg in", path, arg.serializer, e)
            }
        }
        endpoints.http.forEach { (http, handler) ->
            if (handler !is ApiHttpHandler<*, *, *, *>) return@forEach

            try {
                registerRecursive(handler.inputType)
            } catch (e: Exception) {
                throw TypingGenerationException("input of \"${handler.summary}\" at", HttpEndpoint(path, http), handler.inputType, e)
            }

            try {
                registerRecursive(handler.outputType)
            } catch (e: Exception) {
                throw TypingGenerationException("output of \"${handler.summary}\" at", HttpEndpoint(path, http), handler.outputType, e)
            }
        }
        endpoints.websocket?.let { handler ->
            if (handler !is ApiWebsocketHandler<*, *, *, *, *>) return@let

            try {
                registerRecursive(handler.inputType)
            } catch (e: Exception) {
                throw TypingGenerationException("input of \"${handler.summary}\" at", HttpEndpoint(path, HttpMethod.WEBSOCKET), handler.inputType, e)
            }

            try {
                registerRecursive(handler.outputType)
            } catch (e: Exception) {
                throw TypingGenerationException("output of \"${handler.summary}\" at", HttpEndpoint(path, HttpMethod.WEBSOCKET), handler.outputType, e)
            }
        }
    }

    return seen.values
}
