package com.lightningkite.lightningserver.typed.denormalize

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.ModificationBuilder
import com.lightningkite.services.database.optics.IdentityLens
import com.lightningkite.services.database.optics.Lens
import com.lightningkite.services.database.optics.Optic


/**
 * Internal builder for defining denormalization rules on a model.
 * Used as the receiver in the `denormalizations` lambda of [modelInfoWithDeno].
 */
@DenormalizationBuilder.DenormalizationDsl
public class DenormalizationBuilder<Model : Any> {
    /**
     * DSL marker to prevent scope confusion in nested denormalization builder lambdas.
     */
    @DslMarker
    public annotation class DenormalizationDsl


    /**
     * The receiver of [denormalizes] for a non-null reference.
     *
     * [watch] keeps fields in sync with the referenced model. [snapshot] copies them when the reference is set and
     * ignores later changes to the referenced model. Both have an infix form for a single field and a block form for
     * custom calculations.
     */
    @DenormalizationDsl
    public class RequiredRefBuilder<Model : Any, Referenced : HasId<RID>, RID : Comparable<RID>>(
        public val reference: Optic<Model, RID>,
        public val info: ModelInfo<*, Referenced, RID>,
    ) {
        private val list =
            ArrayList<Denormalization.Calculation<Model, Referenced, Referenced>>()

        /**
         * Registers a [calculation] that reruns when any of the [dependencies] change.
         *
         * Prefer [watch] or [snapshot], which also build the modification for you.
         */
        @PublishedApi
        internal fun registerDenormalization(
            dependencies: Set<Optic<Referenced, *>>,
            calculation: suspend context(ServerRuntime) (Referenced) -> Modification<Model>,
        ) {
            dependencies.forEach(::requireSourceOfTruth)
            list.add(Denormalization.Calculation(dependencies, calculation))
        }

        /**
         * Runs [calculation] when the reference is set and again whenever any of the [dependencies] change on the
         * referenced model.
         *
         * [calculation] receives the referenced model and writes the denormalized fields. In the example, `collection`
         * is a path into the referenced model, used to name dependencies, and `it` is the referenced model itself.
         *
         * ```kotlin
         * material.collection.denormalizes(MaterialCollection.info) { collection ->
         *     watch(collection.reviewCycle) {
         *         val cycle = ReviewCycle.info.table().getOrBadRequest(it.reviewCycle)
         *         material.reviewCycleEndAt assign cycle.timeframe.endDate
         *     }
         * }
         * ```
         */
        public inline fun watch(
            dependencies: Set<Optic<Referenced, *>>,
            crossinline calculation: suspend context(ServerRuntime) ModificationBuilder<Model>.(Referenced) -> Unit,
        ) {
            registerDenormalization(dependencies) {
                ModificationBuilder<Model>().apply { calculation(it) }.build()
            }
        }

        public inline fun watch(
            dependency: Optic<Referenced, *>,
            crossinline calculation: suspend context(ServerRuntime) ModificationBuilder<Model>.(Referenced) -> Unit
        ): Unit = watch(setOf(dependency), calculation)

        /**
         * Runs [calculation] whenever the reference is set, on create, replace, or a modification that changes it.
         *
         * Unlike [watch], later changes to the referenced model are not copied, so the fields keep the values from
         * when the reference was set.
         *
         * ```kotlin
         * material.collection.denormalizes(MaterialCollection.info) {
         *     snapshot { material.collectionNameWhenAdded assign it.name }
         * }
         * ```
         */
        public inline fun snapshot(
            crossinline calculation: suspend context(ServerRuntime) ModificationBuilder<Model>.(Referenced) -> Unit,
        ) {
            registerDenormalization(emptySet()) {
                ModificationBuilder<Model>().apply { calculation(it) }.build()
            }
        }

        public fun build(): Denormalization.RequiredRef<Model, Referenced, RID> = Denormalization.RequiredRef(reference, info, list)
    }

    /**
     * The receiver of [denormalizes] for a reference that may be `null`.
     *
     * Works like [RequiredRefBuilder], except that calculations receive `null` while the reference is unset.
     */
    @DenormalizationDsl
    public class OptionalRefBuilder<Model : Any, Referenced : HasId<RID>, RID : Comparable<RID>>(
        public val reference: Optic<Model, RID?>,
        public val info: ModelInfo<*, Referenced, RID>,
    ) {
        private val list: ArrayList<Denormalization.Calculation<Model, Referenced, Referenced?>> =
            ArrayList()

        /**
         * Registers a [calculation] that reruns when any of the [dependencies] change.
         *
         * Prefer [watch] or [snapshot], which also build the modification for you.
         */
        @PublishedApi
        internal fun registerDenormalization(
            dependencies: Set<Optic<Referenced, *>>,
            calculation: suspend context(ServerRuntime) (Referenced?) -> Modification<Model>,
        ) {
            dependencies.forEach(::requireSourceOfTruth)
            list.add(Denormalization.Calculation(dependencies, calculation))
        }

        /**
         * Runs [calculation] when the reference is set and again whenever any of the [dependencies] change on the
         * referenced model.
         *
         * [calculation] receives `null` while the reference is unset. See [RequiredRefBuilder.watch].
         */
        public inline fun watch(
            dependencies: Set<Optic<Referenced, *>>,
            crossinline calculation: suspend context(ServerRuntime) ModificationBuilder<Model>.(Referenced?) -> Unit,
        ) {
            registerDenormalization(dependencies) {
                ModificationBuilder<Model>().apply { calculation(it) }.build()
            }
        }

        public inline fun watch(
            dependency: Optic<Referenced, *>,
            crossinline calculation: suspend context(ServerRuntime) ModificationBuilder<Model>.(Referenced?) -> Unit
        ): Unit = watch(setOf(dependency), calculation)

        /**
         * Runs [calculation] whenever the reference is set, on create, replace, or a modification that changes it.
         *
         * [calculation] receives `null` while the reference is unset. See [RequiredRefBuilder.snapshot].
         */
        public inline fun snapshot(
            crossinline calculation: suspend context(ServerRuntime) ModificationBuilder<Model>.(Referenced?) -> Unit,
        ) {
            registerDenormalization(emptySet()) {
                ModificationBuilder<Model>().apply { calculation(it) }.build()
            }
        }

        public fun build(): Denormalization.OptionalRef<Model, Referenced, RID> = Denormalization.OptionalRef(reference, info, list)
    }

    @PublishedApi
    internal val denormalizations: ArrayList<Denormalization<Model, *, *, *>> = ArrayList()

    /**
     * Register a required (non-nullable) reference denormalization.
     * Called by the [denormalizes] extension function.
     */
    @PublishedApi
    internal inline fun <Ref : HasId<RID>, RID : Comparable<RID>> addRequiredRef(
        reference: Optic<Model, RID>,
        info: ModelInfo<*, Ref, RID>,
        build: RequiredRefBuilder<Model, Ref, RID>.(Lens<Ref, Ref>) -> Unit,
    ) {
        denormalizations.add(
            RequiredRefBuilder(reference, info)
                .apply { build(IdentityLens(info.serializer)) }
                .build()
        )
    }

    /**
     * Register an optional (nullable) reference denormalization.
     * Called by the [denormalizes] extension function for nullable references.
     */
    @PublishedApi
    internal inline fun <Ref : HasId<RID>, RID : Comparable<RID>> addOptionalRef(
        reference: Optic<Model, RID?>,
        info: ModelInfo<*, Ref, RID>,
        build: OptionalRefBuilder<Model, Ref, RID>.(Optic<Ref, Ref>) -> Unit,
    ) {
        denormalizations.add(
            OptionalRefBuilder(reference, info)
                .apply { build(IdentityLens(info.serializer)) }
                .build()
        )
    }
}

// TODO: Api Recommendations
// Claude:
// - Shrink the public surface before release: registerDenormalization, required/optional, build(), and the builders' reference/info can be @PublishedApi internal or internal.
// - requireSourceOfTruth only sees declared dependencies; a watch/snapshot body can still read a @Denormalized field unnoticed.
