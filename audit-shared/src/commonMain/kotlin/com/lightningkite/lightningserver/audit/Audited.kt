package com.lightningkite.lightningserver.audit

import kotlinx.serialization.SerialInfo

/**
 * Marks disclosure auditing, meaning one thing on a class and a narrower thing on a property.
 *
 * **On a class:** every instance that reaches a client — as a response body, inside a list, inside a
 * `Partial`, nested in a wrapper, or pushed over a WebSocket — produces its own [DisclosureRecord]
 * naming the record's id. This is what makes a model audited at all.
 *
 * **On a property:** that field is *itemised*. A bit is reserved for it, and each disclosure records
 * whether the field carried a value in the payload the client received. Answers "which requests
 * disclosed an SSN?" as opposed to merely "who saw this record?".
 *
 * ## Itemising is opt-in, and disclosure is not
 *
 * A record is logged whenever an audited model reaches a client, annotated fields or none. Marking
 * properties adds resolution; it never decides whether a disclosure is recorded. So a model with no
 * annotated properties still answers "who saw this record, under which request" completely — it just
 * cannot answer "was the SSN among what they saw".
 *
 * That is why itemising is opt-in. Most fields of a real model are inconsequential to an audit, and
 * reserving bits for `sortOrder` and `createdAt` costs permanent capacity (indices are never reused —
 * see `AuditFieldRegistration`) while diluting the log with noise that makes the interesting query
 * harder to write and harder to trust.
 *
 * An annotated property is picked up anywhere in the graph, including inside nested types that are
 * not themselves audited — `@Audited val street: String` inside an `Address` becomes `address.street`
 * on the record that holds it.
 *
 * ## The model must be keyed by `Uuid`
 *
 * One disclosure row is written per record disclosed, which makes the identifier the most-written
 * column in the system. A `Uuid` is sixteen bytes in every backend; a string key is larger and
 * indexed poorly. Marking a class whose `_id` is absent or is not a `Uuid` fails at deploy.
 *
 * ## Why `@SerialInfo`
 *
 * A plain Kotlin annotation is invisible to a `SerialDescriptor`. Disclosure detection walks
 * descriptors rather than reflecting, so that it sees precisely what the serializer will emit and
 * behaves identically on every target. `@SerialInfo` is what puts the marker where that walk can
 * find it, which also means this only has an effect on `@Serializable` classes.
 */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Audited
