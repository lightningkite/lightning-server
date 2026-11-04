package com.lightningkite.lightningserver.typed.denormalize

import com.lightningkite.services.data.Denormalized
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.optics.ComposedOptic
import com.lightningkite.services.database.optics.Field
import com.lightningkite.services.database.optics.IdentityLens
import com.lightningkite.services.database.optics.Lens
import com.lightningkite.services.database.optics.NotNullOptic
import com.lightningkite.services.database.optics.Optic

/**
 * Calls [onModification] with each part of this modification that affects the target of [path].
 *
 * An assignment to a value containing the target is reported as an assignment to the target. The parts of a
 * [Modification.Chain] are reported separately, in order, and parts that don't affect the target are not reported.
 *
 * @throws IllegalArgumentException if [path] passes through a field shared by the variants of a sealed type.
 */
public fun <K, V> Modification<K>.vet(path: Lens<K, V>, onModification: (Modification<V>) -> Unit): Unit =
    @Suppress("UNCHECKED_CAST")
    vetAny(path, onModification as (Modification<*>) -> Unit)

/**
 * Calls [onModification] with each part of this modification that affects the target of [path].
 *
 * An assignment to a value containing the target is reported as an assignment to the target, or as an assignment of
 * `null` if a nullable value on the way to the target is `null`. The parts of a [Modification.Chain] are reported
 * separately, in order, and parts that don't affect the target are not reported.
 *
 * @throws IllegalArgumentException if [path] passes through collection elements or a sealed type.
 */
public fun <K, V> Modification<K>.vetNullable(path: Optic<K, V>, onModification: (Modification<V?>) -> Unit): Unit =
    @Suppress("UNCHECKED_CAST")
    vetAny(path, onModification as (Modification<*>) -> Unit)

private fun Modification<*>.vetAny(path: Optic<*, *>, onModification: (Modification<*>) -> Unit) {
    if (this is Modification.Chain) return modifications.forEach { it.vetAny(path, onModification) }
    when (path) {
        is IdentityLens<*> -> onModification(this)
        is ComposedOptic<*, *, *> -> vetAny(path.outer) { it.vetAny(path.inner, onModification) }
        is Field<*, *> -> when (this) {
            is Modification.Assign -> {
                @Suppress("UNCHECKED_CAST")
                onModification(Modification.Assign(value?.let { (path as Field<Any, *>).get(it) }))
            }
            is Modification.OnField<*, *> -> if (key == path) modification.forEachPart(onModification)
            else -> {}
        }

        is NotNullOptic<*, *> -> vetAny(path.wraps) {
            when (it) {
                is Modification.Assign -> onModification(it)
                is Modification.IfNotNull -> it.modification.forEachPart(onModification)
                else -> {}
            }
        }

        else -> throw IllegalArgumentException("vet does not support the step $path")
    }
}

private fun Modification<*>.forEachPart(action: (Modification<*>) -> Unit) {
    if (this is Modification.Chain) modifications.forEach { it.forEachPart(action) } else action(this)
}

/** Throws an [IllegalArgumentException] if [source] passes through a field marked [Denormalized]. */
internal fun requireSourceOfTruth(source: Optic<*, *>) {
    val copied = source.fields.firstOrNull { field -> field.annotations.any { it is Denormalized } } ?: return
    throw IllegalArgumentException(
        "A denormalization reads $source, but '${copied.name}' is itself @Denormalized. " +
            "Denormalize the reference '${copied.name}' comes from onto this model and read the value from its source " +
            "instead, e.g. `a.b.denormalizes(B.info) { b -> a.c bind b.c }` then `a.c.denormalizes(C.info) { c -> a.cName bind c.name }`."
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
// - vetNullable is a placeholder name; settle it before vet/vetNullable go public in service-abstractions.
// - When moving vet/vetNullable to service-abstractions, deprecate the list-based vet(fieldChain: List<Field>), which has the same fields-walking bugs and the null-parent crash. vet(field: Field) behaves the same as vet(Lens) and could be deprecated too.
// - Rejecting asType and SealedSharedFieldLens steps in vetAny is untested; it needs a sealed @Queryable model.
// - requireSourceOfTruth and requireMarkedDenormalized scan path.fields, so they can't see through a SealedSharedFieldLens.
