package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.ApiExample
import com.lightningkite.lightningserver.typed.typed
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.serializer
import kotlin.random.Random

open class ModelRestEndpoints<USER, T : HasId<ID>, ID : Comparable<ID>>(
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

    private fun exampleItem(): T? = (info as? ModelInfoWithDefault<USER, T, ID>)?.exampleItem()
    private fun sampleConditions(): List<Condition<T>> {
        return try {
            val sample = exampleItem() ?: return listOf(Condition.Always())
            listOf(Condition.Always<T>()) + info.serialization.serializer.attemptGrabFields().entries
                .take(3)
                .map { Condition.OnField(it.value, Condition.Equal(it.value.get(sample))) }
        } catch (e: Exception) {
            listOf(Condition.Always())
        }
    }

    private fun sampleModifications(): List<Modification<T>> {
        return try {
            val sample = exampleItem() ?: return emptyList()
            info.serialization.serializer.attemptGrabFields().entries
                .filter { it.key != "_id" }
                .take(3)
                .map { Modification.OnField(it.value, Modification.Assign(it.value.get(sample))) }
        } catch (e: Exception) {
            listOf()
        }
    }

    private fun sampleSorts(): List<List<SortPart<T>>> {
        return try {
            val sample = exampleItem() ?: return emptyList()
            info.serialization.serializer.attemptGrabFields().entries
                .filter { Serialization.Internal.module.serializer(it.value.returnType).descriptor.kind is PrimitiveKind }
                .let {
                    (1..3).map { _ -> it.shuffled().take(2).map { SortPart(it.value, Random.nextBoolean()) } }
                }
        } catch (e: Exception) {
            listOf()
        }
    }

    val default = (info as? ModelInfoWithDefault<USER, T, ID>)?.let {
        get("_default_").typed(
            authRequirement = info.serialization.authRequirement,
            inputType = Unit.serializer(),
            outputType = info.serialization.serializer,
            summary = "Default",
            description = "Gets a default ${collectionName} that would be useful to start creating a full one to insert.  Primarily used for administrative interfaces.",
            errorCases = listOf(),
            examples = exampleItem()?.let { listOf(ApiExample(Unit, it)) } ?: listOf(),
            implementation = { user: USER, input: Unit ->
                info.defaultItem(user)
            }
        )
    }

    val list = get.typed(
        authRequirement = info.serialization.authRequirement,
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
        implementation = { user: USER, input: Query<T> ->
            info.collection(user)
                .query(input)
                .toList()
        }
    )

    // This is used to GET a list objects, but rather than the query being in the parameter
    // it's in the POST body.
    val query = post("query").typed(
        authRequirement = info.serialization.authRequirement,
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
        implementation = { user: USER, input: Query<T> ->
            info.collection(user)
                .query(input)
                .toList()
        }
    )

    // This is used to GET a list objects, but rather than the query being in the parameter
    // it's in the POST body.
    val queryPartial = post("query-partial").typed(
        authRequirement = info.serialization.authRequirement,
        inputType = QueryPartial.serializer(info.serialization.serializer),
        outputType = ListSerializer(PartialSerializer(info.serialization.serializer)),
        summary = "Query Partial",
        description = "Gets parts of ${collectionName}s that match the given query.",
        errorCases = listOf(),
        examples = exampleItem()?.let {
            try {
                val sampleSorts = sampleSorts()
                val paths = info.serialization.serializer.attemptGrabFields().entries.take(2)
                    .map { DataClassPathAccess(DataClassPathSelf(), it.value) }.toSet()
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
        implementation = { user: USER, input: QueryPartial<T> ->
            info.collection(user)
                .queryPartial(input)
                .toList()
        }
    )

    // This is used get a single object with id of _id
    val detail = get("{id}").typed(
        authRequirement = info.serialization.authRequirement,
        inputType = Unit.serializer(),
        outputType = info.serialization.serializer,
        pathType = info.serialization.idSerializer,
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
        implementation = { user: USER, id: ID, input: Unit ->
            info.collection(user)
                .get(id)
                ?: throw NotFoundException()
        }
    )

    val insertBulk = post("bulk").typed(
        authRequirement = info.serialization.authRequirement,
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
        implementation = { user: USER, values: List<T> ->
            try {
                info.collection(user)
                    .insertMany(values)
            } catch (e: UniqueViolationException) {
                throw BadRequestException(detail = "unique", message = e.message, cause = e)
            }
        }
    )

    val insert = post("").typed(
        authRequirement = info.serialization.authRequirement,
        inputType = info.serialization.serializer,
        outputType = info.serialization.serializer,
        summary = "Insert",
        description = "Creates a new ${collectionName}",
        errorCases = listOf(),
        examples = exampleItem()?.let { listOf(ApiExample(it, it)) } ?: listOf(),
        successCode = HttpStatus.Created,
        implementation = { user: USER, value: T ->
            try {
                info.collection(user)
                    .insertOne(value)
                    ?: throw ForbiddenException("Value was not posted as requested.")
            } catch (e: UniqueViolationException) {
                throw BadRequestException(detail = "unique", message = e.message, cause = e)
            }
        }
    )

    val upsert = post("{id}").typed(
        authRequirement = info.serialization.authRequirement,
        inputType = info.serialization.serializer,
        outputType = info.serialization.serializer,
        pathType = info.serialization.idSerializer,
        summary = "Upsert",
        description = "Creates or updates a ${collectionName}",
        errorCases = listOf(),
        examples = exampleItem()?.let { listOf(ApiExample(it, it)) } ?: listOf(),
        successCode = HttpStatus.Created,
        implementation = { user: USER, id: ID, value: T ->
            try {
                info.collection(user)
                    .upsertOneById(id, value)
                    .new
                    ?: throw NotFoundException()
            } catch (e: UniqueViolationException) {
                throw BadRequestException(detail = "unique", message = e.message, cause = e)
            }
        }
    )

    // This is used replace many objects at once. This does make individual calls to the database. Kmongo does not have a many replace option.
    val bulkReplace = put("").typed(
        authRequirement = info.serialization.authRequirement,
        inputType = ListSerializer(info.serialization.serializer),
        outputType = ListSerializer(info.serialization.serializer),
        summary = "Bulk Replace",
        description = "Modifies many ${collectionName}s at the same time by ID.",
        errorCases = listOf(),
        examples = exampleItem()?.let {
            val items = (1..10).map { exampleItem()!! }
            listOf(ApiExample(items, items))
        } ?: listOf(),
        implementation = { user: USER, values: List<T> ->
            try {
                val db = info.collection(user)
                values.map { db.replaceOneById(it._id, it) }.mapNotNull { it.new }
            } catch (e: UniqueViolationException) {
                throw BadRequestException(detail = "unique", message = e.message, cause = e)
            }
        }
    )

    val replace = put("{id}").typed(
        authRequirement = info.serialization.authRequirement,
        inputType = info.serialization.serializer,
        outputType = info.serialization.serializer,
        pathType = info.serialization.idSerializer,
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
        implementation = { user: USER, id: ID, value: T ->
            try {
                info.collection(user)
                    .replaceOneById(id, value)
                    .new
                    ?: throw NotFoundException()
            } catch (e: UniqueViolationException) {
                throw BadRequestException(detail = "unique", message = e.message, cause = e)
            }
        }
    )

    val bulkModify = patch("bulk").typed(
        authRequirement = info.serialization.authRequirement,
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
        implementation = { user: USER, input: MassModification<T> ->
            try {
                info.collection(user)
                    .updateManyIgnoringResult(input)
            } catch (e: UniqueViolationException) {
                throw BadRequestException(detail = "unique", message = e.message, cause = e)
            }
        }
    )

    val modifyWithDiff = patch("{id}/delta").typed(
        authRequirement = info.serialization.authRequirement,
        inputType = Modification.serializer(info.serialization.serializer),
        outputType = EntryChange.serializer(info.serialization.serializer),
        pathType = info.serialization.idSerializer,
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
        implementation = { user: USER, id: ID, input: Modification<T> ->
            try {
                info.collection(user)
                    .updateOneById(id, input)
                    .also { if (it.old == null && it.new == null) throw NotFoundException() }
            } catch (e: UniqueViolationException) {
                throw BadRequestException(detail = "unique", message = e.message, cause = e)
            }
        }
    )

    val modify = patch("{id}").typed(
        authRequirement = info.serialization.authRequirement,
        inputType = Modification.serializer(info.serialization.serializer),
        outputType = info.serialization.serializer,
        pathType = info.serialization.idSerializer,
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
        implementation = { user: USER, id: ID, input: Modification<T> ->
            try {
                info.collection(user)
                    .updateOneById(id, input)
                    .also { if (it.old == null && it.new == null) throw NotFoundException() }
                    .new!!
            } catch (e: UniqueViolationException) {
                throw BadRequestException(detail = "unique", message = e.message, cause = e)
            }
        }
    )

    val bulkDelete = post("bulk-delete").typed(
        authRequirement = info.serialization.authRequirement,
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
        implementation = { user: USER, filter: Condition<T> ->
            info.collection(user)
                .deleteManyIgnoringOld(filter)
        }
    )

    val deleteItem = delete("{id}").typed(
        authRequirement = info.serialization.authRequirement,
        inputType = Unit.serializer(),
        outputType = Unit.serializer(),
        pathType = info.serialization.idSerializer,
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
        implementation = { user: USER, id: ID, _: Unit ->
            if (!info.collection(user)
                    .deleteOneById(id)
            ) {
                throw NotFoundException()
            }
            Unit
        }
    )

    val countGet = get("count").typed(
        authRequirement = info.serialization.authRequirement,
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
        implementation = { user: USER, condition: Condition<T> ->
            info.collection(user)
                .count(condition)
        }
    )

    val count = post("count").typed(
        authRequirement = info.serialization.authRequirement,
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
        { user: USER, condition: Condition<T> ->
            info.collection(user)
                .count(condition)
        }
    )

    val groupCount = post("group-count").typed(
        authRequirement = info.serialization.authRequirement,
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
        implementation = { user: USER, condition: GroupCountQuery<T> ->
            @Suppress("UNCHECKED_CAST")
            info.collection(user)
                .groupCount(condition.condition, condition.groupBy as DataClassPath<T, Any?>)
                .mapKeys { it.key.toString() }
        }
    )

    val aggregate = post("aggregate").typed(
        authRequirement = info.serialization.authRequirement,
        inputType = AggregateQuery.serializer(info.serialization.serializer),
        outputType = Double.serializer().nullable,
        summary = "Aggregate",
        description = "Aggregates a property of ${collectionName}s matching the given condition.",
        errorCases = listOf(),
        implementation = { user: USER, condition: AggregateQuery<T> ->
            @Suppress("UNCHECKED_CAST")
            info.collection(user)
                .aggregate(
                    condition.aggregate,
                    condition.condition,
                    condition.property as DataClassPath<T, Number>
                )
        }
    )

    val groupAggregate = post("group-aggregate").typed(
        authRequirement = info.serialization.authRequirement,
        inputType = GroupAggregateQuery.serializer(info.serialization.serializer),
        outputType = MapSerializer(String.serializer(), Double.serializer().nullable),
        summary = "Group Aggregate",
        description = "Aggregates a property of ${collectionName}s matching the given condition divided by group.",
        errorCases = listOf(),
        implementation = { user: USER, condition: GroupAggregateQuery<T> ->
            @Suppress("UNCHECKED_CAST")
            info.collection(user)
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