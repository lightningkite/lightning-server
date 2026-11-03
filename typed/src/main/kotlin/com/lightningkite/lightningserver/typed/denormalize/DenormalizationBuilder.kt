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
 * Used as the receiver in the `denormalizations` lambda of [withDenormalizations].
 */
@DenormalizationBuilder.DenormalizationDsl
public class DenormalizationBuilder<Model : Any> {
    /**
     * DSL marker to prevent scope confusion in nested denormalization builder lambdas.
     */
    @DslMarker
    public annotation class DenormalizationDsl


    /**
     * Builder context for defining denormalization rules for a required (non-nullable) reference.
     * This is the receiver of the lambda passed to [denormalizes] for non-nullable references.
     *
     * Common methods:
     * - [bind] - Simple field copy that watches for changes in the source field
     * - [set] - Simple field copy WITHOUT watching for changes (lighter weight)
     * - [watch] - Watch specific dependencies and perform calculations when they change
     * - [once] - Perform calculations without watching for changes in the referenced model
     */
    @DenormalizationDsl
    public class RequiredRefBuilder<Model : Any, Referenced : HasId<RID>, RID : Comparable<RID>>(
        public val reference: Lens<Model, RID>,
        public val info: ModelInfo<*, Referenced, RID>,
    ) {
        private val list =
            ArrayList<Denormalization.Calculation<Referenced, suspend context(ServerRuntime) (Referenced) -> Modification<Model>>>()

        /**
         * Low-level method to register a dependency-based calculation.
         * Use [watch], [bind], [set], or [once] instead for most use cases.
         */
        public fun registerDenormalization(
            dependencies: Set<Optic<Referenced, *>>,
            calculation: suspend context(ServerRuntime) (Referenced) -> Modification<Model>,
        ) {
            list.add(Denormalization.Calculation(dependencies, calculation))
        }

        /**
         * Watch specific fields on the referenced model and perform calculations when they change.
         *
         * Use this when you need to:
         * - Perform calculations based on the referenced data
         * - Watch multiple dependencies for the same calculation
         * - Fetch additional data from other tables
         *
         * Example:
         * ```kotlin
         * material.collection.denormalizes(MaterialCollection.info) { collection ->
         *     // Only update when the submitted field changes
         *     watch(collection.submitted) {
         *         material.submittedAt assign it.submitted?.timestamp
         *     }
         *
         *     // Fetch additional data when reviewCycle changes
         *     watch(collection.reviewCycle) {
         *         val cycle = ReviewCycle.info.table().getOrBadRequest(it.reviewCycle)
         *         material.reviewCycleEndAt assign cycle.timeframe.endDate
         *     }
         * }
         * ```
         *
         * @param dependencies One or more fields to watch on the referenced model
         * @param calculation Lambda to create a denormalization modification based on the referenced model
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
         * Denormalize data from the referenced model without watching for changes in the referenced model.
         *
         * Unlike [watch], once calculations run whenever the reference is set (create, replace, modify)
         * but do NOT listen for changes in the referenced model itself. This means the denormalized data
         * will only update when you change which model you're referencing, not when that referenced model changes.
         *
         * **When it runs:**
         * - On create: Yes
         * - On replace: Yes
         * - On modify (when reference changes): Yes
         * - When referenced model changes: **No** (this is the key difference from watch)
         *
         * Use this when you want to:
         * - Copy data from a reference without the overhead of change listeners
         * - Denormalize fields that don't need to stay in sync with the source
         * - Reduce database load by avoiding reactive updates
         *
         * **Note:** The name "once" is somewhat misleading - it actually runs on create, replace, and modify.
         * The key distinction is that it doesn't watch for changes in the referenced model.
         *
         * Example:
         * ```kotlin
         * material.collection.denormalizes(MaterialCollection.info) { collection ->
         *     // Updates whenever material.collection reference changes, but not when
         *     // the MaterialCollection itself is modified
         *     once {
         *         material.collectionName assign it.name
         *     }
         *
         *     // For comparison: this DOES update when the MaterialCollection.status changes
         *     watch(collection.status) {
         *         material.currentStatus assign it.status
         *     }
         * }
         * ```
         *
         * @param calculation Lambda to create a denormalization modification based on the referenced model
         */
        public inline fun once(
            crossinline calculation: suspend context(ServerRuntime) ModificationBuilder<Model>.(Referenced) -> Unit,
        ) {
            registerDenormalization(emptySet()) {
                ModificationBuilder<Model>().apply { calculation(it) }.build()
            }
        }

        public fun build(): Denormalization.RequiredRef<Model, Referenced, RID> = Denormalization.RequiredRef(reference, info, list)
    }

    /**
     * Builder context for defining denormalization rules for an optional (nullable) reference.
     * This is the receiver of the lambda passed to [denormalizes] for nullable references.
     *
     * Similar to [RequiredRefBuilder], but lambdas receive `Referenced?` (nullable) instead of `Referenced`.
     *
     * Common methods:
     * - [bind] - Simple field copy that watches for changes
     * - [set] - Simple field copy WITHOUT watching for changes
     * - [watch] - Watch specific dependencies with nullable reference
     * - [once] - Perform calculations without watching for changes
     */
    @DenormalizationDsl
    public class OptionalRefBuilder<Model : Any, Referenced : HasId<RID>, RID : Comparable<RID>>(
        public val reference: Optic<Model, RID?>,
        public val info: ModelInfo<*, Referenced, RID>,
    ) {
        private val list: ArrayList<Denormalization.Calculation<Referenced, suspend context(ServerRuntime) (Referenced?) -> Modification<Model>>> =
            ArrayList()

        /**
         * Low-level method to register a dependency-based calculation.
         * Use [watch], [bind], [set], or [once] instead for most use cases.
         */
        public fun registerDenormalization(
            dependencies: Set<Optic<Referenced, *>>,
            calculation: suspend context(ServerRuntime) (Referenced?) -> Modification<Model>,
        ) {
            list.add(Denormalization.Calculation(dependencies, calculation))
        }

        /**
         * Watch specific fields on the referenced model and perform calculations when they change.
         * Same as [RequiredRefBuilder.watch] but receives `Referenced?` (nullable) instead of `Referenced`.
         *
         * @param dependencies One or more fields to watch on the referenced model
         * @param calculation Lambda to create a denormalization modification based on the referenced model
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
         * Denormalize data from an optional referenced model without watching for changes in the referenced model.
         *
         * Same as [RequiredRefBuilder.once] but for nullable references. The calculation receives `Referenced?`
         * which will be null if the reference is null.
         *
         * **When it runs:**
         * - On create: Yes
         * - On replace: Yes
         * - On modify (when reference changes): Yes
         * - When referenced model changes: **No** (this is the key difference from watch)
         *
         * @param calculation Lambda to create a denormalization modification. Receives `Referenced?` (nullable).
         * @see RequiredRefBuilder.once for detailed documentation
         */
        public inline fun once(
            crossinline calculation: suspend context(ServerRuntime) ModificationBuilder<Model>.(Referenced?) -> Unit,
        ) {
            registerDenormalization(emptySet()) {
                ModificationBuilder<Model>().apply { calculation(it) }.build()
            }
        }

        public fun build(): Denormalization.OptionalRef<Model, Referenced, RID> = Denormalization.OptionalRef(reference, info, list)
    }

    @PublishedApi
    internal val denormalizations: ArrayList<Denormalization<Model, *, *>> = ArrayList()

    /**
     * Register a required (non-nullable) reference denormalization.
     * Called by the [denormalizes] extension function.
     */
    public inline fun <Ref : HasId<RID>, RID : Comparable<RID>> required(
        reference: Lens<Model, RID>,
        info: ModelInfo<*, Ref, RID>,
        build: RequiredRefBuilder<Model, Ref, RID>.(Optic<Ref, Ref>) -> Unit,
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
    public inline fun <Ref : HasId<RID>, RID : Comparable<RID>> optional(
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
