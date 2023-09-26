package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.serializer
import kotlin.random.Random

open class ModelRestEndpoints<USER: HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    path: ServerPath,
    val info: ModelInfo<USER, T, ID>
) : ServerPathGroup(path) {

    companion object {
        val all = HashSet<ModelRestEndpoints<*, *, *>>()
    }

    val collectionName get() = info.collectionName

    init {
        if (path.docName == null) path.docName = collectionName
        all.add(this)
    }

    val detailPath = path.arg<ID>("id", info.serialization.idSerializer)
    val wholePath = TypedServerPath0(path)
    val bulkPath = TypedServerPath0(path).path("bulk")

    private fun exampleItem(): T? = (info as? ModelInfoWithDefault<USER, T, ID>)?.exampleItem()

    @Suppress("UNCHECKED_CAST")
    private fun sampleConditions(): List<Condition<T>> {
        return try {
            val sample = exampleItem() ?: return listOf(Condition.Always())
            listOf(Condition.Always<T>()) + info.serialization.serializer.serializableProperties!!
                .take(3)
                .map { Condition.OnField(it as SerializableProperty<T, Any?>, Condition.Equal(it.get(sample))) }
        } catch (e: Exception) {
            listOf(Condition.Always())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun sampleModifications(): List<Modification<T>> {
        return try {
            val sample = exampleItem() ?: return emptyList()
            info.serialization.serializer.serializableProperties!!
                .filter { it.name != "_id" }
                .take(3)
                .map { Modification.OnField(it as SerializableProperty<T, Any?>, Modification.Assign(it.get(sample))) }
        } catch (e: Exception) {
            listOf()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun sampleSorts(): List<List<SortPart<T>>> {
        return try {
            val sample = exampleItem() ?: return emptyList()
            info.serialization.serializer.serializableProperties!!
                .filter { it.serializer.descriptor.kind is PrimitiveKind }
                .let {
                    (1..3).map { _ -> it.shuffled().take(2).map { SortPart(DataClassPathAccess(DataClassPathSelf(info.serialization.serializer), it), Random.nextBoolean()) } }
                }
        } catch (e: Exception) {
            listOf()
        }
    }

    val default = (info as? ModelInfoWithDefault<USER, T, ID>)?.let {
        get("_default_").api<USER, Unit, T>(
            authOptions = info.authOptions,
            inputType = Unit.serializer(),
            outputType = info.serialization.serializer,
            summary = "Default",
            description = "Gets a default ${collectionName} that would be useful to start creating a full one to insert.  Primarily used for administrative interfaces.",
            errorCases = listOf(),
            examples = exampleItem()?.let { listOf(ApiExample(Unit, it)) } ?: listOf(),
            implementation = { input: Unit ->
                info.defaultItem(authOrNull)
            }
        )
    }

    val list = wholePath.get.api(
        authOptions = info.authOptions,
        inputType = Query.serializer(info.serialization.serializer),
        outputType = ListSerializer(info.serialization.serializer),
        summary = "List",
        description = "Gets a list of ${collectionName}s.",
        errorCases = listOf(),
        examples = exampleItem()?.let {
            val sampleSorts = sampleSorts()
            sampleConditions().map {
                ApiExample(Query(it, sampleSorts.random()), List(10) { exampleItem()!! })
            }
        } ?: listOf(),
        implementation = { input: Query<T> ->
            info.collection(this)
                .query(input)
                .toList()
        }
    )

    private fun <R, I, O> lambda(action: suspend R.(I) -> O): suspend R.(I) -> O = action

    // This is used to GET a list objects, but rather than the query being in the parameter
    // it's in the POST body.
    val query = wholePath.path("query").post.api(
        authOptions = info.authOptions,
        inputType = Query.serializer(info.serialization.serializer),
        outputType = ListSerializer(info.serialization.serializer),
        summary = "Query",
        description = "Gets a list of ${collectionName}s that match the given query.",
        errorCases = listOf(),
        examples = exampleItem()?.let {
            val sampleSorts = sampleSorts()
            sampleConditions().map {
                ApiExample(Query(it, sampleSorts.random()), List(10) { exampleItem()!! })
            }
        } ?: listOf(),
        implementation = { input: Query<T> ->
            info.collection(this)
                .query(input)
                .toList()
        }
    )

    // This is used to GET a list objects, but rather than the query being in the parameter
    // it's in the POST body.
    val queryPartial = wholePath.path("query-partial").post.api(
        authOptions = info.authOptions,
        inputType = QueryPartial.serializer(info.serialization.serializer),
        outputType = ListSerializer(PartialSerializer(info.serialization.serializer)),
        summary = "Query Partial",
        description = "Gets parts of ${collectionName}s that match the given query.",
        errorCases = listOf(),
        examples = exampleItem()?.let {
            try {
                val sampleSorts = sampleSorts()
                val paths = info.serialization.serializer.serializableProperties!!.take(2)
                    .map { DataClassPathAccess(DataClassPathSelf(this.info.serialization.serializer), it) }.toSet()
                sampleConditions().map { cond ->
                    ApiExample(
                        QueryPartial(
                            paths,
                            condition = cond,
                            orderBy = sampleSorts.random()
                        ),
                        List(10) { Partial(exampleItem()!!, paths) }
                    )
                }
            } catch (e: Exception) {
                listOf()
            }
        } ?: listOf(),
        implementation = { input: QueryPartial<T> ->
            info.collection(this)
                .queryPartial(input)
                .toList()
        }
    )

    // This is used get a single object with id of _id
    val detail = detailPath.get.api(
        authOptions = info.authOptions,
        inputType = Unit.serializer(),
        outputType = info.serialization.serializer,
        summary = "Detail",
        description = "Gets a single ${collectionName} by ID.",
        errorCases = listOf(
            LSError(
                http = HttpStatus.NotFound.code,
                detail = "",
                message = "There was no known object by that ID.",
                data = ""
            )
        ),
        examples = exampleItem()?.let { listOf(ApiExample(Unit, it)) } ?: listOf(),
        implementation = { input: Unit ->
            info.collection(this)
                .get(path1)
                ?: throw NotFoundException()
        }
    )

    val insertBulk = bulkPath.post.api(
        authOptions = info.authOptions,
        inputType = ListSerializer(info.serialization.serializer),
        outputType = ListSerializer(info.serialization.serializer),
        summary = "Insert Bulk",
        description = "Creates multiple ${collectionName}s at the same time.",
        errorCases = listOf(),
        examples = exampleItem()?.let {
            val items = (1..10).map { exampleItem()!! }
            listOf(ApiExample(items, items))
        } ?: listOf(),
        successCode = HttpStatus.Created,
        implementation = { values: List<T> ->
            try {
                info.collection(this)
                    .insertMany(values)
            } catch (e: UniqueViolationException) {
                throw BadRequestException(detail = "unique", message = e.message, cause = e)
            }
        }
    )

    val insert = wholePath.post.api(
        authOptions = info.authOptions,
        inputType = info.serialization.serializer,
        outputType = info.serialization.serializer,
        summary = "Insert",
        description = "Creates a new ${collectionName}",
        errorCases = listOf(),
        examples = exampleItem()?.let { listOf(ApiExample(it, it)) } ?: listOf(),
        successCode = HttpStatus.Created,
        implementation = { value: T ->
            try {
                info.collection(this)
                    .insertOne(value)
                    ?: throw ForbiddenException("Value was not posted as requested.")
            } catch (e: UniqueViolationException) {
                throw BadRequestException(detail = "unique", message = e.message, cause = e)
            }
        }
    )

    val upsert = detailPath.post.api(
        authOptions = info.authOptions,
        inputType = info.serialization.serializer,
        outputType = info.serialization.serializer,
        summary = "Upsert",
        description = "Creates or updates a ${collectionName}",
        errorCases = listOf(),
        examples = exampleItem()?.let { listOf(ApiExample(it, it)) } ?: listOf(),
        successCode = HttpStatus.Created,
        implementation = { value: T ->
            try {
                info.collection(this)
                    .upsertOneById(path1, value)
                    .new
                    ?: throw NotFoundException()
            } catch (e: UniqueViolationException) {
                throw BadRequestException(detail = "unique", message = e.message, cause = e)
            }
        }
    )

    // This is used replace many objects at once. This does make individual calls to the database. Kmongo does not have a many replace option.
    val bulkReplace = wholePath.put.api(
        authOptions = info.authOptions,
        inputType = ListSerializer(info.serialization.serializer),
        outputType = ListSerializer(info.serialization.serializer),
        summary = "Bulk Replace",
        description = "Modifies many ${collectionName}s at the same time by ID.",
        errorCases = listOf(),
        examples = exampleItem()?.let {
            val items = (1..10).map { exampleItem()!! }
            listOf(ApiExample(items, items))
        } ?: listOf(),
        implementation = { values: List<T> ->
            try {
                val db = info.collection(this)
                values.map { db.replaceOneById(it._id, it) }.mapNotNull { it.new }
            } catch (e: UniqueViolationException) {
                throw BadRequestException(detail = "unique", message = e.message, cause = e)
            }
        }
    )

    val replace = detailPath.put.api(
        authOptions = info.authOptions,
        inputType = info.serialization.serializer,
        outputType = info.serialization.serializer,
        summary = "Replace",
        description = "Replaces a single ${collectionName} by ID.",
        errorCases = listOf(
            LSError(
                http = HttpStatus.NotFound.code,
                detail = "",
                message = "There was no known object by that ID.",
                data = ""
            )
        ),
        examples = exampleItem()?.let { listOf(ApiExample(it, it)) } ?: listOf(),
        implementation = { value: T ->
            try {
                info.collection(this)
                    .replaceOneById(path1, value)
                    .new
                    ?: throw NotFoundException()
            } catch (e: UniqueViolationException) {
                throw BadRequestException(detail = "unique", message = e.message, cause = e)
            }
        }
    )

    val bulkModify = bulkPath.patch.api(
        authOptions = info.authOptions,
        inputType = MassModification.serializer(info.serialization.serializer),
        outputType = Int.serializer(),
        summary = "Bulk Modify",
        description = "Modifies many ${collectionName}s at the same time.  Returns the number of changed items.",
        errorCases = listOf(),
        examples = exampleItem()?.let {
            val c = sampleConditions()
            sampleModifications().map { m ->
                ApiExample(
                    MassModification(
                        c.random(),
                        m
                    ),
                    3
                )
            }
        } ?: listOf(),
        implementation = { input: MassModification<T> ->
            try {
                info.collection(this)
                    .updateManyIgnoringResult(input)
            } catch (e: UniqueViolationException) {
                throw BadRequestException(detail = "unique", message = e.message, cause = e)
            }
        }
    )

    val modifyWithDiff = detailPath.path("delta").patch.api(
        authOptions = info.authOptions,
        inputType = Modification.serializer(info.serialization.serializer),
        outputType = EntryChange.serializer(info.serialization.serializer),
        summary = "Modify with Diff",
        description = "Modifies a ${collectionName} by ID, returning both the previous value and new value.",
        errorCases = listOf(
            LSError(
                http = HttpStatus.NotFound.code,
                detail = "",
                message = "There was no known object by that ID.",
                data = ""
            )
        ),
        examples = exampleItem()?.let {
            sampleModifications().map { m ->
                ApiExample(
                    m,
                    EntryChange(it, m(it))
                )
            }
        } ?: listOf(),
        implementation = { input: Modification<T> ->
            try {
                info.collection(this)
                    .updateOneById(path1, input)
                    .also { if (it.old == null && it.new == null) throw NotFoundException() }
            } catch (e: UniqueViolationException) {
                throw BadRequestException(detail = "unique", message = e.message, cause = e)
            }
        }
    )

    val modify = detailPath.patch.api(
        authOptions = info.authOptions,
        inputType = Modification.serializer(info.serialization.serializer),
        outputType = info.serialization.serializer,
        summary = "Modify",
        description = "Modifies a ${collectionName} by ID, returning the new value.",
        errorCases = listOf(
            LSError(
                http = HttpStatus.NotFound.code,
                detail = "",
                message = "There was no known object by that ID.",
                data = ""
            )
        ),
        examples = exampleItem()?.let {
            sampleModifications().map { m ->
                ApiExample(
                    m,
                    m(it)
                )
            }
        } ?: listOf(),
        implementation = { input: Modification<T> ->
            try {
                info.collection(this)
                    .updateOneById(path1, input)
                    .also { if (it.old == null && it.new == null) throw NotFoundException() }
                    .new!!
            } catch (e: UniqueViolationException) {
                throw BadRequestException(detail = "unique", message = e.message, cause = e)
            }
        }
    )

    val bulkDelete = post("bulk-delete").api(
        authOptions = info.authOptions,
        inputType = Condition.serializer(info.serialization.serializer),
        outputType = Int.serializer(),
        summary = "Bulk Delete",
        description = "Deletes all matching ${collectionName}s, returning the number of deleted items.",
        errorCases = listOf(),
        examples = exampleItem()?.let {
            sampleConditions().map { c ->
                ApiExample(
                    c,
                    3
                )
            }
        } ?: listOf(),
        implementation = { filter: Condition<T> ->
            info.collection(this)
                .deleteManyIgnoringOld(filter)
        }
    )

    val deleteItem = detailPath.delete.api(
        authOptions = info.authOptions,
        inputType = Unit.serializer(),
        outputType = Unit.serializer(),
        summary = "Delete",
        description = "Deletes a ${collectionName} by id.",
        errorCases = listOf(
            LSError(
                http = HttpStatus.NotFound.code,
                detail = "",
                message = "There was no known object by that ID.",
                data = ""
            )
        ),
        implementation = { _: Unit ->
            if (!info.collection(this)
                    .deleteOneById(path1)
            ) {
                throw NotFoundException()
            }
            Unit
        }
    )

    val countGet = get("count").api(
        authOptions = info.authOptions,
        inputType = Condition.serializer(info.serialization.serializer),
        outputType = Int.serializer(),
        summary = "Count",
        description = "Gets the total number of ${collectionName}s matching the given condition.",
        errorCases = listOf(),
        examples = exampleItem()?.let {
            sampleConditions().map { c ->
                ApiExample(
                    c,
                    3
                )
            }
        } ?: listOf(),
        implementation = { condition: Condition<T> ->
            info.collection(this)
                .count(condition)
        }
    )

    val count = post("count").api(
        authOptions = info.authOptions,
        inputType = Condition.serializer(info.serialization.serializer),
        outputType = Int.serializer(),
        summary = "Count",
        description = "Gets the total number of ${collectionName}s matching the given condition.",
        errorCases = listOf(),
        examples = exampleItem()?.let {
            sampleConditions().map { c ->
                ApiExample(
                    c,
                    3
                )
            }
        } ?: listOf(),
        implementation =
        { condition: Condition<T> ->
            info.collection(this)
                .count(condition)
        }
    )

    val groupCount = post("group-count").api(
        authOptions = info.authOptions,
        inputType = GroupCountQuery.serializer(info.serialization.serializer),
        outputType = MapSerializer(String.serializer(), Int.serializer()),
        summary = "Group Count",
        description = "Gets the total number of ${collectionName}s matching the given condition divided by group.",
        errorCases = listOf(),
        examples = exampleItem()?.let {
            sampleConditions().map { c ->
                val f = sampleSorts().random().random().field
                ApiExample(
                    GroupCountQuery(c, f),
                    mapOf(f.getAny(it).toString() to 3)
                )
            }
        } ?: listOf(),
        implementation = { condition: GroupCountQuery<T> ->
            @Suppress("UNCHECKED_CAST")
            info.collection(this)
                .groupCount(condition.condition, condition.groupBy as DataClassPath<T, Any?>)
                .mapKeys { it.key.toString() }
        }
    )

    val aggregate = post("aggregate").api(
        authOptions = info.authOptions,
        inputType = AggregateQuery.serializer(info.serialization.serializer),
        outputType = Double.serializer().nullable,
        summary = "Aggregate",
        description = "Aggregates a property of ${collectionName}s matching the given condition.",
        errorCases = listOf(),
        implementation = { condition: AggregateQuery<T> ->
            @Suppress("UNCHECKED_CAST")
            info.collection(this)
                .aggregate(
                    condition.aggregate,
                    condition.condition,
                    condition.property as DataClassPath<T, Number>
                )
        }
    )

    val groupAggregate = post("group-aggregate").api(
        authOptions = info.authOptions,
        inputType = GroupAggregateQuery.serializer(info.serialization.serializer),
        outputType = MapSerializer(String.serializer(), Double.serializer().nullable),
        summary = "Group Aggregate",
        description = "Aggregates a property of ${collectionName}s matching the given condition divided by group.",
        errorCases = listOf(),
        implementation = { condition: GroupAggregateQuery<T> ->
            @Suppress("UNCHECKED_CAST")
            info.collection(this)
                .groupAggregate(
                    condition.aggregate,
                    condition.condition,
                    condition.groupBy as DataClassPath<T, Any?>,
                    condition.property as DataClassPath<T, Number>
                )
                .mapKeys { it.key.toString() }
        }
    )
}