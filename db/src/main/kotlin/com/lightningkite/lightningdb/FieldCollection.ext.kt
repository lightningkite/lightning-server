package com.lightningkite.lightningdb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList

suspend fun <Model : Any>
        FieldCollection<Model>.all() = find(condition = Condition.Always())

suspend fun <Model : Any>
        FieldCollection<Model>.findOne(condition: Condition<Model>): Model? = find(condition = condition, limit = 1).firstOrNull()

suspend fun <Model : Any>
        FieldCollection<Model>.insertOne(model: Model): Model = insert(listOf(model)).first()

suspend fun <Model : Any>
        FieldCollection<Model>.insertMany(models: List<Model>): List<Model> = insert(models)

suspend fun <Model : Any>
        FieldCollection<Model>.updateManyIgnoringResult(
    mass: MassModification<Model>
) = updateManyIgnoringResult(mass.condition, mass.modification)


suspend fun <Model : HasId<ID>, ID : Comparable<ID>>
        FieldCollection<Model>.updateOneByIdIgnoringResult(
    id: ID,
    modification: Modification<Model>
): Boolean {
    return updateOneIgnoringResult(Condition.OnField(HasIdFields._id(), Condition.Equal(id)), modification)
}

suspend fun <Model : HasId<ID>, ID : Comparable<ID>>
        FieldCollection<Model>.updateOneById(
    id: ID,
    modification: Modification<Model>
): EntryChange<Model> {
    return updateOne(Condition.OnField(HasIdFields._id(), Condition.Equal(id)), modification)
}

suspend fun <Model : HasId<ID>, ID : Comparable<ID>>
        FieldCollection<Model>.deleteOneById(
    id: ID
): Boolean {
    return deleteOneIgnoringOld(Condition.OnField(HasIdFields._id(), Condition.Equal(id)))
}

suspend fun <Model : HasId<ID>, ID : Comparable<ID>>
        FieldCollection<Model>.replaceOneById(
    id: ID,
    model: Model
) = replaceOne(Condition.OnField(HasIdFields._id(), Condition.Equal(id)), model)

suspend fun <Model : HasId<ID>, ID : Comparable<ID>>
        FieldCollection<Model>.upsertOneById(
    id: ID,
    model: Model
) = upsertOne(Condition.OnField(HasIdFields._id(), Condition.Equal(id)), Modification.Assign(model), model)


suspend fun <Model : HasId<ID>, ID : Comparable<ID>>
        FieldCollection<Model>.get(
    id: ID
): Model? {
    return find(Condition.OnField(HasIdFields._id(), Condition.Equal(id)), limit = 1).firstOrNull()
}

suspend fun <Model : HasId<ID>, ID : Comparable<ID>>
        FieldCollection<Model>.getMany(
    ids: List<ID>
): List<Model> {
    return find(Condition.OnField(HasIdFields._id(), Condition.Inside(ids))).toList()
}

suspend fun <Model : Any>
        FieldCollection<Model>.query(query: Query<Model>): Flow<Model> =
    find(query.condition, query.orderBy, query.skip, query.limit)
