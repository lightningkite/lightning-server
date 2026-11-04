package com.lightningkite.lightningserver.typed.denormalize

import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.optics.Lens
import com.lightningkite.services.database.optics.Optic

context(builder: DenormalizationBuilder<Model>)
public inline fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>> Lens<Model, RID>.denormalizes(
    info: ModelInfo<*, Ref, RID>,
    build: DenormalizationBuilder.RequiredRefBuilder<Model, Ref, RID>.(Lens<Ref, Ref>) -> Unit,
): Unit =
    builder.addRequiredRef(this, info, build)

@JvmName("denormalizesNullable")
context(builder: DenormalizationBuilder<Model>)
public inline fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>> Optic<Model, RID?>.denormalizes(
    info: ModelInfo<*, Ref, RID>,
    build: DenormalizationBuilder.OptionalRefBuilder<Model, Ref, RID>.(Optic<Ref, Ref>) -> Unit,
): Unit =
    builder.addOptionalRef(this, info, build)

/** Keeps this field equal to [ref] on the referenced model, updating it whenever [ref] changes. */
context(builder: DenormalizationBuilder.RequiredRefBuilder<Model, Ref, RID>)
public infix fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>, T> Optic<Model, in T>.watch(ref: Lens<Ref, out T>) {
    requireMarkedDenormalized(this)
    builder.registerDenormalization(setOf(ref)) { reference ->
        this.mapModification(
            Modification.Assign(ref.get(reference))
        )
    }
}

/** Copies [ref] from the referenced model into this field whenever the reference is set, without following later changes to [ref]. */
context(builder: DenormalizationBuilder.RequiredRefBuilder<Model, Ref, RID>)
public infix fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>, T> Optic<Model, in T>.snapshot(ref: Lens<Ref, out T>) {
    requireMarkedDenormalized(this)
    requireSourceOfTruth(ref)
    builder.registerDenormalization(emptySet()) { reference ->
        this.mapModification(
            Modification.Assign(ref.get(reference))
        )
    }
}

/** Keeps this field equal to [ref] on the referenced model, updating it whenever [ref] changes. It is `null` while the reference is unset. */
context(builder: DenormalizationBuilder.OptionalRefBuilder<Model, Ref, RID>)
public infix fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>, T> Optic<Model, in T?>.watch(ref: Optic<Ref, out T?>) {
    requireMarkedDenormalized(this)
    builder.registerDenormalization(setOf(ref)) { reference ->
        this.mapModification(
            Modification.Assign(reference?.let(ref::get))
        )
    }
}

/** Copies [ref] from the referenced model into this field whenever the reference is set, without following later changes to [ref]. It is `null` while the reference is unset. */
context(builder: DenormalizationBuilder.OptionalRefBuilder<Model, Ref, RID>)
public infix fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>, T> Optic<Model, in T?>.snapshot(ref: Optic<Ref, out T?>) {
    requireMarkedDenormalized(this)
    requireSourceOfTruth(ref)
    builder.registerDenormalization(emptySet()) { reference ->
        this.mapModification(
            Modification.Assign(reference?.let(ref::get))
        )
    }
}
