package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.runtime.Initiator
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.insertOne
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wraps a table so that every change it makes is recorded — when, and only when, its model is
 * audited.
 *
 * Pass it as a `ModelInfo`'s `log` parameter, alongside [DataAccessLog.dataAccessLogged] if both
 * layers are installed:
 *
 * ```kotlin
 * val patients = database.explicitModelInfo(
 *     // ...
 *     log = { audit.mutationLogged(it) },
 * )
 * ```
 *
 * ## Why it is safe to pass everywhere
 * A model without [Audited] is returned untouched, so the "is this audited" decision stays in the
 * annotation alone rather than being split between the annotation and a list of decorated tables.
 * Passing it on every model is the intended usage.
 *
 * ## An audited model with no registry entry fails
 * As with [DataAccessLog.dataAccessLogged], the id is resolved per operation through
 * [AuditRegistry.modelId], which **throws** when the model has no entry — and the registry is
 * populated by scanning *endpoints*, not tables. An `@Audited` model no endpoint's serializer reaches
 * therefore has no id. Here that throw is caught by the fail-open guard rather than failing the call,
 * so the symptom is a loud log line and an unrecorded mutation rather than a rejected write. Make the
 * model reachable from an endpoint's serializer to log it at all.
 *
 * ## Where it sits
 * `ModelInfo` applies `log` below permissions and in **both** `table(auth)` and `table()`, so this
 * observes privileged internal writes — startup tasks, schedule ticks, one service writing another's
 * model — as well as user-facing ones.
 *
 * ## Failure
 * Fail-open: the change is already committed by the time there is anything to record, so a failed
 * audit write is logged loudly and the mutation stands. See [MutationLog].
 */
@OptIn(ExperimentalSerializationApi::class)
context(runtime: ServerRuntime)
public fun <T : Any> MutationLog.mutationLogged(table: Table<T>): Table<T> {
    val descriptor = table.serializer.descriptor
    // Reachability, not a single annotation. `isAudited` inspects one descriptor, so gating on it
    // would return a sealed parent's table — or any wrapper's — untouched even though its children
    // are audited, and every change to them would go unrecorded in silence. `auditedModels()` walks.
    val reachable = descriptor.auditedModels()
    if (reachable.isEmpty()) return table
    // The row names one model, so a table that merely *contains* audited data cannot be attributed.
    // Fail loudly rather than pick one: inconsistent coverage hides the gap instead of failing on it,
    // which is the same rule the registry applies to an unregistered model.
    if (!descriptor.isAudited) throw IllegalStateException(
        "Table \"" + descriptor.serialName + "\" is not itself @Audited but can contain audited " +
            "models (" + reachable.keys.sorted().joinToString(", ") + "). A mutation record names " +
            "one model, so this shape cannot be attributed and is refused rather than logged " +
            "inconsistently."
    )
    val serialName = descriptor.auditSerialName
    val initiator = runtime.initiator
    val json = runtime.internalSerialization.json
    return MutationLogTable(
        wraps = table,
        modelId = { registry.await().modelId(serialName) },
        // Only where a request record actually exists. RequestRecordInterceptor is an http/websocket
        // interceptor, so a task or schedule tick has no row to point at, and storing its execution
        // id here would produce an id that joins to nothing.
        requestId = when (initiator) {
            is Initiator.Http, is Initiator.WebSocket -> initiator.requestRecordId
            else -> null
        },
        // The row that names who is responsible. Unlike `requestId` this is never null: a change
        // made inside a task carries the anchor of whatever launched it, which is the only way an
        // indirect change stays traceable to a person.
        attributedTo = initiator.attributedTo,
        executionId = initiator.executionId,
        causedBy = initiator.causedBy,
        rootExecutionId = initiator.rootExecutionId,
        initiatorKind = initiator.kind(json),
        initiator = json.encodeToString(Initiator.serializer(), initiator),
        json = json,
        nowMillis = { runtime.clock.now().toEpochMilliseconds() },
        write = { with(runtime) { mutations().insertOne(it) } },
        bulkDetail = bulkDetail,
    )
}

/**
 * The `@SerialName` discriminator of this initiator's concrete type — "http", "task", and so on.
 *
 * Read back out of the encoded form rather than matched against the subtypes in a `when`, so that the
 * value in the record is by construction the same string the serialized [Initiator] carries. A `when`
 * would be a second copy of the discriminators, free to drift from the annotations.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun Initiator.kind(json: Json): String =
    json.encodeToJsonElement(Initiator.serializer(), this)
        .jsonObject.getValue(json.configuration.classDiscriminator).jsonPrimitive.content
