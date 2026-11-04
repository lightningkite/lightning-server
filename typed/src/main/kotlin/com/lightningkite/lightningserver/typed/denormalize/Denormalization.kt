package com.lightningkite.lightningserver.typed.denormalize

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.optics.Lens
import com.lightningkite.services.database.optics.Optic

/** A denormalization declared with `denormalizes` on a model created by [modelInfoWithDeno]. */
public sealed interface Denormalization<Model : Any, Referenced : HasId<RID>, RID : Comparable<RID>> {
    public val info: ModelInfo<*, Referenced, RID>
    public val calculations: List<Calculation<Referenced, *>>

    /** Whether a change to the referenced model must update the models that reference it. */
    public val watchesReferencedModel: Boolean get() = calculations.any { it.dependencies.isNotEmpty() }

    /**
     * A [calculation] that reruns when any of its [dependencies] change.
     *
     * With no dependencies, it runs only when the reference is set.
     */
    public class Calculation<Ref, T>(
        public val dependencies: Set<Optic<Ref, *>>,
        public val calculation: T,
    ) {
        /** Returns `true` if any of the [dependencies] differ between [old] and [new]. */
        public fun changedBetween(old: Ref, new: Ref?): Boolean =
            dependencies.any { it.get(old) != new?.let(it::get) }
    }

    /** A denormalization through a reference that is always set. */
    public class RequiredRef<Model : Any, Referenced : HasId<RID>, RID : Comparable<RID>>(
        public val reference: Lens<Model, RID>,
        override val info: ModelInfo<*, Referenced, RID>,
        override val calculations: List<Calculation<Referenced, suspend context(ServerRuntime) (Referenced) -> Modification<Model>>>,
    ) : Denormalization<Model, Referenced, RID> {
        /** Returns the modification that updates a referencing model after the referenced model changed from [old] to [new]. */
        context(_: ServerRuntime)
        public suspend fun denormalize(old: Referenced, new: Referenced): Modification<Model> =
            Modification.Chain(calculations.filter { it.changedBetween(old, new) }.map { it.calculation(new) })

        /** Returns the modification that fills in every denormalized field from [ref]. */
        context(_: ServerRuntime)
        public suspend fun denormalize(ref: Referenced): Modification<Model> =
            Modification.Chain(calculations.map { it.calculation(ref) })
    }

    /** A denormalization through a reference that may be `null`. */
    public class OptionalRef<Model : Any, Referenced : HasId<RID>, RID : Comparable<RID>>(
        public val reference: Optic<Model, RID?>,
        override val info: ModelInfo<*, Referenced, RID>,
        override val calculations: List<Calculation<Referenced, suspend context(ServerRuntime) (Referenced?) -> Modification<Model>>>,
    ) : Denormalization<Model, Referenced, RID> {
        /**
         * Returns the modification that updates a referencing model after the referenced model changed from [old] to [new].
         *
         * [new] is `null` when the referenced model was deleted.
         */
        context(_: ServerRuntime)
        public suspend fun denormalize(old: Referenced, new: Referenced?): Modification<Model> =
            Modification.Chain(calculations.filter { it.changedBetween(old, new) }.map { it.calculation(new) })

        /** Returns the modification that fills in every denormalized field from [ref], which is `null` when the reference is unset. */
        context(_: ServerRuntime)
        public suspend fun denormalize(ref: Referenced?): Modification<Model> =
            Modification.Chain(calculations.map { it.calculation(ref) })
    }
}
