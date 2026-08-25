package com.lightningkite.lightningserver.audit

import kotlinx.serialization.SerialInfo

/**
 * Records disclosure of this model whenever it leaves the server through a typed endpoint.
 *
 * Every instance that reaches a client — as a response body, inside a list, inside a `Partial`,
 * nested in a wrapper, or pushed over a WebSocket — produces its own [DisclosureRecord] naming the
 * record's id and exactly which of its fields carried a value.
 *
 * ## The model must be keyed by `Uuid`
 *
 * One disclosure row per record makes this the highest-volume table in the system, so the identifier
 * column has to be tight: a `Uuid` is sixteen bytes in every backend, where a string key is larger
 * and, in most engines, indexed poorly. Marking a model whose `_id` is not a `Uuid` fails at deploy.
 *
 * ## Why `@SerialInfo`
 *
 * A plain Kotlin annotation is invisible to a `SerialDescriptor`. Disclosure detection walks
 * descriptors rather than reflecting, so that it sees precisely what the serializer will emit and
 * behaves identically on every target. `@SerialInfo` is what puts the marker where that walk can
 * find it, which also means this only has an effect on `@Serializable` classes.
 *
 * ## Nesting
 *
 * An audited model nested inside another audited model produces its own record rather than
 * consuming bits of its parent. That is also the remedy when a model is too wide for the
 * [FieldBits.CAPACITY] bits available to one model: mark the nested type audited and it splits off.
 */
@SerialInfo
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Audited
