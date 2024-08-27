package com.lightningkite.lightningdb

import com.lightningkite.serialization.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import com.lightningkite.serialization.SerializableProperty

/**
 * Returns a Flow that will contain ALL the instances of *Model* in the collection.
 * @return Flow that will return ALL the instances of *Model* in the collection.
 */
suspend fun <Model : Any>
        FieldCollection<Model>.all() = find(condition = Condition.Always())

/**
 * Will find a single instance of *Model* from the collection and return it.
 * @param condition The condition used to find an instance of Model.
 * @return The first instance of *Model* that matches the provided condition or null if nothing in the collection matches the condition.
 */
suspend fun <Model : Any>
        FieldCollection<Model>.findOne(condition: Condition<Model>): Model? =
    find(condition = condition, limit = 1).firstOrNull()

/**
 * Inserts and then returns a single instance of *Model* into the database.
 * @param model The instance of *Model* that will be inserted into the collection.
 * @return The instance of *Model* that was inserted into the collection.
 */
suspend fun <Model : Any>
        FieldCollection<Model>.insertOne(model: Model): Model? = insert(listOf(model)).firstOrNull()

/**
 * Inserts and then returns the given Iterable of *Model* into the collection.
 * @param models The Iterable that will be inserted into the collection
 * @return The List of *Model* that was inserted into the database.
 */
suspend fun <Model : Any>
        FieldCollection<Model>.insertMany(models: Iterable<Model>): List<Model> = insert(models)

/**
 * Will update many elements in the collection based on the MassModification's condition and modification.
 * @param mass The MassModification containing the condition for which to update, and a modification for how to update *Models* in the collection.
 * @return An Int representing how many instances of *Model* was updated.
 */
suspend fun <Model : Any>
        FieldCollection<Model>.updateManyIgnoringResult(
    mass: MassModification<Model>
) = updateManyIgnoringResult(mass.condition, mass.modification)


/**
 * Will update a single instance of *Model* using the _id field to determine which to update.
 * @param id The id of the object you want to update.
 * @param modification The modification describing how you which to update the instance.
 * @return A boolean indicating whether an item was updated in the collection.
 */
suspend fun <Model : HasId<ID>, ID : Comparable<ID>>
        FieldCollection<Model>.updateOneByIdIgnoringResult(
    id: ID,
    modification: Modification<Model>
): Boolean {
    return updateOneIgnoringResult(Condition.OnField(serializer._id(), Condition.Equal(id)), modification)
}

/**
 * Will update a single instance of *Model* using the _id field to determine which to update.
 * @param id The id of the object you want to update.
 * @param modification The modification describing how you which to update the instance.
 * @return An Entry change which includes the value before the update and the value after the update.
 */
suspend fun <Model : HasId<ID>, ID : Comparable<ID>>
        FieldCollection<Model>.updateOneById(
    id: ID,
    modification: Modification<Model>
): EntryChange<Model> {
    return updateOne(Condition.OnField(serializer._id(), Condition.Equal(id)), modification)
}

/**
 * Will delete a single instance in a collection using the _id field.
 * @param id The _id of the *Model* instance that you wish to delete.
 * @return A boolean indicating whether an item was deleted.
 */
suspend fun <Model : HasId<ID>, ID : Comparable<ID>>
        FieldCollection<Model>.deleteOneById(
    id: ID
): Boolean {
    return deleteOneIgnoringOld(Condition.OnField(serializer._id(), Condition.Equal(id)))
}

/**
 * Will replace a single instance of *Model* using the _id field to determine which to replace.
 * @param id The id of the object you want to replace.
 * @param model The instance of *Model* you which to replace the existing instance with.
 * @return An Entry change which includes the value before the update and the value after the replace.
 */
suspend fun <Model : HasId<ID>, ID : Comparable<ID>>
        FieldCollection<Model>.replaceOneById(
    id: ID,
    model: Model
) = replaceOne(Condition.OnField(serializer._id(), Condition.Equal(id)), model)


/**
 * Will replace a single instance of *Model* using the _id field to determine which to replace, OR it will insert the new item into the collection if it didn't previously exist.
 * @param id The id of the object you want to upsert.
 * @param model The instance of *Model* you which to upsert into the collection.
 * @return An Entry change which includes the value before the update and the value after the upsert.
 */
suspend fun <Model : HasId<ID>, ID : Comparable<ID>>
        FieldCollection<Model>.upsertOneById(
    id: ID,
    model: Model
) = upsertOne(Condition.OnField(serializer._id(), Condition.Equal(id)), Modification.Assign(model), model)


/**
 * Will retrieve a single instance of *Model* using the _id field.
 * @param id The id of the object you want to retrieve.
 * @return The instance of *Model* from the collection with the same id or null if it does not exist.
 */
suspend fun <Model : HasId<ID>, ID : Comparable<ID>>
        FieldCollection<Model>.get(
    id: ID
): Model? {
    return find(Condition.OnField(serializer._id(), Condition.Equal(id)), limit = 1).firstOrNull()
}

/**
 * Will retrieve a list of instance of *Model* from the collection based on the _id field.
 * @param ids The list of ids of the objects you want to retrieve.
 * @return A List of *Model* from the collection with the matching ids to the ones provided.
 */
suspend fun <Model : HasId<ID>, ID : Comparable<ID>>
        FieldCollection<Model>.getMany(
    ids: Collection<ID>
): List<Model> {
    return find(Condition.OnField(serializer._id(), Condition.Inside(ids.toList()))).toList()
}


/**
 * Will retrieve a list of instance of *Model* from the collection on the values in the query provided.
 * @param query The values used in calculating a search on a collection.
 * @return A List of *Model* from the collection that match the query provided.
 */
suspend fun <Model : Any>
        FieldCollection<Model>.query(query: Query<Model>): Flow<Model> =
    find(query.condition, query.orderBy, query.skip, query.limit)

/**
 * Will retrieve a list of instance of *Model* from the collection on the values in the query provided.
 * @param query The values used in calculating a search on a collection.
 * @return A List of *Model* from the collection that match the query provided.
 */
suspend fun <Model : Any>
        FieldCollection<Model>.queryPartial(query: QueryPartial<Model>): Flow<Partial<Model>> =
    findPartial(query.fields, query.condition, query.orderBy, query.skip, query.limit)

@Deprecated("Use the built in group count with keyPaths.")
suspend inline fun <reified Key, reified Model:Any> FieldCollection<Model>.groupCount(
    condition: Condition<Model> = Condition.Always(),
    groupBy: SerializableProperty<Model, Key>
): Map<Key, Int> {
    return this.groupCount<Key>(condition, path<Model>()[groupBy])
}