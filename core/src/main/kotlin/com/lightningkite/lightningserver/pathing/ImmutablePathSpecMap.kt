package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.services.data.StringArrayFormat
import kotlinx.serialization.SerializationException

public class ImmutablePathSpecMap<V>(start: MutablePathSpecMap<V>) : PathSpecMap<V> {
    private inner class Node(
        val path: PathSpec? = null,
        val pathValue: V? = null,
        val chainedWildcard: PathSpec? = null,
        val chainedWildcardValue: V? = null,
        val thenConstant: Map<String, Node>,
        val thenWildcard: Node? = null,
    ) {
        constructor(from: MutablePathSpecMap<V>.Node) : this(
            from.path,
            from.pathValue,
            from.chainedWildcard,
            from.chainedWildcardValue,
            from.thenConstant.mapValues { Node(it.value) },
            from.thenWildcard?.let(::Node)
        )

        operator fun get(after: PathSpec.Afterwards): V? = when (after) {
            PathSpec.Afterwards.None -> pathValue
            PathSpec.Afterwards.TrailingSegments -> chainedWildcardValue
        }

        override fun toString(): String {
            return """
                ImmutablePathSpecMap.Node(
                    path = $path, 
                    pathValue = $pathValue, 
                    chainedWildcard = $chainedWildcard, 
                    chainedWildcardValue = $chainedWildcardValue, 
                )
            """.trimIndent().lines().joinToString(" ") { it.trim() }
        }
    }

    private val root = Node(start.root)

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
        getter: (V) -> T?,
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
        for ((idx, part) in pathParts.withIndex()) {
            soFar.add(current)
//            println("Current is $current, looking for $part")
            val c = current.thenConstant[part]
            if (c != null) {
                current = c
                continue
            }
            val w = current.thenWildcard
            if (w != null) {
                if (idx == pathParts.lastIndex && part == "") continue  // empty trailing segments are not arguments
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
                            } catch (e: SerializationException) {
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
                        } catch (e: SerializationException) {
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
                                } catch (e: SerializationException) {
                                    throw BadRequestException(
                                        "${s.name} in '$spec' is formatted incorrectly",
                                        cause = e
                                    )
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