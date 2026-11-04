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
    builder.required(this, info, build)

@JvmName("denormalizesNullable")
context(builder: DenormalizationBuilder<Model>)
public inline fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>> Optic<Model, RID?>.denormalizes(
    info: ModelInfo<*, Ref, RID>,
    build: DenormalizationBuilder.OptionalRefBuilder<Model, Ref, RID>.(Optic<Ref, Ref>) -> Unit,
): Unit =
    builder.optional(this, info, build)

context(builder: DenormalizationBuilder.RequiredRefBuilder<Model, Ref, RID>)
public infix fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>, T> Optic<Model, in T>.bind(ref: Lens<Ref, out T>) {
    requireMarkedDenormalized(this)
    builder.registerDenormalization(setOf(ref)) { reference ->
        this.mapModification(
            Modification.Assign(ref.get(reference))
        )
    }
}

context(builder: DenormalizationBuilder.RequiredRefBuilder<Model, Ref, RID>)
public infix fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>, T> Optic<Model, in T>.set(ref: Lens<Ref, out T>) {
    requireMarkedDenormalized(this)
    requireSourceOfTruth(ref)
    builder.registerDenormalization(emptySet()) { reference ->
        this.mapModification(
            Modification.Assign(ref.get(reference))
        )
    }
}

context(builder: DenormalizationBuilder.OptionalRefBuilder<Model, Ref, RID>)
public infix fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>, T> Optic<Model, in T?>.bind(ref: Optic<Ref, out T?>) {
    requireMarkedDenormalized(this)
    builder.registerDenormalization(setOf(ref)) { reference ->
        this.mapModification(
            Modification.Assign(reference?.let(ref::get))
        )
    }
}

context(builder: DenormalizationBuilder.OptionalRefBuilder<Model, Ref, RID>)
public infix fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>, T> Optic<Model, in T?>.set(ref: Optic<Ref, out T?>) {
    requireMarkedDenormalized(this)
    requireSourceOfTruth(ref)
    builder.registerDenormalization(emptySet()) { reference ->
        this.mapModification(
            Modification.Assign(reference?.let(ref::get))
        )
    }
}
