package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.Locationed
import com.lightningkite.services.data.StringArrayFormat

public interface PathSpecMap<out V> : Map<PathSpec, V> {
    public fun match(format: StringArrayFormat, pathParts: List<String>, endingSlash: Boolean): Match<V>?
    public fun match(format: StringArrayFormat, string: String): Match<V>? = match(format, string.split('/').filter { it.isNotEmpty() }, string.endsWith('/'))
    public fun asSequence(): Sequence<Locationed<PathSpec, V>>

    public class Match<out V>(
        override val pathSpec: PathSpec,
        override val rawPathArguments: List<Any?>,
        override val wildcard: List<String>?,
        public val value: V?
    ): ConcretePath<PathSpec>

    override fun containsKey(key: PathSpec): Boolean = get(key) != null
    override fun containsValue(value: @UnsafeVariance V): Boolean = asSequence().any { it.value == value }

    override val entries: Set<Map.Entry<PathSpec, V>> get() = asSequence().toSet()
    override val keys: Set<PathSpec> get() = asSequence().map { it.key }.toSet()
    override val values: Collection<V> get() = asSequence().map { it.value }.toList()
    override val size: Int get() = asSequence().count()
    override fun isEmpty(): Boolean = asSequence().none()
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

    public fun putAll(prefix: PathSpec, map: PathSpecMap<V>) {
        map.asSequence().forEach {
            this.put(prefix + it.key, it.value)
        }
    }

    public operator fun set(path: PathSpec, value: V): Unit = put(path, value)

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

    override fun get(key: PathSpec): V? {
        var current = root
        for(segment in key.segments) {
            current = when(segment) {
                is PathSpec.Segment.Constant -> current.thenConstant[segment.value] ?: return null
                is PathSpec.Segment.Wildcard<*> -> current.thenWildcard ?: return null
            }
        }
        return when(key.after) {
            PathSpec.Afterwards.None -> current.pathValue
            PathSpec.Afterwards.TrailingSlash -> current.trailingSlashValue
            PathSpec.Afterwards.TrailingSegments -> current.chainedWildcardValue
        }
    }

    override fun asSequence(): Sequence<Locationed<PathSpec, V>> = sequence {
        suspend fun SequenceScope<Locationed<PathSpec, V>>.entry(path: PathSpec, value: V) = yield(Locationed(path, value))

        suspend fun SequenceScope<Locationed<PathSpec, V>>.traverse(node: Node) {
            node.pathValue?.let { entry(node.path!!, it) }
            node.trailingSlashValue?.let { entry(node.trailingSlash!!, it) }
            node.chainedWildcardValue?.let { entry(node.chainedWildcard!!, it) }

            node.thenConstant.values.forEach { traverse(it) }
            node.thenWildcard?.let { traverse(it) }
        }

        traverse(root)
    }

    public override fun match(format: StringArrayFormat, pathParts: List<String>, endingSlash: Boolean): PathSpecMap.Match<V>? {
        if (pathParts.isEmpty())
            return (root.path ?: root.trailingSlash ?: root.chainedWildcard)?.let {
                PathSpecMap.Match<V>(
                    pathSpec = PathSpec.root,
                    rawPathArguments = emptyList(),
                    wildcard = if (it.after == PathSpec.Afterwards.TrailingSegments) emptyList() else null,
                    value = root[it.after]
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
                    PathSpecMap.Match<V>(
                        pathSpec = spec,
                        rawPathArguments = wildcards.zip(spec.wildcards) { v, s -> format.decodeFromString(s.serializer, v) },
                        wildcard = pathParts.drop(spec.segments.size),
                        value = it.chainedWildcardValue,
                    )
                }
            }.firstOrNull()
        } else {
            if (endingSlash) {
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
}