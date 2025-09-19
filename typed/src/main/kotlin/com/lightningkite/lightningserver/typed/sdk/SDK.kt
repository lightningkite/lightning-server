package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.auth.AuthRequirement
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
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ApiWebsocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.data.KFile
import kotlinx.serialization.KSerializer
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.reflect.KClass
import kotlin.sequences.forEach

public object SDK { // namespace object
    public interface Documentable {
        public val summary: String
        public val functionName: String get() = summary.functionCase()
        public val description: String
        public val auth: AuthRequirement<*>

        public val inputType: KSerializer<*>
        public val outputType: KSerializer<*>
    }

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
            val endpoints: Map<Locationed<PathSpec0, InterfaceInfo>?, PathSpecMap<ServerApiEndpoints>>
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

    public sealed interface Function : Documentable {
        public val path: PathSpec
        public val fromInterface: InterfaceInfo?
        public val arguments: List<Argument>

        public data class Argument(val name: String, val type: KSerializer<*>)

        public data class Endpoint(
            val handler: ApiHttpHandler<*, *, *, *>,
            val endpoint: HttpEndpoint<PathSpec>,
            override val fromInterface: InterfaceInfo?,
            override val functionName: String = handler.functionName.functionCase()
        ) : Function, Documentable by handler {
            override val path: PathSpec get() = endpoint.path

            override val arguments: List<Argument> get() = path.wildcards
                .map { Argument(it.name, it.serializer) }
                .plus(
                    if (inputType.isUnit()) emptyList()
                    else listOf(Argument("input", inputType))
                )
        }

        public data class Websocket(
            val handler: ApiWebsocketHandler<*, *, *, *, *>,
            override val path: PathSpec,
            override val fromInterface: InterfaceInfo?,
            override val functionName: String = handler.functionName.functionCase()
        ) : Function, Documentable by handler {
            override val arguments: List<Argument> get() =
                path.wildcards.map { Argument(it.name, it.serializer) }
        }
    }

    public data class Module(
        val info: SdkModule.Info,
        /**The relative path of the module to its parent module*/
        val path: PathSpec0,
        val extendsInterfaces: List<Locationed<PathSpec0, InterfaceInfo>>,
        val functions: List<Function>,
        val children: List<Module>
    ) {
        public val declaredFunctions: List<Function> get() = functions.filter { it.fromInterface == null }
        public val functionOverrides: List<Function> get() = functions.filter { it.fromInterface != null }
    }

    public fun ServerDefinition.sdk(root: SdkModule.Info = SdkModule.Info("Api")): Data {
        class Builder(val info: SdkModule.Info) {
            val endpoints = HashMap<Locationed<PathSpec0, InterfaceInfo>?, MutablePathSpecMap<ServerApiEndpoints>>()
            val modules = ArrayList<Locationed<PathSpec0, Builder>>()

            fun append(relativePath: PathSpec0, module: ServerDefinition) {
                endpoints
                    .getOrPut(module.thisLayer.extensions[InterfaceInfo]?.let { Locationed(relativePath, it) }, ::MutablePathSpecMap)
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


    private fun List<Locationed<PathSpec0, InterfaceInfo>>.filterSupertypes(): List<Locationed<PathSpec0, InterfaceInfo>> {
        val supertypes = flatMap { it.item.type.supertypes }.mapNotNull { it.classifier as? KClass<*> }
        return filter { it.item.type !in supertypes }
    }

    private fun Data.processToModules(path: PathSpec0): Module = Module(
        info = layer.info,
        path = path,
        extendsInterfaces = layer.endpoints.keys.filterNotNull().filterSupertypes(),
        functions = layer.endpoints.flatMap { (inter, endpoints) ->
            endpoints.flatMap { (path, endpoints) ->
                val websocket = endpoints.websocket?.let {
                    Function.Websocket(
                        handler = it,
                        path = path,
                        fromInterface = inter?.item,
                    )
                }

                val http = endpoints.http.map { (method, api) ->
                    Function.Endpoint(
                        handler = api,
                        endpoint = HttpEndpoint(path, method),
                        fromInterface = inter?.item,
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

        override suspend fun <T> Task<T>.invoke(input: T): Nothing =
            throw NotImplementedError("SDK Runner only exists to retrieve serialization information")

        override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>): Nothing =
            throw NotImplementedError("SDK Runner only exists to retrieve serialization information")
    }

    public fun ServerBuilder.writeSdk(format: Format, folder: KFile, packageName: String) {
        with(Runtime(this)) {
            settings.readyUsingDefaults()
            writeSdk(format, folder, packageName)
        }
    }

    context(runtime: ServerRuntime)
    public fun ServerBuilder.writeSdk(format: Format, folder: KFile, packageName: String) {
        format.write(build(), folder, packageName)
    }
}