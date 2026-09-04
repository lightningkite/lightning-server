package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.insertOne

/**
 * Wraps a table so that every query against it is recorded — when, and only when, its model is
 * audited.
 *
 * Pass it as a `ModelInfo`'s `log` parameter:
 *
 * ```kotlin
 * val patients = database.explicitModelInfo(
 *     // ...
 *     log = { audit.dataAccessLogged(it) },
 * )
 * ```
 *
 * ## Why it is safe to pass everywhere
 * A model without [Audited] is returned untouched, so the "is this audited" decision stays in the
 * annotation alone rather than being split between the annotation and a list of decorated tables.
 * Passing it on every model is the intended usage.
 *
 * ## An audited model with no registry entry fails
 * The id is resolved per operation rather than once at wrap time, because `ModelInfo`'s `log` slot is
 * not suspending while the registry loads asynchronously; the operations themselves are suspending.
 * Resolution uses [AuditRegistry.modelId], which **throws** when the model has no entry — the same
 * fail-closed rule the disclosure log uses.
 *
 * That matters because the registry is populated by scanning **endpoints**, not tables (see
 * [AuditCore]'s bit assignment). An `@Audited` model that no endpoint's serializer reaches has no id,
 * and its reads will fail rather than go unrecorded. For a model that is only ever read internally
 * this is a real limitation: it must be reachable from some endpoint's serializer to be
 * data-access-logged at all. Recorded in `plans/audit-logging.md` 6.2.
 *
 * ## Where it sits
 * `ModelInfo` applies `log` below permissions and in **both** `table(auth)` and `table()`, so this
 * observes privileged internal reads — startup tasks, schedule ticks, one service reading another's
 * model — as well as user-facing ones. That is the whole reason this layer exists rather than
 * relying on the typed layer's disclosure log; see `plans/audit-logging.md` 2.1 and 6.1.
 *
 * ## Failure
 * Fail-closed: a query whose record cannot be written does not run. The blast radius is larger than
 * the disclosure log's, because it covers privileged reads too — see 6.2.
 */
context(runtime: ServerRuntime)
public fun <T : Any> DataAccessLog.dataAccessLogged(table: Table<T>): Table<T> {
    val descriptor = table.serializer.descriptor
    // Reachability, not a single annotation. `isAudited` inspects one descriptor, so gating on it
    // would return a sealed parent's table — or any wrapper's — untouched even though its children
    // are audited, and every read of them would go unrecorded in silence. `auditedModels()` walks.
    val reachable = descriptor.auditedModels()
    if (reachable.isEmpty()) return table
    // The row names one model, so a table that merely *contains* audited data cannot be attributed.
    // Fail loudly rather than pick one: inconsistent coverage hides the gap instead of failing on it,
    // which is the same rule the registry applies to an unregistered model.
    if (!descriptor.isAudited) throw IllegalStateException(
        "Table \"" + descriptor.serialName + "\" is not itself @Audited but can contain audited " +
            "models (" + reachable.keys.sorted().joinToString(", ") + "). A data access record names " +
            "one model, so this shape cannot be attributed and is refused rather than logged " +
            "inconsistently."
    )
    val serialName = descriptor.auditSerialName
    val initiator = runtime.initiator
    return DataAccessLogTable(
        wraps = table,
        modelId = { registry.await().modelId(serialName) },
        // The anchor, not this execution's own id. A query run inside a task has no request row of
        // its own, so `requestRecordId` would store an id that joins to nothing; `attributedTo`
        // names the row of whoever is responsible. Identical for http and websocket executions,
        // which are their own anchor.
        requestId = initiator.attributedTo,
        executionId = initiator.executionId,
        json = runtime.internalSerialization.json,
        nowMillis = { runtime.clock.now().toEpochMilliseconds() },
        write = { with(runtime) { dataAccess().insertOne(it) } },
    )
}
