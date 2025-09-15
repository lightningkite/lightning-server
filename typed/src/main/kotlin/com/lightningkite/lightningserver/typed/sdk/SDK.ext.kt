package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.definition.mapItems
import com.lightningkite.lightningserver.pathing.MutablePathSpecMap

internal val SDK.Data.Node.docGroup: String?
    get() = (ancestors + layer).drop(1).takeUnless { it.isEmpty() }?.joinToString(".") { it.info.interfaceName }

public fun SDK.Data.filterSafeEndpoints(): SDK.Data = copy(
    layer = layer.copy(
        endpoints = layer.endpoints.mapValues { (_, endpoints) ->
            MutablePathSpecMap<ServerApiEndpoints>().apply {
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