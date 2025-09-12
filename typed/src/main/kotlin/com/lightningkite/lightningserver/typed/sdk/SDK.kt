package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.mapItems
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.pathing.MutablePathSpecMap
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpecMap
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.ServerRuntimeBase
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.data.KFile
import kotlinx.serialization.KSerializer
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.sequences.forEach

public object SDK { // namespace object
    public interface Format {
        context(server: ServerRuntime)
        public fun write(data: ServerDefinition, folder: KFile, packageName: String)
    }

    public data class Data(
        val layer: Layer,
        val children: List<Locationed<PathSpec0, Data>>
    ) {
        public data class Layer(
            val info: SdkModule.Info,
            val endpoints: Map<InterfaceInfo?, PathSpecMap<ServerApiEndpoints>>
        )

        public data class Node(
            val ancestors: List<Layer>,
            val absolutePath: PathSpec0,
            val layer: Layer
        ) {
            public val depth: Int get() = ancestors.size
        }

        /**Returns a sequence of all nested modules as nodes using a depth-first search.*/
        public fun asSequence(): Sequence<Node> = sequence {
            suspend fun SequenceScope<Node>.yield(ancestors: List<Layer>, path: PathSpec0, data: Data) {
                yield(Node(ancestors, path, data.layer))
                for ((modPath, child) in data.children.sortedBy { it.item.layer.info.interfaceName })
                    yield(ancestors + data.layer, path + modPath, child)
            }

            yield(emptyList(), PathSpec.root, this@Data)
        }
    }

    public sealed interface Function {
        public val name: String
        public val path: PathSpec
        public val fromInterface: InterfaceInfo?
        public val arguments: List<Argument>

        public data class Argument(val name: String, val type: KSerializer<*>)

        public data class Endpoint(
            override val name: String,
            val endpoint: HttpEndpoint<PathSpec>,
            override val fromInterface: InterfaceInfo?,
            val input: KSerializer<*>,
            val output: KSerializer<*>
        ) : Function {
            override val path: PathSpec get() = endpoint.path

            override val arguments: List<Argument> get() = path.wildcards
                .map { Argument(it.name, it.serializer) }
                .plus(
                    if (input.isUnit()) emptyList()
                    else listOf(Argument("input", input))
                )
        }

        public data class Websocket(
            override val name: String,
            override val path: PathSpec,
            override val fromInterface: InterfaceInfo?,
            val inputType: KSerializer<*>,
            val outputType: KSerializer<*>
        ) : Function {
            override val arguments: List<Argument> get() =
                path.wildcards.map { Argument(it.name, it.serializer) }
        }
    }

    public data class Module(
        val info: SdkModule.Info,
        /**The relative path of the module to its parent module*/
        val path: PathSpec0,
        val extendsInterfaces: List<InterfaceInfo>,
        val functions: List<Function>,
        val children: List<Module>
    ) {
        public val declaredFunctions: List<Function> get() = functions.filter { it.fromInterface == null }
        public val functionOverrides: List<Function> get() = functions.filter { it.fromInterface != null }
    }

    public fun ServerDefinition.sdk(root: SdkModule.Info = SdkModule.Info("Api")): Data {
        class Builder(val info: SdkModule.Info) {
            val endpoints = HashMap<InterfaceInfo?, MutablePathSpecMap<ServerApiEndpoints>>()
            val modules = ArrayList<Locationed<PathSpec0, Builder>>()

            fun append(relativePath: PathSpec0, module: ServerDefinition) {
                endpoints
                    .getOrPut(module.thisLayer.extensions[InterfaceInfo], ::MutablePathSpecMap)
                    .apply {
                        module.thisLayer.endpoints.asSequence().forEach { entry ->
                            val api = ServerApiEndpoints(entry.value)
                            if (api.isNotEmpty()) put(relativePath + entry.location, api)
                        }
                    }

                for ((path, mod) in module.modules) {
                    var modRelPath = relativePath + path

                    val builder = module.thisLayer
                        .getModuleInfo(mod.thisLayer)
                        ?.let(::Builder)
                        ?.also {
                            modules.add(Locationed(modRelPath, it))
                            modRelPath = PathSpec.root
                        }
                        ?: this

                    builder.append(modRelPath, mod)
                }
            }

            fun build(): Data = Data(
                Data.Layer(
                    info,
                    endpoints.toMap()
                ),
                modules.mapItems { it.build() }
            )
        }

        val root = Builder(root)

        root.append(PathSpec.root, this)

        return root.build()
    }

    private fun Data.processToModules(path: PathSpec0): Module = Module(
        info = layer.info,
        path = path,
        extendsInterfaces = layer.endpoints.keys.filterNotNull(),
        functions = layer.endpoints.flatMap { (inter, endpoints) ->
            endpoints.flatMap { (path, endpoints) ->
                val websocket = endpoints.websocket?.let {
                    Function.Websocket(
                        name = it.functionName,
                        path = path,
                        fromInterface = inter,
                        inputType = it.inputType,
                        outputType = it.outputType
                    )
                }

                val http = endpoints.http.map { (method, api) ->
                    Function.Endpoint(
                        name = api.functionName,
                        endpoint = HttpEndpoint(path, method),
                        fromInterface = inter,
                        input = api.inputType,
                        output = api.outputType
                    )
                }

                http + listOfNotNull(websocket)
            }
        },
        children = children.map { (path, def) -> def.processToModules(path) },
    )
    public fun Data.processToModules(): Module = processToModules(PathSpec.root)

    public open class GenerationException(override val message: String) : Exception()

    private class Runtime(server: ServerBuilder) : ServerRuntimeBase(server.build()) {
        override val serverId: String = "SDK Runtime"
        override val serverVersion: String = "0.0.0"

        override suspend fun <T> Task<T>.invoke(input: T) =
            throw NotImplementedError("SDK Runner only exists to retrieve serialization information")

        override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>) =
            throw NotImplementedError("SDK Runner only exists to retrieve serialization information")
    }

    public fun ServerBuilder.writeSdk(format: Format, folder: KFile, packageName: String) {
        context(Runtime(this)) {
            format.write(build(), folder, packageName)
        }
    }
}