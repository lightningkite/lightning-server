package com.lightningkite.lightningserver.typed.denormalize

import com.lightningkite.services.data.Denormalized
import com.lightningkite.services.database.optics.Optic

/** Throws an [IllegalArgumentException] if [source] passes through a field marked [Denormalized]. */
internal fun requireSourceOfTruth(source: Optic<*, *>) {
    val copied = source.fields.firstOrNull { field -> field.annotations.any { it is Denormalized } } ?: return
    throw IllegalArgumentException(
        "A denormalization reads $source, but '${copied.name}' is itself @Denormalized. " +
            "Denormalize the reference '${copied.name}' comes from onto this model and read the value from its source " +
            "instead, e.g. `a.b.denormalizes(B.info) { b -> a.c watch b.c }` then `a.c.denormalizes(C.info) { c -> a.cName watch c.name }`."
    )
}

/** Throws an [IllegalArgumentException] unless the last field of [target] is marked [Denormalized]. */
internal fun requireMarkedDenormalized(target: Optic<*, *>) {
    require(target.fields.lastOrNull()?.annotations.orEmpty().any { it is Denormalized }) {
        "$target is written by a denormalization, so it must be marked @Denormalized."
    }
}

// TODO: Api Recommendations
// Claude:
// - requireSourceOfTruth and requireMarkedDenormalized scan path.fields, so they can't see through a SealedSharedFieldLens.
