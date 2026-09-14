package com.lightningkite.lightningserver.typed.contract

import com.lightningkite.lightningserver.typed.LightningServerKSchema
import com.lightningkite.lightningserver.typed.LightningServerKSchemaEndpoint
import com.lightningkite.services.database.VirtualEnum
import com.lightningkite.services.database.VirtualSealed
import com.lightningkite.services.database.VirtualStruct
/**
 * Produces a deterministic, wire-only normalized copy of [this] schema for backward-compatibility diffing.
 *
 * The result is what gets committed as a baseline and what both sides of [diffApiContract] operate on. Two captures
 * of an unchanged server canonicalize to byte-for-byte identical JSON (under [apiBaselineJson]), and any field that
 * does not affect wire compatibility is removed so the baseline is stable across doc/URL edits and the diff inherently
 * ignores them:
 *
 * - **Base URLs** ([LightningServerKSchema.baseUrl]/[LightningServerKSchema.baseWsUrl]) are blanked — environment-specific.
 * - **Documentation** is stripped: endpoint `summary`/`description`/`docGroup`, and the `annotations` on every
 *   [VirtualStruct]/[VirtualSealed]/[VirtualEnum]/field/option (annotations carry `@Description` and the like).
 * - **SDK-only grouping** (`interfaces`, endpoint `belongsToInterface`) is dropped — it affects only generated client
 *   code shape, never the wire.
 * - **All collections are sorted** into a stable order: `structures`/`sealedStructures`/`enums`/`aliases` maps rebuilt
 *   sorted by key (into a [LinkedHashMap] so kotlinx-json emits them in that order), `endpoints` by (path, method),
 *   each struct's `fields` by name, enum `options` and sealed `options` by name, and each endpoint's `scopes` set into
 *   a stable sorted order.
 */
public fun LightningServerKSchema.canonicalize(): LightningServerKSchema = LightningServerKSchema(
    baseUrl = "",
    baseWsUrl = "",
    structures = structures.entries
        .sortedBy { it.key }
        .associateTo(LinkedHashMap()) { (k, v) -> k to v.canonical() },
    sealedStructures = sealedStructures.entries
        .sortedBy { it.key }
        .associateTo(LinkedHashMap()) { (k, v) -> k to v.canonical() },
    enums = enums.entries
        .sortedBy { it.key }
        .associateTo(LinkedHashMap()) { (k, v) -> k to v.canonical() },
    aliases = aliases.entries
        .sortedBy { it.key }
        .associateTo(LinkedHashMap()) { (k, v) -> k to v.copy(annotations = listOf()) },
    endpoints = endpoints
        .map { it.canonical() }
        .sortedWith(compareBy({ it.path }, { it.method })),
    interfaces = listOf(),
)

/** Drops docs and SDK-grouping, and canonicalizes the scope set into a stable sorted order. */
private fun LightningServerKSchemaEndpoint.canonical(): LightningServerKSchemaEndpoint = copy(
    docGroup = null,
    description = "",
    summary = "",
    scopes = scopes.sortedBy { it.asString }.toCollection(LinkedHashSet()),
    routes = routes.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { (k, v) -> k to v },
    belongsToInterface = null,
)

// Members are sorted by name and then re-indexed to their sorted position. The original `index` reflects declaration
// order, which is not a wire concern for compatibility (fields/enum values/sealed subtypes are matched by name on the
// wire), so pinning it to the sorted position keeps the baseline stable across reordered declarations.

/** Strips annotations (docs) and sorts fields by name, re-indexing to the sorted position. */
private fun VirtualStruct.canonical(): VirtualStruct = copy(
    annotations = listOf(),
    fields = fields
        .sortedBy { it.name }
        .mapIndexed { i, f -> f.copy(annotations = listOf(), index = i) },
)

/** Strips annotations (docs) and sorts subtype options by name, re-indexing to the sorted position. */
private fun VirtualSealed.canonical(): VirtualSealed = copy(
    annotations = listOf(),
    options = options
        .sortedBy { it.name }
        .mapIndexed { i, o -> o.copy(index = i) },
)

/** Strips annotations (docs) and sorts options by name, re-indexing to the sorted position. */
private fun VirtualEnum.canonical(): VirtualEnum = copy(
    annotations = listOf(),
    options = options
        .sortedBy { it.name }
        .mapIndexed { i, o -> o.copy(annotations = listOf(), index = i) },
)
