@file:Suppress("UNCHECKED_CAST")

package com.lightningkite.lightningserver.pathing

public operator fun PathSpec0.plus(other: PathSpec0): PathSpec0 = when {
    this == PathSpec.root -> other
    other == PathSpec.root -> this
    else -> PathSpec0(this.segments + other.segments, other.after)
}

public operator fun <PATH : PathSpec> PathSpec0.plus(other: PATH): PATH =
    if (this == PathSpec.root) other
    else when (other) {
        is PathSpec0 -> PathSpec0(this.segments + other.segments, other.after)
        is PathSpec1<*> -> PathSpec1(this.segments + other.segments, other.after, other.first)
        is PathSpec2<*, *> -> PathSpec2(this.segments + other.segments, other.after, other.first, other.second)
        is PathSpec3<*, *, *> -> PathSpec3(
            this.segments + other.segments,
            other.after,
            other.first,
            other.second,
            other.third
        )

        is PathSpecMany -> PathSpecMany(this.segments + other.segments, other.after, this.wildcards + other.wildcards)
    } as PATH

public operator fun <PATH : PathSpec> PATH.plus(other: PathSpec0): PATH =
    if (other == PathSpec.root) this
    else when (this) {
        is PathSpec0 -> PathSpec0(this.segments + other.segments, other.after)
        is PathSpec1<*> -> PathSpec1(this.segments + other.segments, other.after, this.first)
        is PathSpec2<*, *> -> PathSpec2(this.segments + other.segments, other.after, this.first, this.second)
        is PathSpec3<*, *, *> -> PathSpec3(
            this.segments + other.segments,
            other.after,
            this.first,
            this.second,
            this.third
        )

        is PathSpecMany -> PathSpecMany(this.segments + other.segments, other.after, this.wildcards + other.wildcards)
    } as PATH

public operator fun <A, B> PathSpec1<A>.plus(other: PathSpec1<B>): PathSpec2<A, B> =
    PathSpec2(this.segments + other.segments, other.after, this.first, other.first)

public operator fun <A, B, C> PathSpec1<A>.plus(other: PathSpec2<B, C>): PathSpec3<A, B, C> =
    PathSpec3(this.segments + other.segments, other.after, this.first, other.first, other.second)

public operator fun <A, B, C> PathSpec2<A, B>.plus(other: PathSpec1<C>): PathSpec3<A, B, C> =
    PathSpec3(this.segments + other.segments, other.after, this.first, this.second, other.first)