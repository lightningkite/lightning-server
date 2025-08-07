package com.lightningkite.lightningserver

import com.lightningkite.services.data.StringArrayFormat

public interface PathSpecMap<out V> {
    public operator fun get(path: PathSpec): V?
    public fun match(format: StringArrayFormat, pathParts: List<String>, endingSlash: Boolean): Match<V>?
    public fun match(format: StringArrayFormat, string: String): Match<V>? = match(format, string.split('/').filter { it.isNotEmpty() }, string.endsWith('/'))
    public fun asSequence(): Sequence<Pair<PathSpec, V>>

    public class Match<out V>(
        override val pathSpec: PathSpec,
        override val rawPathArguments: List<Any?>,
        override val wildcard: List<String>?,
        public val value: V?
    ): PathSpecResolvable<PathSpec>
}

public class MutablePathSpecMap<V>(): PathSpecMap<V> {
    private inner class Node {
        var path: PathSpec? = null
        var pathValue: V? = null
        var trailingSlash: PathSpec? = null
        var trailingSlashValue: V? = null
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
        operator fun get(after: PathSpec.Afterwards): V? = when(after) {
            PathSpec.Afterwards.None -> pathValue
            PathSpec.Afterwards.TrailingSlash -> trailingSlashValue
            PathSpec.Afterwards.TrailingSegments -> chainedWildcardValue
        }
    }
    private val root = Node()
    public fun put(path: PathSpec, value: V) {
        var current = root
        for(segment in path.segments) {
            current = when(segment) {
                is PathSpec.Segment.Constant -> current.thenConstant.getOrPut(segment.value) { Node() }
                is PathSpec.Segment.Wildcard<*> -> current.thenWildcard()
            }
        }
        when(path.after) {
            PathSpec.Afterwards.None -> {
                current.path = path
                current.pathValue = value
            }
            PathSpec.Afterwards.TrailingSlash -> {
                current.trailingSlash = path
                current.trailingSlashValue = value
            }
            PathSpec.Afterwards.TrailingSegments -> {
                current.chainedWildcard = path
                current.chainedWildcardValue = value
            }
        }
    }

    public fun getOrPut(path: PathSpec, generate: ()->V): V {
        var current = root
        for(segment in path.segments) {
            current = when(segment) {
                is PathSpec.Segment.Constant -> current.thenConstant.getOrPut(segment.value) { Node() }
                is PathSpec.Segment.Wildcard<*> -> current.thenWildcard()
            }
        }
        when(path.after) {
            PathSpec.Afterwards.None -> {
                current.pathValue?.let { return it }
                val value = generate()
                current.path = path
                current.pathValue = value
                return value
            }
            PathSpec.Afterwards.TrailingSlash -> {
                current.trailingSlashValue?.let { return it }
                val value = generate()
                current.trailingSlash = path
                current.trailingSlashValue = value
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

    override fun get(path: PathSpec): V? {
        var current = root
        for(segment in path.segments) {
            current = when(segment) {
                is PathSpec.Segment.Constant -> current.thenConstant[segment.value] ?: return null
                is PathSpec.Segment.Wildcard<*> -> current.thenWildcard ?: return null
            }
        }
        return when(path.after) {
            PathSpec.Afterwards.None -> current.pathValue
            PathSpec.Afterwards.TrailingSlash -> current.trailingSlashValue
            PathSpec.Afterwards.TrailingSegments -> current.chainedWildcardValue
        }
    }

    override fun asSequence(): Sequence<Pair<PathSpec, V>> = sequence {
        suspend fun SequenceScope<Pair<PathSpec, V>>.traverse(node: Node) {
            node.pathValue?.let { yield(node.path!! to it) }
            node.trailingSlashValue?.let { yield(node.trailingSlash!! to it) }
            node.chainedWildcardValue?.let { yield(node.chainedWildcard!! to it) }
        }
        traverse(root)
    }

    public override fun match(format: StringArrayFormat, pathParts: List<String>, endingSlash: Boolean): PathSpecMap.Match<V>? {
        if (pathParts.isEmpty())
            return (root.path ?: root.trailingSlash ?: root.chainedWildcard)?.let {
                PathSpecMap.Match<V>(PathSpec.root, listOf(), if (it.after == PathSpec.Afterwards.TrailingSegments) listOf() else null, root[it.after])
            }

        val wildcards = ArrayList<String>()
        var current = root
        val soFar = arrayListOf<Node>()
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
                    PathSpecMap.Match<V>(
                        pathSpec = spec,
                        rawPathArguments = wildcards.zip(spec.wildcards) { v, s -> format.decodeFromString(s.serializer, v) },
                        wildcard = pathParts.drop(spec.segments.size),
                        value = it.chainedWildcardValue,
                    )
                }
            }.firstOrNull()
        } else if (endingSlash) {
//            println("Pulling trailingSlash")
            current.trailingSlash?.let { spec ->
                PathSpecMap.Match<V>(
                    pathSpec = spec,
                    rawPathArguments = wildcards.zip(spec.wildcards) { v, s -> format.decodeFromString(s.serializer, v) },
                    wildcard = null,
                    value = current.trailingSlashValue,
                )
            }
        } else {
//            println("Pulling path")
            current.path?.let { it ->
                PathSpecMap.Match<V>(
                    pathSpec = it,
                    rawPathArguments = wildcards.zip(it.wildcards) { v, s -> format.decodeFromString(s.serializer, v) },
                    wildcard = null,
                    value = current.pathValue
                )
            }
        } ?: run {
//            println("Searching for wildcard ending")
            soFar.asReversed().asSequence().mapNotNull {
                it.chainedWildcard?.let { spec ->
                    PathSpecMap.Match<V>(
                        pathSpec = spec,
                        rawPathArguments = wildcards.zip(spec.wildcards) { v, s -> format.decodeFromString(s.serializer, v) },
                        wildcard = pathParts.drop(spec.segments.size),
                        value = it.chainedWildcardValue
                    )
                }
            }.firstOrNull()
        }
    }
}