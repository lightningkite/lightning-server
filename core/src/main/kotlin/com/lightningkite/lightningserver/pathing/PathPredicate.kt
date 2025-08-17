package com.lightningkite.lightningserver.pathing

/**
 * A predicate that can match a [ConcretePath] and/or a [PathSpec].
 *
 * [PathSpec] and [ConcretePath] both inherit this interface.
 *
 * A [PathSpec] will be satisfied by another [PathSpec] that meets the following criteria:
 * 1. All segments in the predicate must match the corresponding segments in the other path
 *    (the other path must start with the same segment sequence as the predicate)
 * 2. If the predicate ends with [PathSpec.Afterwards.TrailingSegments], the other path
 *    may have additional segments beyond the predicate's segments and may end with any
 *    trailing behavior. Otherwise, the other path must have exactly the same number of
 *    segments and the same [PathSpec.Afterwards] value as the predicate.
 *
 * Additionally, a [PathSpec] will be satisfied by a [ConcretePath] according to its `pathSpec` parameter and
 * the rules above.
 *
 * A [ConcretePath] will be satisfied only by an equivalent [ConcretePath], and will
 * never be satisfied by a [PathSpec].
 *
 * Examples:
 * - `/foo/bar` satisfies `/foo/bar` (same path)
 * - `/foo` does not satisfy `/foo/bar` (missing segment `/bar`)
 * - `/foo/bar` does not satisfy `/foo/bar/` (missing trailing slash)
 * - `/foo/bar/baz` does not satisfy `/foo/bar` (extra segment)
 * - `/foo/bar/baz` ***does*** satisfy `/foo/bar/{...}` (trailing wildcard allows extra segments)
 * */
public sealed interface PathPredicate {
    public fun satisfiedBy(path: ConcretePath<*>): Boolean
    public fun satisfiedBy(path: PathSpec): Boolean
}

public infix fun PathSpec.satisfies(predicate: PathPredicate): Boolean = predicate.satisfiedBy(this)
public infix fun ConcretePath<*>.satisfies(predicate: PathPredicate): Boolean = predicate.satisfiedBy(this)