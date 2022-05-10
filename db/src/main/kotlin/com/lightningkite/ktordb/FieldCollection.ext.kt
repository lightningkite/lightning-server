package com.lightningkite.ktordb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import java.util.*

fun <Model: Any> FieldCollection<Model>.secure(
    rules: SecurityRules<Model>
) = SecuredFieldCollection<Model>(this, rules)

fun <Model: Any> WatchableFieldCollection<Model>.secure(
    rules: SecurityRules<Model>
) = WatchableSecuredFieldCollection<Model>(this, rules)

suspend fun <Model : Any>
        FieldCollection<Model>.updateMany(
    mass: MassModification<Model>
) = updateMany(mass.condition, mass.modification)


suspend fun <Model : HasId>
        FieldCollection<Model>.updateOneById(
    id: UUID,
    modification: Modification<Model>
): Boolean {
    return updateOne(Condition.OnField(HasIdFields._id<Model>(), Condition.Equal(id)), modification)
}

suspend fun <Model : HasId>
        FieldCollection<Model>.findOneAndUpdateById(
    id: UUID,
    modification: Modification<Model>
): EntryChange<Model> {
    return findOneAndUpdate(Condition.OnField(HasIdFields._id<Model>(), Condition.Equal(id)), modification)
}

suspend fun <Model : HasId>
        FieldCollection<Model>.deleteOneById(
    id: UUID
): Boolean {
    return deleteOne(Condition.OnField(HasIdFields._id<Model>(), Condition.Equal(id)))
}

suspend fun <Model : HasId>
        FieldCollection<Model>.replaceOneById(
    id: UUID,
    model: Model
) = replaceOne(Condition.OnField(HasIdFields._id<Model>(), Condition.Equal(id)), model)


suspend fun <Model : HasId>
        FieldCollection<Model>.get(
    id: UUID
): Model? {
    return find(Condition.OnField(HasIdFields._id<Model>(), Condition.Equal(id)), limit = 1).firstOrNull()
}

suspend fun <Model : HasId>
        FieldCollection<Model>.getMany(
    ids: List<UUID>
): List<Model> {
    return find(Condition.OnField(HasIdFields._id<Model>(), Condition.Inside(ids)), limit = 1).toList()
}

suspend fun <Model : Any>
        FieldCollection<Model>.query(query: Query<Model>): Flow<Model> =
    find(query.condition, query.orderBy, query.skip, query.limit)


suspend fun <Model : Any>
        FieldCollection<Model>.find(
    condition: (chain: PropChain<Model, Model>) -> Condition<Model>,
    orderBy: List<SortPart<Model>> = listOf(),
    skip: Int = 0,
    limit: Int = Int.MAX_VALUE,
    maxQueryMs: Long = 15_000
): Flow<Model> = find(condition(startChain()), orderBy, skip, limit, maxQueryMs)
suspend fun <Model : Any>
        FieldCollection<Model>.replaceOne(
    condition: (chain: PropChain<Model, Model>) -> Condition<Model>,
    model: Model
): Model? = replaceOne(condition(startChain()), model)

suspend fun <Model : Any>
        FieldCollection<Model>.upsertOne(
    condition: (chain: PropChain<Model, Model>) -> Condition<Model>,
    model: Model
): Model? = upsertOne(condition(startChain()), model)

suspend fun <Model : Any>
        FieldCollection<Model>.updateOne(
    condition: (chain: PropChain<Model, Model>) -> Condition<Model>,
    modification: (chain: PropChain<Model, Model>) -> Modification<Model>,
): Boolean = updateOne(condition(startChain()), modification(startChain()))

suspend fun <Model : Any>
        FieldCollection<Model>.findOneAndUpdate(
    condition: (chain: PropChain<Model, Model>) -> Condition<Model>,
    modification: (chain: PropChain<Model, Model>) -> Modification<Model>
): EntryChange<Model> = findOneAndUpdate(condition(startChain()), modification(startChain()))

suspend fun <Model : Any>
        FieldCollection<Model>.updateMany(
    condition: (chain: PropChain<Model, Model>) -> Condition<Model>,
    modification: (chain: PropChain<Model, Model>) -> Modification<Model>,
): Int = updateMany(condition(startChain()), modification(startChain()))

suspend fun <Model : Any>
        FieldCollection<Model>.deleteOne(
    condition: (chain: PropChain<Model, Model>) -> Condition<Model>
): Boolean = deleteOne(condition(startChain()))

suspend fun <Model : Any>
        FieldCollection<Model>.deleteMany(
    condition: (chain: PropChain<Model, Model>) -> Condition<Model>
): Int = deleteMany(condition(startChain()))