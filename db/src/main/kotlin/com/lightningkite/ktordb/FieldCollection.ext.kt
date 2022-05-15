package com.lightningkite.ktordb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import java.util.*

suspend fun <Model : Any>
        FieldCollection<Model>.updateMany(
    mass: MassModification<Model>
) = updateMany(mass.condition, mass.modification)


suspend fun <Model : HasId<ID>, ID: Comparable<ID>>
        FieldCollection<Model>.updateOneById(
    id: ID,
    modification: Modification<Model>
): Boolean {
    return updateOne(Condition.OnField(HasIdFields._id(), Condition.Equal(id)), modification)
}

suspend fun <Model : HasId<ID>, ID: Comparable<ID>>
        FieldCollection<Model>.findOneAndUpdateById(
    id: ID,
    modification: Modification<Model>
): EntryChange<Model> {
    return findOneAndUpdate(Condition.OnField(HasIdFields._id(), Condition.Equal(id)), modification)
}

suspend fun <Model : HasId<ID>, ID: Comparable<ID>>
        FieldCollection<Model>.deleteOneById(
    id: ID
): Boolean {
    return deleteOne(Condition.OnField(HasIdFields._id(), Condition.Equal(id)))
}

suspend fun <Model : HasId<ID>, ID: Comparable<ID>>
        FieldCollection<Model>.replaceOneById(
    id: ID,
    model: Model
) = replaceOne(Condition.OnField(HasIdFields._id(), Condition.Equal(id)), model)

suspend fun <Model : HasId<ID>, ID: Comparable<ID>>
        FieldCollection<Model>.upsertOneById(
    id: ID,
    model: Model
) = upsertOne(Condition.OnField(HasIdFields._id(), Condition.Equal(id)), model)


suspend fun <Model : HasId<ID>, ID: Comparable<ID>>
        FieldCollection<Model>.get(
    id: ID
): Model? {
    return find(Condition.OnField(HasIdFields._id(), Condition.Equal(id)), limit = 1).firstOrNull()
}

suspend fun <Model : HasId<ID>, ID: Comparable<ID>>
        FieldCollection<Model>.getMany(
    ids: List<ID>
): List<Model> {
    return find(Condition.OnField(HasIdFields._id(), Condition.Inside(ids)), limit = 1).toList()
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