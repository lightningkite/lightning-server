package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.data.StringArrayFormat
import kotlinx.serialization.KSerializer

public interface HasContextualPath<PATH: PathSpec> {
    context(server: ServerRuntime) public val pathInContext: ConcretePath<PATH>
}

public interface ConcretePath<PATH: PathSpec> {
    public val pathSpec: PATH
    public val rawPathArguments: List<Any?>
    public val wildcard: List<String>? get() = null
}

public fun ConcretePath<*>.pathSegments(stringArrayFormat: StringArrayFormat): List<String> {
    var index = 0
    return pathSpec.segments.map {
        when (it) {
            is PathSpec.Segment.Constant -> it.value
            is PathSpec.Segment.Wildcard<*> -> stringArrayFormat.encodeToString(it.serializer as KSerializer<Any?>, rawPathArguments[index++])
        }
    }
}
public fun ConcretePath<*>.path(stringArrayFormat: StringArrayFormat): String = buildString {
    var index = 0
    pathSpec.segments.forEach {
        when (it) {
            is PathSpec.Segment.Constant -> append(it.value)
            is PathSpec.Segment.Wildcard<*> -> append(stringArrayFormat.encodeToString(it.serializer as KSerializer<Any?>, rawPathArguments[index++]))
        }
        append('/')
    }
    if (this.isNotBlank() && pathSpec.after != PathSpec.Afterwards.TrailingSlash)
        deleteAt(lastIndex)
}

@Suppress("UNCHECKED_CAST")
@get:JvmName("first1")
public val <A> ConcretePath<PathSpec1<A>>.first: A get() = rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("first2")
public val <A, B> ConcretePath<PathSpec2<A, B>>.first: A get() = rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("second2")
public val <A, B> ConcretePath<PathSpec2<A, B>>.second: B get() = rawPathArguments[1] as B

@Suppress("UNCHECKED_CAST")
@get:JvmName("first3")
public val <A, B, C> ConcretePath<PathSpec3<A, B, C>>.first: A get() = rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("second3")
public val <A, B, C> ConcretePath<PathSpec3<A, B, C>>.second: B get() = rawPathArguments[1] as B

@Suppress("UNCHECKED_CAST")
@get:JvmName("third3")
public val <A, B, C> ConcretePath<PathSpec3<A, B, C>>.third: C get() = rawPathArguments[1] as C



@Suppress("UNCHECKED_CAST")
@get:JvmName("first1")
context(serverRuntime: ServerRuntime)
public val <A> HasContextualPath<PathSpec1<A>>.first: A get() = pathInContext.rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("first2")
context(serverRuntime: ServerRuntime)
public val <A, B> HasContextualPath<PathSpec2<A, B>>.first: A get() = pathInContext.rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("second2")
context(serverRuntime: ServerRuntime)
public val <A, B> HasContextualPath<PathSpec2<A, B>>.second: B get() = pathInContext.rawPathArguments[1] as B

@Suppress("UNCHECKED_CAST")
@get:JvmName("first3")
context(serverRuntime: ServerRuntime)
public val <A, B, C> HasContextualPath<PathSpec3<A, B, C>>.first: A get() = pathInContext.rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("second3")
context(serverRuntime: ServerRuntime)
public val <A, B, C> HasContextualPath<PathSpec3<A, B, C>>.second: B get() = pathInContext.rawPathArguments[1] as B

@Suppress("UNCHECKED_CAST")
@get:JvmName("third3")
context(serverRuntime: ServerRuntime)
public val <A, B, C> HasContextualPath<PathSpec3<A, B, C>>.third: C get() = pathInContext.rawPathArguments[1] as C
