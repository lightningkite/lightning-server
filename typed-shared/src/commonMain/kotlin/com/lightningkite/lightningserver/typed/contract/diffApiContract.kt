package com.lightningkite.lightningserver.typed.contract

import com.lightningkite.lightningserver.typed.LightningServerKSchema
import com.lightningkite.lightningserver.typed.LightningServerKSchemaEndpoint
import com.lightningkite.services.database.VirtualField
import com.lightningkite.services.database.VirtualTypeReference
import kotlinx.serialization.json.Json

/**
 * The JSON configuration used for serializing API baselines: stable, human-diffable, and with defaults written out
 * so the on-disk form does not shift when default values change.
 */
public val apiBaselineJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
}

/**
 * Direction(s) in which a type is used across the API.
 *
 * The direction determines whether a change is breaking: e.g. adding an enum option is safe for a type only
 * sent in responses (OUTPUT) but breaking for one only accepted in requests (INPUT), and vice-versa. A type used
 * in both directions takes the union of constraints (a change is breaking if it is breaking in either direction).
 */
private enum class Direction { INPUT, OUTPUT, BOTH, NONE }

private fun Direction.plus(other: Direction): Direction = when {
    this == other -> this
    this == Direction.NONE -> other
    other == Direction.NONE -> this
    else -> Direction.BOTH
}

private val Direction.usesInput: Boolean get() = this == Direction.INPUT || this == Direction.BOTH
private val Direction.usesOutput: Boolean get() = this == Direction.OUTPUT || this == Direction.BOTH

/**
 * Computes the direction in which each named type is reachable, starting from endpoint inputs/routes (INPUT) and
 * endpoint outputs (OUTPUT), following type-reference arguments, struct fields, and sealed subtypes transitively.
 */
private fun reachability(schema: LightningServerKSchema): Map<String, Direction> {
    val structs = schema.structures
    val sealeds = schema.sealedStructures
    val result = HashMap<String, Direction>()

    fun visit(serialName: String, direction: Direction) {
        val existing = result[serialName] ?: Direction.NONE
        val combined = existing.plus(direction)
        if (result.containsKey(serialName) && combined == existing) return
        result[serialName] = combined
        structs[serialName]?.fields?.forEach { field -> visitRef(field.type, direction, ::visit) }
        sealeds[serialName]?.options?.forEach { option -> visit(option.type.serialName, direction) }
    }

    for (endpoint in schema.endpoints) {
        visitRef(endpoint.input, Direction.INPUT, ::visit)
        endpoint.routes.values.forEach { visitRef(it, Direction.INPUT, ::visit) }
        visitRef(endpoint.output, Direction.OUTPUT, ::visit)
    }
    return result
}

/** Walks a type reference and all its generic arguments, applying [visit] to each named type. */
private fun visitRef(ref: VirtualTypeReference, direction: Direction, visit: (String, Direction) -> Unit) {
    visit(ref.serialName, direction)
    ref.arguments.forEach { visitRef(it, direction, visit) }
}

/** Required iff not optional, not nullable, and lacks any default value. */
private val VirtualField.required: Boolean
    get() = !optional && !type.isNullable && defaultJson == null && defaultCode == null

/** Stable endpoint identity used for matching across versions. */
private fun key(e: LightningServerKSchemaEndpoint) = "${e.method} ${e.path}"

/** Renders a type reference for human-readable diff messages. */
private fun VirtualTypeReference.render(): String =
    serialName + (arguments.takeIf { it.isNotEmpty() }?.joinToString(", ", "<", ">") { it.render() } ?: "") +
        (if (isNullable) "?" else "")

/**
 * Diffs a [baseline] schema against the [current] one and classifies every change.
 *
 * Operates directly on raw [LightningServerKSchema]s — no normalization pass is required. The comparison is
 * **order-independent and documentation-insensitive by construction**: it only ever reads the wire-relevant fields and
 * matches things by stable identity rather than position:
 *
 * - structures/enums/sealed types are matched by their map key (`serialName`);
 * - endpoints by `(method, path)` (see [key]);
 * - struct fields by name; enum options by name (compared as a SET for membership);
 *   sealed subtypes by their option `serialName` (also a set).
 *
 * Consequently the following never influence the diff and need not be stripped beforehand: `baseUrl`/`baseWsUrl`; all
 * documentation (endpoint `summary`/`description`/`docGroup` and `@Description`/other annotations on
 * structs/enums/fields/options); `interfaces`/`belongsToInterface` (SDK grouping only); and declaration `index` /
 * collection ordering. The one place order is inspected is enum option order — see [ApiChangeCode.ENUM_REORDERED].
 *
 * This is pure: it depends only on the two schemas and the [allowlist]. The per-type breaking rules are direction-aware
 * (a type's reachability as request input vs. response output is computed by [reachability]):
 *
 * - **Breaking:** endpoint removed; input gains a required field or an optional field becomes required; output loses a
 *   field or a field becomes nullable; any field's type changes incompatibly (safe widenings such as `Int`→`Long` are
 *   still treated as breaking by default); an output enum gains options (output-enum-widened); an input enum loses
 *   options (input-enum-narrowed); auth scopes are tightened; a type or sealed subtype is removed.
 * - **Potentially breaking:** an enum's options are reordered without changing the set (see [ApiChangeCode.ENUM_REORDERED]).
 * - **Non-breaking:** endpoint added; optional input field added; output field added; output enum loses options;
 *   input enum gains options; auth loosened; documentation changes (never inspected, so invisible).
 *
 * Websocket outbound messages are treated exactly like HTTP outputs.
 *
 * @param allowlist Suppressions for intentional breaks; matched changes are reported with `suppressed = true`.
 */
public fun diffApiContract(
    baseline: LightningServerKSchema,
    current: LightningServerKSchema,
    allowlist: ApiAllowlist = ApiAllowlist.EMPTY,
): ApiDiffReport {
    val changes = ArrayList<ApiChange>()

    val baseDirections = reachability(baseline)
    val curDirections = reachability(current)
    // A type's effective direction is the union of how it's used in both versions, so that, e.g., a type that was
    // output-only but becomes input-only still gets input constraints applied.
    fun direction(serialName: String): Direction =
        (baseDirections[serialName] ?: Direction.NONE).plus(curDirections[serialName] ?: Direction.NONE)

    // --- Endpoints ---
    val baseEndpoints = baseline.endpoints.associateBy { key(it) }
    val curEndpoints = current.endpoints.associateBy { key(it) }

    for ((k, baseEp) in baseEndpoints) {
        val curEp = curEndpoints[k]
        if (curEp == null) {
            changes += ApiChange(ApiChangeCode.ENDPOINT_REMOVED, k, "Endpoint $k was removed.")
            continue
        }
        // Auth: tightening = current requires a scope the baseline did not (clients with old tokens get rejected).
        val baseScopes = baseEp.scopes.map { it.asString }.toSet()
        val curScopes = curEp.scopes.map { it.asString }.toSet()
        val added = curScopes - baseScopes
        val removed = baseScopes - curScopes
        if (added.isNotEmpty()) {
            changes += ApiChange(ApiChangeCode.AUTH_TIGHTENED, k, "Auth scopes added: ${added.sorted()}.")
        }
        if (removed.isNotEmpty()) {
            changes += ApiChange(ApiChangeCode.AUTH_LOOSENED, k, "Auth scopes removed: ${removed.sorted()}.")
        }
        // Endpoint-level input/output type identity changes (e.g. a whole DTO swapped).
        if (baseEp.input.serialName != curEp.input.serialName || baseEp.input.isNullable != curEp.input.isNullable) {
            changes += ApiChange(ApiChangeCode.TYPE_CHANGED, "$k input", "Input type changed from ${baseEp.input.render()} to ${curEp.input.render()}.")
        }
        if (baseEp.output.serialName != curEp.output.serialName || baseEp.output.isNullable != curEp.output.isNullable) {
            changes += ApiChange(ApiChangeCode.TYPE_CHANGED, "$k output", "Output type changed from ${baseEp.output.render()} to ${curEp.output.render()}.")
        }
    }
    for ((k, _) in curEndpoints) {
        if (k !in baseEndpoints) changes += ApiChange(ApiChangeCode.ENDPOINT_ADDED, k, "Endpoint $k was added.")
    }

    // --- Structs ---
    val baseStructs = baseline.structures
    val curStructs = current.structures
    for ((name, baseStruct) in baseStructs) {
        val curStruct = curStructs[name]
        val dir = direction(name)
        if (curStruct == null) {
            // Only meaningful if the type was actually reachable in the API surface.
            if (dir != Direction.NONE) {
                changes += ApiChange(ApiChangeCode.TYPE_REMOVED, name, "Type $name was removed.")
            }
            continue
        }
        val baseFields = baseStruct.fields.associateBy { it.name }
        val curFields = curStruct.fields.associateBy { it.name }
        for ((fname, baseField) in baseFields) {
            val curField = curFields[fname]
            val loc = "$name.$fname"
            if (curField == null) {
                if (dir.usesOutput) {
                    changes += ApiChange(ApiChangeCode.OUTPUT_FIELD_REMOVED, loc, "Output field $loc was removed.")
                }
                // For input-only, a removed field is simply no longer read; not breaking on its own.
                continue
            }
            // Type change on the field itself.
            if (!typeRefCompatible(baseField.type, curField.type)) {
                changes += ApiChange(ApiChangeCode.TYPE_CHANGED, loc, "Field $loc type changed from ${baseField.type.render()} to ${curField.type.render()}.")
            } else if (dir.usesOutput && !baseField.type.isNullable && curField.type.isNullable) {
                changes += ApiChange(ApiChangeCode.OUTPUT_FIELD_BECAME_NULLABLE, loc, "Output field $loc became nullable.")
            }
            // Input field became required.
            if (dir.usesInput && !baseField.required && curField.required) {
                changes += ApiChange(ApiChangeCode.INPUT_FIELD_BECAME_REQUIRED, loc, "Input field $loc became required.")
            }
        }
        for ((fname, curField) in curFields) {
            if (fname in baseFields) continue
            val loc = "$name.$fname"
            if (dir.usesInput) {
                if (curField.required) {
                    changes += ApiChange(ApiChangeCode.INPUT_REQUIRED_FIELD_ADDED, loc, "Required input field $loc was added.")
                } else {
                    changes += ApiChange(ApiChangeCode.INPUT_OPTIONAL_FIELD_ADDED, loc, "Optional input field $loc was added.")
                }
            }
            if (dir.usesOutput) {
                changes += ApiChange(ApiChangeCode.OUTPUT_FIELD_ADDED, loc, "Output field $loc was added.")
            }
        }
    }

    // --- Enums ---
    val baseEnums = baseline.enums
    val curEnums = current.enums
    for ((name, baseEnum) in baseEnums) {
        val curEnum = curEnums[name]
        val dir = direction(name)
        if (curEnum == null) {
            if (dir != Direction.NONE) changes += ApiChange(ApiChangeCode.TYPE_REMOVED, name, "Enum $name was removed.")
            continue
        }
        val baseOptions = baseEnum.options.map { it.name }.toSet()
        val curOptions = curEnum.options.map { it.name }.toSet()
        val addedOptions = curOptions - baseOptions
        val removedOptions = baseOptions - curOptions
        // Widened = options added. A wider output set can surprise clients; a wider input set is harmless.
        if (addedOptions.isNotEmpty() && dir.usesOutput) {
            changes += ApiChange(ApiChangeCode.OUTPUT_ENUM_WIDENED, name, "Output enum $name gained options: ${addedOptions.sorted()}.")
        }
        if (addedOptions.isNotEmpty() && dir.usesInput) {
            changes += ApiChange(ApiChangeCode.INPUT_ENUM_WIDENED, name, "Input enum $name gained options: ${addedOptions.sorted()}.")
        }
        // Narrowed = options removed. A narrower input set rejects values old clients still send; a narrower output is safe.
        if (removedOptions.isNotEmpty() && dir.usesInput) {
            changes += ApiChange(ApiChangeCode.INPUT_ENUM_NARROWED, name, "Input enum $name lost options: ${removedOptions.sorted()}.")
        }
        if (removedOptions.isNotEmpty() && dir.usesOutput) {
            changes += ApiChange(ApiChangeCode.OUTPUT_ENUM_NARROWED, name, "Output enum $name lost options: ${removedOptions.sorted()}.")
        }
        // Reordering the SAME set of options is not guaranteed safe: some serializers encode enums by ordinal index
        // rather than by name, so changing declaration order silently remaps existing persisted/serialized values.
        // We can't know which encoding a given client uses, so we surface this as POTENTIALLY_BREAKING rather than
        // ignore it. (Added/removed options are already handled above by their own codes.)
        if (dir != Direction.NONE && addedOptions.isEmpty() && removedOptions.isEmpty()) {
            val baseOrder = baseEnum.options.map { it.name }
            val curOrder = curEnum.options.map { it.name }
            if (baseOrder != curOrder) {
                changes += ApiChange(ApiChangeCode.ENUM_REORDERED, name, "Enum $name options were reordered (same set): $baseOrder -> $curOrder.")
            }
        }
    }

    // --- Sealeds ---
    val baseSealeds = baseline.sealedStructures
    val curSealeds = current.sealedStructures
    for ((name, baseSealed) in baseSealeds) {
        val curSealed = curSealeds[name]
        val dir = direction(name)
        if (curSealed == null) {
            if (dir != Direction.NONE) changes += ApiChange(ApiChangeCode.TYPE_REMOVED, name, "Sealed type $name was removed.")
            continue
        }
        val baseSubtypes = baseSealed.options.map { it.type.serialName }.toSet()
        val curSubtypes = curSealed.options.map { it.type.serialName }.toSet()
        val removedSubtypes = baseSubtypes - curSubtypes
        if (removedSubtypes.isNotEmpty()) {
            changes += ApiChange(ApiChangeCode.SEALED_SUBTYPE_REMOVED, name, "Sealed type $name lost subtypes: ${removedSubtypes.sorted()}.")
        }
    }

    val withSuppression = changes.map { it.copy(suppressed = allowlist.suppresses(it)) }
    return ApiDiffReport(withSuppression.sortedWith(compareBy({ it.code.code }, { it.location })))
}

/**
 * Whether [current] is a wire-compatible replacement for [baseline] at the type-reference level.
 *
 * Compatible only when the serial name and generic arguments are identical. Nullability widening (non-null → nullable)
 * is handled separately as [ApiChangeCode.OUTPUT_FIELD_BECAME_NULLABLE]; a non-null → nullable change is considered
 * "the same type" here so it is not double-reported as a type change. Nullable → non-null on an input is breaking and
 * surfaces as a type change.
 */
private fun typeRefCompatible(baseline: VirtualTypeReference, current: VirtualTypeReference): Boolean {
    if (baseline.serialName != current.serialName) return false
    if (baseline.arguments.size != current.arguments.size) return false
    if (baseline.arguments.zip(current.arguments).any { (b, c) -> !typeRefCompatible(b, c) }) return false
    // Nullable -> non-nullable is a narrowing (breaking); report as type change.
    if (baseline.isNullable && !current.isNullable) return false
    return true
}
