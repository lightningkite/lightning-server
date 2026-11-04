package com.lightningkite.lightningserver.typed.denormalize

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.optics.Optic

/**
 * A denormalization declared with `denormalizes` on a model created by [modelInfoWithDeno].
 *
 * [Input] is what the calculations receive: [Referenced] for a [RequiredRef], or `Referenced?` for an [OptionalRef].
 */
public sealed interface Denormalization<Model : Any, Referenced : HasId<RID>, RID : Comparable<RID>, Input : Referenced?> {
    public val info: ModelInfo<*, Referenced, RID>
    public val calculations: List<Calculation<Model, Referenced, Input>>

    /** Whether a change to the referenced model must update the models that reference it. */
    public val watchesReferencedModel: Boolean get() = calculations.any { it.dependencies.isNotEmpty() }

    /**
     * A [calculation] that reruns when any of its [dependencies] change.
     *
     * With no dependencies, it runs only when the reference is set.
     */
    public class Calculation<Model, Ref, Input : Ref?>(
        public val dependencies: Set<Optic<Ref, *>>,
        public val calculation: suspend context(ServerRuntime) (Input) -> Modification<Model>,
    ) {
        public fun dependenciesChanged(old: Ref, new: Input): Boolean =
            dependencies.any { it.get(old) != new?.let(it::get) }
    }

    context(_: ServerRuntime)
    public suspend fun updateDenormalizations(old: Referenced, new: Input): Modification<Model> =
        Modification.Chain(calculations.filter { it.dependenciesChanged(old, new) }.map { it.calculation(new) })

    context(_: ServerRuntime)
    public suspend fun setDenormalizations(ref: Input): Modification<Model> =
        Modification.Chain(calculations.map { it.calculation(ref) })

    /**
     * A denormalization through a non-null reference.
     *
     * Where [reference] doesn't reach a value, for example under a `null` parent, the denormalization does nothing.
     */
    public class RequiredRef<Model : Any, Referenced : HasId<RID>, RID : Comparable<RID>>(
        public val reference: Optic<Model, RID>,
        override val info: ModelInfo<*, Referenced, RID>,
        override val calculations: List<Calculation<Model, Referenced, Referenced>>
    ) : Denormalization<Model, Referenced, RID, Referenced>

    /** A denormalization through a reference that may be `null`. */
    public class OptionalRef<Model : Any, Referenced : HasId<RID>, RID : Comparable<RID>>(
        public val reference: Optic<Model, RID?>,
        override val info: ModelInfo<*, Referenced, RID>,
        override val calculations: List<Calculation<Model, Referenced, Referenced?>>
    ) : Denormalization<Model, Referenced, RID, Referenced?>
}
