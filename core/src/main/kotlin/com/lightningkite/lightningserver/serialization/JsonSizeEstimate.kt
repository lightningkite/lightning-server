package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.SerializationStrategy

/**
 * Estimates how many bytes [value] would occupy as JSON, without building the JSON.
 *
 * This walks the value itself rather than its descriptor, which matters in three ways: string lengths
 * are counted for real rather than assumed, polymorphic and contextual values are resolved by their own
 * serializers, and recursion is bounded by the object graph instead of by a self-referential descriptor.
 *
 * The result is an estimate. Escaping, floating-point formatting and map punctuation are approximated,
 * so treat it as accurate to within a few percent — good for deciding whether a payload is too large,
 * not for sizing a buffer.
 *
 * Pass a [limit] when only a threshold comparison matters: measurement stops as soon as it is reached,
 * so an enormous value costs no more to measure than a small one. The returned size is then only
 * guaranteed to be at least [limit].
 */
public fun <T> Serialization.approximateJsonSize(
    serializer: SerializationStrategy<T>,
    value: T,
    limit: Int = Int.MAX_VALUE,
): Int {
    val encoder = JsonSizeCountingEncoder(serializersModule, limit)
    return try {
        serializer.serialize(encoder, value)
        encoder.size
    } catch (_: BudgetExhausted) {
        encoder.size
    }
}
