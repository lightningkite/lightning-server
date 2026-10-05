package com.lightningkite.lightningserver.audit

import kotlinx.serialization.Serializable


/**
 * How much a bulk mutation is worth recording — the one trade in this layer that a deployment gets
 * to make.
 *
 * The `…IgnoringResult` / `…IgnoringOld` methods exist so a backend can skip reading rows it is about
 * to overwrite. That is exactly the information this log is for, so the default gives the saving up.
 */
@Serializable
public enum class BulkMutationDetail {
    /**
     * Record every changed row, whichever method the caller reached for.
     *
     * The `Ignoring*` calls are upgraded to their effect-returning equivalents, so an
     * `updateManyIgnoringResult` over a large table materialises every matched row and writes a
     * [MutationRecord] per change. That cost is accepted deliberately: a log that can be circumvented
     * by choosing a different method on the same interface is not an audit log, it is a convention.
     *
     * Callers see no difference — an upgraded call returns exactly what the method it replaced would
     * have returned.
     */
    RecordEveryRow,

    /**
     * Let the `Ignoring*` variants keep their cheap path, and record one summary row for the call.
     *
     * The escape hatch for deployments whose bulk writes are too large to pay [RecordEveryRow] for.
     * What it gives up is the whole point of the layer for those calls: a summary row says *how many*
     * rows changed, never *which* ones or *from what*. Prefer narrowing which models are mutation
     * logged over turning this on globally.
     */
    SummaryOnly,
}
