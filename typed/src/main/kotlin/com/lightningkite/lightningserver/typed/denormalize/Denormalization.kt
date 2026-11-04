package com.lightningkite.lightningserver.typed.denormalize

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.optics.Lens
import com.lightningkite.services.database.optics.Optic

/**
 * Internal representation of a denormalization rule.
 * Contains the logic to copy data from a referenced model into the referencing model.
 */
public sealed interface Denormalization<Model : Any, Referenced : HasId<RID>, RID : Comparable<RID>> {
    public val info: ModelInfo<*, Referenced, RID>
    public val listenForChanges: Boolean

    /**
     * Internal data class pairing a set of field dependencies with their calculation function.
     * Used to track which fields trigger denormalization updates.
     *
     * An empty set of dependencies means the calculation runs on create/replace/modify but does NOT
     * register change listeners on the referenced model. Non-empty dependencies trigger both immediate
     * denormalization and change listener registration.
     */
    public data class Calculation<Ref, T>(
        val dependencies: Set<Optic<Ref, *>>,
        val calculation: T,
    )

    /**
     * Denormalization rule for non-nullable (required) references.
     */
    public data class RequiredRef<Model : Any, Referenced : HasId<RID>, RID : Comparable<RID>>(
        val reference: Lens<Model, RID>,
        override val info: ModelInfo<*, Referenced, RID>,
        val calculations: List<Calculation<Referenced, suspend context(ServerRuntime) (Referenced) -> Modification<Model>>>,
    ) : Denormalization<Model, Referenced, RID> {
        context(_: ServerRuntime)
        public suspend fun denormalize(old: Referenced, new: Referenced): Modification<Model> =
            Modification.Chain(
                calculations
                    .filter { calc ->
                        calc.dependencies.any { it.get(old) != it.get(new) }
                    }
                    .map { it.calculation(new) }
            )

        context(_: ServerRuntime)
        public suspend fun denormalize(ref: Referenced): Modification<Model> =
            Modification.Chain(
                calculations.map { it.calculation(ref) }
            )

        override val listenForChanges: Boolean get() = calculations.any { it.dependencies.isNotEmpty() }
    }

    /**
     * Denormalization rule for nullable (optional) references.
     */
    public data class OptionalRef<Model : Any, Referenced : HasId<RID>, RID : Comparable<RID>>(
        val reference: Optic<Model, RID?>,
        override val info: ModelInfo<*, Referenced, RID>,
        val calculations: List<Calculation<Referenced, suspend context(ServerRuntime) (Referenced?) -> Modification<Model>>>,
    ) : Denormalization<Model, Referenced, RID> {
        context(_: ServerRuntime)
        public suspend fun denormalize(old: Referenced, new: Referenced?): Modification<Model> =
            Modification.Chain(
                calculations
                    .filter { calc ->
                        calc.dependencies.any { it.get(old) != new?.let(it::get) }
                    }
                    .map { it.calculation(new) }
            )

        context(_: ServerRuntime)
        public suspend fun denormalize(ref: Referenced?): Modification<Model> =
            Modification.Chain(
                calculations.map { it.calculation(ref) }
            )

        override val listenForChanges: Boolean get() = calculations.any { it.dependencies.isNotEmpty() }
    }
}

