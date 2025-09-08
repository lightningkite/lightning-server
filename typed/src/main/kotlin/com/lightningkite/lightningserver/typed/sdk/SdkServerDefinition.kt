package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.definition.ModularServerDefinition
import com.lightningkite.lightningserver.definition.ServerPathEndpoints
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.pathing.MutablePathSpecMap
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpecMap
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ApiWebsocketHandler
import kotlin.collections.component1
import kotlin.collections.component2

public interface ServerApiEndpoints : ServerPathEndpoints {
    override val http: Map<HttpMethod, ApiHttpHandler<*, *, *, *>>
    override val websocket: ApiWebsocketHandler<*, *, *, *, *>?
}

public data class SdkServerDefinition(
    val module: Module,
    val children: Map<PathSpec0, SdkServerDefinition>
) {
    public data class Module(
        val info: SdkModuleInfo,
        val endpoints: Map<InterfaceInfo?, PathSpecMap<ServerApiEndpoints>>
    )

    public data class Node(
        val depth: Int,
        val absolutePath: PathSpec0,
        val module: Module
    )

    /**Returns a sequence of all nested modules as nodes using a depth-first search.*/
    public fun asSequence(): Sequence<Node> = sequence {
        suspend fun SequenceScope<Node>.yield(depth: Int, path: PathSpec0, sdk: SdkServerDefinition) {
            yield(Node(depth, path, sdk.module))
            for ((modPath, mod) in sdk.children.toList().sortedBy { it.second.module.info.interfaceName }) yield(depth + 1, path + modPath, mod)
        }

        yield(depth = 0, PathSpec.root, this@SdkServerDefinition)
    }

    public interface Traverser {
        public val parent: Node?
        public val siblings: List<Node>
        public fun traverseChildrenRecursively()
    }

    private data class NodeTraverser(
        val depth: Int,
        val path: PathSpec0,
        val sdk: SdkServerDefinition,
        private val _parent: NodeTraverser? = null,
        val action: Traverser.(Node) -> Unit
    ) : Traverser {
        val node = Node(depth, path, sdk.module)
        val children by lazy {
            sdk.children
                .toList()
                .sortedBy { it.second.module.info.interfaceName }
                .map { (modPath, mod) ->
                    NodeTraverser(depth + 1, path + modPath, mod, this, action)
                }
        }

        override val parent: Node? get() = _parent?.node
        override val siblings: List<Node> by lazy { _parent?.children?.minus(this)?.map { it.node } ?: emptyList() }

        override fun traverseChildrenRecursively() {
            for (child in children) child.traverse()
        }

        fun traverse() = action(node)
    }

    public fun traverse(action: Traverser.(Node) -> Unit) {
        NodeTraverser(depth = 0, PathSpec.root, this, null, action).traverse()
    }
}

private data class ApiEndpoints(
    override val http: Map<HttpMethod, ApiHttpHandler<*, *, *, *>>,
    override val websocket: ApiWebsocketHandler<*, *, *, *, *>?
) : ServerApiEndpoints {
    constructor(endpoints: ServerPathEndpoints) : this(
        http = buildMap {
            endpoints.http.forEach { (key, endpoint) ->
                if (endpoint is ApiHttpHandler<*, *, *, *>) put(key, endpoint)
            }
        },
        websocket = endpoints.websocket as? ApiWebsocketHandler<*, *, *, *, *>
    )

    fun isNotEmpty() = http.isNotEmpty() || websocket != null
}

public fun ModularServerDefinition.sdk(root: SdkModuleInfo = SdkModuleInfo("Api")): SdkServerDefinition {

    class Builder(val info: SdkModuleInfo) {
        val endpoints = HashMap<InterfaceInfo?, MutablePathSpecMap<ServerApiEndpoints>>()
        val modules = HashMap<PathSpec0, Builder>()

        fun append(relativePath: PathSpec0, module: ModularServerDefinition) {
            endpoints
                .getOrPut(module.definition.extensions[InterfaceInfo], ::MutablePathSpecMap)
                .apply {
                    module.definition.endpoints.asSequence().forEach { entry ->
                        val api = ApiEndpoints(entry.value)
                        if (api.isNotEmpty()) put(relativePath + entry.location, api)
                    }
                }

            for ((path, mod) in module.modules) {
                var modRelPath = relativePath + path

                val builder = module.definition
                    .getModuleInfo(mod.definition)
                    ?.let(::Builder)
                    ?.also {
                        modules[modRelPath] = it
                        modRelPath = PathSpec.root
                    }
                    ?: this

                builder.append(modRelPath, mod)
            }
        }

        fun build(): SdkServerDefinition = SdkServerDefinition(
            SdkServerDefinition.Module(
                info,
                endpoints.toMap()
            ),
            modules.mapValues { (_, mod) -> mod.build() }
        )
    }

    val root = Builder(root)

    root.append(PathSpec.root, this)

    return root.build()
}

public fun ServerBuilder.sdkBuild(root: SdkModuleInfo = SdkModuleInfo("Api")): SdkServerDefinition = modularBuild().sdk(root)
