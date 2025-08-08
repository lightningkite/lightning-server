package com.lightningkite.lightningserver.pathing


public operator fun PathSpec.plus(other: PathSpec): PathSpec = PathSpecMany(this.segments + other.segments, other.after, this.wildcards + other.wildcards)

public operator fun PathSpec0.plus(other: PathSpec0): PathSpec0 = PathSpec0(this.segments + other.segments, other.after)
public operator fun <A> PathSpec0.plus(other: PathSpec1<A>): PathSpec1<A> = PathSpec1(this.segments + other.segments, other.after, other.first)
public operator fun <A, B> PathSpec0.plus(other: PathSpec2<A, B>): PathSpec2<A, B> = PathSpec2(this.segments + other.segments, other.after, other.first, other.second)
public operator fun <A, B, C> PathSpec0.plus(other: PathSpec3<A, B, C>): PathSpec3<A, B, C> = PathSpec3(this.segments + other.segments, other.after, other.first, other.second, other.third)

public operator fun <A> PathSpec1<A>.plus(other: PathSpec0): PathSpec1<A> = PathSpec1(this.segments + other.segments, other.after, this.first)
public operator fun <A, B> PathSpec1<A>.plus(other: PathSpec1<B>): PathSpec2<A, B> = PathSpec2(this.segments + other.segments, other.after, this.first, other.first)
public operator fun <A, B, C> PathSpec1<A>.plus(other: PathSpec2<B, C>): PathSpec3<A, B, C> = PathSpec3(this.segments + other.segments, other.after, this.first, other.first, other.second)

public operator fun <A, B> PathSpec2<A, B>.plus(other: PathSpec0): PathSpec2<A, B> = PathSpec2(this.segments + other.segments, other.after, this.first, this.second)
public operator fun <A, B, C> PathSpec2<A, B>.plus(other: PathSpec1<C>): PathSpec3<A, B, C> = PathSpec3(this.segments + other.segments, other.after, this.first, this.second, other.first)

public operator fun <A, B, C> PathSpec3<A, B, C>.plus(other: PathSpec0): PathSpec3<A, B, C> = PathSpec3(this.segments + other.segments, other.after, this.first, this.second, this.third)
