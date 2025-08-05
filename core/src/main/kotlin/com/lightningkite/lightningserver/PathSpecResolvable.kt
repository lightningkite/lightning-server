package com.lightningkite.lightningserver

import com.lightningkite.serviceabstractions.data.StringArrayFormat
import kotlinx.serialization.KSerializer


public interface PathSpecResolvableInServerRunning<PATH: PathSpec> {
    context(serverRunning: ServerRunning) public val resolvable: PathSpecResolvable<PATH>
}
public interface PathSpecResolvable<PATH: PathSpec> {
    public val pathSpec: PATH
    public val rawPathArguments: List<Any?>
    public val wildcard: List<String>? get() = null
}
public fun PathSpecResolvable<*>.pathSegments(stringArrayFormat: StringArrayFormat): List<String> {
    var index = 0
    return pathSpec.segments.map {
        when (it) {
            is PathSpec.Segment.Constant -> it.value
            is PathSpec.Segment.Wildcard<*> -> stringArrayFormat.encodeToString(it.serializer as KSerializer<Any?>, rawPathArguments[index++])
        }
    }
}
public fun PathSpecResolvable<*>.path(stringArrayFormat: StringArrayFormat): String = buildString {
    var index = 0
    pathSpec.segments.forEach {
        when (it) {
            is PathSpec.Segment.Constant -> append(it.value)
            is PathSpec.Segment.Wildcard<*> -> append(stringArrayFormat.encodeToString(it.serializer as KSerializer<Any?>, rawPathArguments[index++]))
        }
        append('/')
    }
    if(pathSpec.after != PathSpec.Afterwards.TrailingSlash)
        deleteAt(lastIndex)
}



@Suppress("UNCHECKED_CAST")
@get:JvmName("first1")
public val <A> PathSpecResolvable<PathSpec1<A>>.first: A get() = rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("first2")
public val <A, B> PathSpecResolvable<PathSpec2<A, B>>.first: A get() = rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("second2")
public val <A, B> PathSpecResolvable<PathSpec2<A, B>>.second: B get() = rawPathArguments[1] as B

@Suppress("UNCHECKED_CAST")
@get:JvmName("first3")
public val <A, B, C> PathSpecResolvable<PathSpec3<A, B, C>>.first: A get() = rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("second3")
public val <A, B, C> PathSpecResolvable<PathSpec3<A, B, C>>.second: B get() = rawPathArguments[1] as B

@Suppress("UNCHECKED_CAST")
@get:JvmName("third3")
public val <A, B, C> PathSpecResolvable<PathSpec3<A, B, C>>.third: C get() = rawPathArguments[1] as C



@Suppress("UNCHECKED_CAST")
@get:JvmName("first1")
context(serverRunning: ServerRunning)
public val <A> PathSpecResolvableInServerRunning<PathSpec1<A>>.first: A get() = resolvable.rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("first2")
context(serverRunning: ServerRunning)
public val <A, B> PathSpecResolvableInServerRunning<PathSpec2<A, B>>.first: A get() = resolvable.rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("second2")
context(serverRunning: ServerRunning)
public val <A, B> PathSpecResolvableInServerRunning<PathSpec2<A, B>>.second: B get() = resolvable.rawPathArguments[1] as B

@Suppress("UNCHECKED_CAST")
@get:JvmName("first3")
context(serverRunning: ServerRunning)
public val <A, B, C> PathSpecResolvableInServerRunning<PathSpec3<A, B, C>>.first: A get() = resolvable.rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("second3")
context(serverRunning: ServerRunning)
public val <A, B, C> PathSpecResolvableInServerRunning<PathSpec3<A, B, C>>.second: B get() = resolvable.rawPathArguments[1] as B

@Suppress("UNCHECKED_CAST")
@get:JvmName("third3")
context(serverRunning: ServerRunning)
public val <A, B, C> PathSpecResolvableInServerRunning<PathSpec3<A, B, C>>.third: C get() = resolvable.rawPathArguments[1] as C
