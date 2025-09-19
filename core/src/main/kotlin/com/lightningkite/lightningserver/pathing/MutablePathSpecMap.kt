package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.services.data.StringArrayFormat
import kotlinx.serialization.SerializationException


public class MutablePathSpecMap<V>() : PathSpecMap<V> {
    internal inner class Node {
        var path: PathSpec? = null
        var pathValue: V? = null
        var chainedWildcard: PathSpec? = null
        var chainedWildcardValue: V? = null
        val thenConstant = HashMap<String, Node>()
        var thenWildcard: Node? = null
        fun thenWildcard(): Node {
            return thenWildcard ?: run {
                val n = Node()
                this.thenWildcard = n
                n
            }
        }

        operator fun get(after: PathSpec.Afterwards): V? = when (after) {
            PathSpec.Afterwards.None -> pathValue
            PathSpec.Afterwards.TrailingSegments -> chainedWildcardValue
        }

        override fun toString(): String {
            return """
                MutablePathSpecMap.Node(
                    path = $path, 
                    pathValue = $pathValue, 
                    chainedWildcard = $chainedWildcard, 
                    chainedWildcardValue = $chainedWildcardValue, 
                )
                """.trimIndent().lines().joinToString(" ") { it.trim() }
        }
    }

    internal val root = Node()

    public fun put(path: PathSpec, value: V) {
        var current = root
        for (segment in path.segments) {
            current = when (segment) {
                is PathSpec.Segment.Constant -> current.thenConstant.getOrPut(segment.value) { Node() }
                is PathSpec.Segment.Wildcard<*> -> current.thenWildcard()
            }
        }
        when (path.after) {
            PathSpec.Afterwards.None -> {
                current.path = path
                current.pathValue = value
            }

            PathSpec.Afterwards.TrailingSegments -> {
                current.chainedWildcard = path
                current.chainedWildcardValue = value
            }
        }
    }

    public fun putAll(prefix: PathSpec0, map: PathSpecMap<V>) {
        for ((path, value) in map) put(prefix + path, value)
    }

    public fun putAll(map: PathSpecMap<V>) {
        for ((path, value) in map) put(path, value)
    }

    public operator fun set(path: PathSpec, value: V): Unit = put(path, value)

    public fun getOrPut(path: PathSpec, generate: () -> V): V {
        var current = root
        for (segment in path.segments) {
            current = when (segment) {
                is PathSpec.Segment.Constant -> current.thenConstant.getOrPut(segment.value) { Node() }
                is PathSpec.Segment.Wildcard<*> -> current.thenWildcard()
            }
        }
        when (path.after) {
            PathSpec.Afterwards.None -> {
                current.pathValue?.let { return it }
                val value = generate()
                current.path = path
                current.pathValue = value
                return value
            }

            PathSpec.Afterwards.TrailingSegments -> {
                current.chainedWildcardValue?.let { return it }
                val value = generate()
                current.chainedWildcard = path
                current.chainedWildcardValue = value
                return value
            }
        }
    }

    override fun get(key: PathSpec): V? {
        var current = root
        for (segment in key.segments) {
            current = when (segment) {
                is PathSpec.Segment.Constant -> current.thenConstant[segment.value] ?: return null
                is PathSpec.Segment.Wildcard<*> -> current.thenWildcard ?: return null
            }
        }
        return when (key.after) {
            PathSpec.Afterwards.None -> current.pathValue
            PathSpec.Afterwards.TrailingSegments -> current.chainedWildcardValue
        }
    }

    override fun asSequence(): Sequence<Locationed<PathSpec, V>> = sequence {
        suspend fun SequenceScope<Locationed<PathSpec, V>>.entry(path: PathSpec, value: V) =
            yield(Locationed(path, value))

        suspend fun SequenceScope<Locationed<PathSpec, V>>.traverse(node: Node) {
            node.pathValue?.let { entry(node.path!!, it) }
            node.chainedWildcardValue?.let { entry(node.chainedWildcard!!, it) }

            node.thenConstant.values.forEach { traverse(it) }
            node.thenWildcard?.let { traverse(it) }
        }

        traverse(root)
    }

    public override fun <T> match(
        format: StringArrayFormat,
        pathParts: PathSegments,
        getter: (V) -> T?
    ): PathSpecMap.Match<T>? {
        if (pathParts.segments.isEmpty() || pathParts.singleOrNull() == "")
            return (root.path ?: root.thenConstant[""]?.path ?: root.chainedWildcard)?.let {
                val value = root[it.after]?.let(getter) ?: return@let null
                PathSpecMap.Match<T>(
                    pathSpec = PathSpec.root,
                    rawPathArguments = emptyList(),
                    wildcard = if (it.after == PathSpec.Afterwards.TrailingSegments) PathSegments.EMPTY else null,
                    value = value
                )
            }

        val wildcards = ArrayList<String>()
        var current = root
        val soFar = ArrayList<Node>()
        var beyond = false
        for (part in pathParts) {
            soFar.add(current)
//            println("Current is $current, looking for $part")
            val c = current.thenConstant[part]
            if (c != null) {
                current = c
                continue
            }
            val w = current.thenWildcard
            if (w != null) {
                current = w
                wildcards.add(part)
                continue
            }
            beyond = true
            break
        }
//        println("Stopped at $current")
        return if (beyond) {
//            println("Searching for wildcard ending")
            soFar.asReversed().asSequence().mapNotNull {
                it.chainedWildcard?.let { spec ->
                    val value = it.chainedWildcardValue?.let(getter) ?: return@let null
                    PathSpecMap.Match<T>(
                        pathSpec = spec,
                        rawPathArguments = wildcards.zip(spec.wildcards) { v, s ->
                            try {
                                format.decodeFromString(s.serializer, v)
                            } catch(e: SerializationException) {
                                throw BadRequestException("${s.name} in '$spec' is formatted incorrectly", cause = e)
                            }
                        },
                        wildcard = PathSegments(pathParts.drop(spec.segments.size)),
                        value = value,
                    )
                }
            }.firstOrNull()
        } else {
            current.path?.let { spec ->
                val value = current.pathValue?.let(getter) ?: return@let null
                PathSpecMap.Match<T>(
                    pathSpec = spec,
                    rawPathArguments = wildcards.zip(spec.wildcards) { v, s ->
                        try {
                            format.decodeFromString(s.serializer, v)
                        } catch(e: SerializationException) {
                            throw BadRequestException("${s.name} in '$spec' is formatted incorrectly", cause = e)
                        }
                    },
                    wildcard = null,
                    value = value,
                )
            } ?: run {
//                println("Searching for wildcard ending")
                soFar.asReversed().asSequence().mapNotNull {
//                    println("Checking $it")
                    it.chainedWildcard?.let { spec ->
                        val value = it.chainedWildcardValue?.let(getter) ?: return@let null
//                        println("Found value $value")
                        PathSpecMap.Match<T>(
                            pathSpec = spec,
                            rawPathArguments = wildcards.zip(spec.wildcards) { v, s ->
                                try {
                                    format.decodeFromString(s.serializer, v)
                                } catch(e: SerializationException) {
                                    throw BadRequestException("${s.name} in '$spec' is formatted incorrectly", cause = e)
                                }
                            },
                            wildcard = PathSegments(pathParts.drop(spec.segments.size)),
                            value = value,
                        )
                    }
                }.firstOrNull()
            }
        }
    }
}