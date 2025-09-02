package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.services.data.StringArrayFormat

public interface PathSpecMap<out V> : Map<PathSpec, V> {
    public fun match(format: StringArrayFormat, pathParts: List<String>, endingSlash: Boolean): Match<V>?
        = match(format, pathParts, endingSlash) { it }
    public fun <T> match(format: StringArrayFormat, pathParts: List<String>, endingSlash: Boolean, getter: (V)->T?): Match<T>?
    public fun match(format: StringArrayFormat, string: String): Match<V>?
        = match(format, string.split('/').filter { it.isNotEmpty() }, string.endsWith('/'))
    public fun <T> match(format: StringArrayFormat, string: String, getter: (V)->T?): Match<T>?
        = match(format, string.split('/').filter { it.isNotEmpty() }, string.endsWith('/'), getter)
    public fun asSequence(): Sequence<Locationed<PathSpec, V>>

    public class Match<out V>(
        override val path: ConcretePath<PathSpec>,
        public val value: V
    ): HasConcretePath<PathSpec> {
        public constructor(
            pathSpec: PathSpec,
            rawPathArguments: List<Any?>,
            trailingSlash: Boolean,
            wildcard: List<String>?,
            value: V
        ) : this(
            ConcretePath(pathSpec, rawPathArguments, wildcard?.let { ConcretePath.TrailingSegments(it, trailingSlash) }),
            value
        )

        public val pathSpec: PathSpec get() = path.pathSpec

        override fun toString(): String = "Match(path = $path, value = $value)"
    }

    override fun containsKey(key: PathSpec): Boolean = get(key) != null
    override fun containsValue(value: @UnsafeVariance V): Boolean = asSequence().any { it.value == value }

    override val entries: Set<Locationed<PathSpec, V>> get() = asSequence().toSet()
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

        override fun toString(): String {
            return """
                MutablePathSpecMap.Node(
                    path = $path, 
                    pathValue = $pathValue, 
                    trailingSlash = $trailingSlash, 
                    trailingSlashValue = $trailingSlashValue, 
                    chainedWildcard = $chainedWildcard, 
                    chainedWildcardValue = $chainedWildcardValue, 
                )
                """.trimIndent().lines().joinToString(" ") { it.trim() }
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

    public fun putAll(prefix: PathSpec0, map: PathSpecMap<V>) {
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

    public override fun <T> match(format: StringArrayFormat, pathParts: List<String>, endingSlash: Boolean, getter: (V)->T?): PathSpecMap.Match<T>? {
        if (pathParts.isEmpty())
            return (root.path ?: root.trailingSlash ?: root.chainedWildcard)?.let {
                val value = root[it.after]?.let(getter) ?: return@let null
                PathSpecMap.Match<T>(
                    pathSpec = PathSpec.root,
                    rawPathArguments = emptyList(),
                    trailingSlash = endingSlash,
                    wildcard = if (it.after == PathSpec.Afterwards.TrailingSegments) emptyList() else null,
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
                        rawPathArguments = wildcards.zip(spec.wildcards) { v, s -> format.decodeFromString(s.serializer, v) },
                        trailingSlash = endingSlash,
                        wildcard = pathParts.drop(spec.segments.size),
                        value = value,
                    )
                }
            }.firstOrNull()
        } else {
            if (endingSlash) {
        //            println("Pulling trailingSlash")
                current.trailingSlash?.let { spec ->
                    val value = current.trailingSlashValue?.let(getter) ?: return@let null
                    PathSpecMap.Match<T>(
                        pathSpec = spec,
                        rawPathArguments = wildcards.zip(spec.wildcards) { v, s -> format.decodeFromString(s.serializer, v) },
                        trailingSlash = true,
                        wildcard = null,
                        value = value,
                    )
                }
            } else {
        //            println("Pulling path")
                current.path?.let { spec ->
                    val value = current.pathValue?.let(getter) ?: return@let null
                    PathSpecMap.Match<T>(
                        pathSpec = spec,
                        rawPathArguments = wildcards.zip(spec.wildcards) { v, s -> format.decodeFromString(s.serializer, v) },
                        trailingSlash = false,
                        wildcard = null,
                        value = value,
                    )
                }
            } ?: run {
//                println("Searching for wildcard ending")
                soFar.asReversed().asSequence().mapNotNull {
//                    println("Checking $it")
                    it.chainedWildcard?.let { spec ->
                        val value = it.chainedWildcardValue?.let(getter) ?: return@let null
//                        println("Found value $value")
                        PathSpecMap.Match<T>(
                            pathSpec = spec,
                            rawPathArguments = wildcards.zip(spec.wildcards) { v, s -> format.decodeFromString(s.serializer, v) },
                            trailingSlash = endingSlash,
                            wildcard = pathParts.drop(spec.segments.size),
                            value = value,
                        )
                    }
                }.firstOrNull()
            }
        }
    }
}