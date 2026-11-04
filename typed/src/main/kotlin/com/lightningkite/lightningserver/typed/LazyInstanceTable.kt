package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.services.database.*
import com.lightningkite.services.database.optics.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private class LazyInstanceTable<Model : HasId<ID>, ID : Comparable<ID>>(
    override val wraps: Table<Model>,
    val includeMassUpdates: Boolean = true,
    private val interceptor: suspend (instance: RuntimeDeferred<Model>, mod: Modification<Model>) -> Modification<Model>,
) : Table<Model> by wraps {
    // used only for non-local control flow
    @Suppress("ObjectInheritsException")
    private object InstanceNotFound : Throwable() {
        private fun readResolve(): Any = InstanceNotFound
        override fun fillInStackTrace(): Throwable = this
    }

    private class LazyInstance<out T : Any>(private val wraps: RuntimeDeferred<T>) : RuntimeDeferred<T> {
        @Volatile
        private var cached: T? = null
        private val mutex = Mutex()

        context(server: Engine)
        override suspend fun await(): T =
            cached ?: mutex.withLock {
                cached?.let { return@withLock it }
                val n = wraps.await()
                cached = n
                n
            }

        fun cached(): T? = cached
    }

    private val wrapsPermissions = generateSequence(wraps) { it.wraps }.any { it is ModelPermissionsTable }
    private val idField = serializer._id()

    private fun Condition<Model>.onInstance(instance: Model): Condition<Model> {
        val idEqual = Condition.OnField(idField, Condition.Equal(instance._id))
        return if (wrapsPermissions) Condition.And(this, idEqual)   // maintain full condition for permissions
        else idEqual
    }

    private fun Condition<Model>.onInstance(instance: LazyInstance<Model>): Condition<Model> =
        instance.cached()?.let { // instance retrieved, make sure we only modify the same instance retrieved
            onInstance(it)
        } ?: this


    override suspend fun insert(models: Iterable<Model>): List<Model> =
        wraps.insert(models.map { // no need to catch
            interceptor(Runtime.Constant(it), Modification.Assign(it))(it)
        })

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>,
    ): EntryChange<Model> = try {
        val instance = LazyInstance {
            wraps.findOne(condition, orderBy) ?: throw InstanceNotFound
        }
        val mod = interceptor(instance, Modification.Assign(model))

        wraps.replaceOne(condition.onInstance(instance), mod(model), orderBy)
    } catch (_: InstanceNotFound) {
        EntryChange(null, null)
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>,
    ): Boolean = try {
        val instance = LazyInstance {
            wraps.findOne(condition, orderBy) ?: throw InstanceNotFound
        }
        val mod = interceptor(instance, Modification.Assign(model))

        wraps.replaceOneIgnoringResult(condition.onInstance(instance), mod(model), orderBy)
    } catch (_: InstanceNotFound) {
        false
    }

    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model,
    ): EntryChange<Model> {
        var inserting = false
        // I'm not throwing an InstanceNotFound here because I still want to use the intercepted mod when inserting without having to recalculate
        val instance = LazyInstance { wraps.findOne(condition) ?: model.also { inserting = true } }
        val mod = interceptor(instance, modification)   // Does this need to check Mod.Assign(model) separately?

        return if (inserting) EntryChange(null, wraps.insertOne(mod(model)))
        else wraps.upsertOne(condition.onInstance(instance), mod, mod(model))
    }

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model,
    ): Boolean {
        var inserting = false
        val instance = LazyInstance { wraps.findOne(condition) ?: model.also { inserting = true } }
        val mod = interceptor(instance, modification)

        return if (inserting) {
            wraps.insertOne(mod(model))
            false
        } else wraps.upsertOneIgnoringResult(condition.onInstance(instance), mod, mod(model))
    }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>,
    ): EntryChange<Model> = try {
        val instance = LazyInstance { wraps.findOne(condition, orderBy) ?: throw InstanceNotFound }
        val mod = interceptor(instance, modification)

        wraps.updateOne(condition.onInstance(instance), mod, orderBy)
    } catch (_: InstanceNotFound) {
        EntryChange(null, null)
    }

    override suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>,
    ): Boolean = try {
        val instance = LazyInstance { wraps.findOne(condition, orderBy) ?: throw InstanceNotFound }
        val mod = interceptor(instance, modification)

        wraps.updateOneIgnoringResult(condition.onInstance(instance), mod, orderBy)
    } catch (_: InstanceNotFound) {
        false
    }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): CollectionChanges<Model> {
        if (!includeMassUpdates) return wraps.updateMany(condition, modification)

        return try {
            val models = LazyInstance { wraps.find(condition).toList() }
            val first = LazyInstance { models.await().firstOrNull() ?: throw InstanceNotFound }

            val updatedModForFirst = interceptor(first, modification)

            models.cached()
                ?.let { instances -> // retrieved instances
                    if (instances.isEmpty()) return CollectionChanges()

                    CollectionChanges(
                        buildList(instances.size) {
                            add(
                                wraps.updateOne(condition.onInstance(first), updatedModForFirst)
                            )
                            for (model in instances.drop(1)) add(
                                wraps.updateOne(condition.onInstance(model), interceptor(Runtime.Constant(model), modification))
                            )
                        }
                    )
                }
                ?: wraps.updateMany(condition, updatedModForFirst)
        } catch (_: InstanceNotFound) {
            CollectionChanges()
        }
    }

    override suspend fun updateManyIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): Int {
        if (!includeMassUpdates) return wraps.updateManyIgnoringResult(condition, modification)

        return try {
            val models = LazyInstance { wraps.find(condition).toList() }
            val first = LazyInstance { models.await().firstOrNull() ?: throw InstanceNotFound }

            val updatedModForFirst = interceptor(first, modification)

            models.cached()
                ?.let { cached -> // retrieved instances
                    if (cached.isEmpty()) return 0
                    var count = 0

                    if (wraps.updateOneIgnoringResult(condition.onInstance(first), updatedModForFirst)) count++

                    count += cached.drop(1).count { model ->
                        wraps.updateOneIgnoringResult(
                            condition.onInstance(model),
                            interceptor(Runtime.Constant(model), modification)
                        )
                    }

                    count
                }
                ?: wraps.updateManyIgnoringResult(condition, updatedModForFirst)

        } catch (_: InstanceNotFound) {
            0
        }
    }
}

/**
 * Similar to [interceptChangePerInstance], but the provided instance of the model is lazy and will only be fetched from the database when requested.
 * This makes it much more efficient, as you get to choose when expensive fetched data is needed.
 *
 * Intercepts all changes sent to the database, including inserting, replacing, upserting, and updating. Deletions are not intercepted.
 *
 * **Warning**: Operations that fetch the lazy `instance` are not atomic, as we must retrieve the data before we can calculate the change.
 *
 * @param includeMassUpdates If `false` then [updateMany] and [updateManyIgnoringResult] will both be ignored by the interceptor.
 */
public fun <Model : HasId<ID>, ID : Comparable<ID>> Table<Model>.interceptChangeWithLazyInstance(
    includeMassUpdates: Boolean = true,
    interceptor: suspend (instance: RuntimeDeferred<Model>, mod: Modification<Model>) -> Modification<Model>,
): Table<Model> = LazyInstanceTable(this, includeMassUpdates, interceptor)