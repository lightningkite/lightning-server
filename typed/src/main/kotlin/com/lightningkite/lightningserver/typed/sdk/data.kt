package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.ModularServerDefinition
import com.lightningkite.lightningserver.definition.ServerPathEndpoints
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.pathing.MutablePathSpecMap
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpecMap
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ApiWebsocketHandler

public interface ServerApiEndpoints : ServerPathEndpoints {
    override val http: Map<HttpMethod, ApiHttpHandler<*, *, *, *>>
    override val websocket: ApiWebsocketHandler<*, *, *, *, *>?
}

private fun ServerPathEndpoints.filterApiEndpoints(): ServerApiEndpoints =
    object : ServerApiEndpoints {
        private val source get() = this@filterApiEndpoints

        override val http: Map<HttpMethod, ApiHttpHandler<*, *, *, *>> = buildMap {
            source.http.forEach { (key, endpoint) ->
                if (endpoint is ApiHttpHandler<*, *, *, *>) put(key, endpoint)
            }
        }
        override val websocket: ApiWebsocketHandler<*, *, *, *, *>? = source.websocket as? ApiWebsocketHandler<*, *, *, *, *>
    }

public data class SdkServerDefinition(
    val module: Module,
    val nested: Map<PathSpec0, SdkServerDefinition>
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
            for ((modPath, mod) in sdk.nested.toList().sortedBy { it.second.module.info.interfaceName }) yield(depth + 1, path + modPath, mod)
        }

        yield(depth = 0, PathSpec.root, this@SdkServerDefinition)
    }

    public interface Traverser {
        public fun traverseChildren()
    }

    private data class NodeTraverser(
        val depth: Int,
        val path: PathSpec0,
        val sdk: SdkServerDefinition,
        val action: Traverser.(Node) -> Unit
    ) : Traverser {
        val node = Node(depth, path, sdk.module)

        override fun traverseChildren() {
            for ((modPath, mod) in sdk.nested.toList().sortedBy { it.second.module.info.interfaceName })
                NodeTraverser(depth + 1, path + modPath, mod, action).traverse()
        }

        fun traverse() = action(node)
    }

    public fun traverse(action: Traverser.(Node) -> Unit) {
        NodeTraverser(depth = 0, PathSpec.root, this, action).traverse()
    }
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
                        put(relativePath + entry.location, entry.value.filterApiEndpoints())
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
