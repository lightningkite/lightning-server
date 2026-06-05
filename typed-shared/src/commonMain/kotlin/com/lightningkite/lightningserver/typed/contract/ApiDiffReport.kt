package com.lightningkite.lightningserver.typed.contract

import kotlinx.serialization.Serializable

/**
 * The severity classification of a single detected API difference.
 */
@Serializable
public enum class ApiChangeSeverity {
    /** A change that can break existing clients (removed endpoint, removed output field, incompatible type change, etc.). */
    BREAKING,

    /**
     * A change that is breaking only under stricter assumptions (e.g. clients that rely on undocumented behavior).
     * Promoted to a failing change when the check runs in `strict` mode.
     */
    POTENTIALLY_BREAKING,

    /** A change that is safe for existing clients (added endpoint, added optional input field, doc changes, etc.). */
    NON_BREAKING,
}

/**
 * A stable taxonomy of every kind of API change [diffApiContract] can report.
 *
 * The string [code] is what appears in reports and in [ApiAllowlist] entries, so it must remain stable.
 */
@Serializable
public enum class ApiChangeCode(public val code: String, public val severity: ApiChangeSeverity) {
    ENDPOINT_REMOVED("endpoint-removed", ApiChangeSeverity.BREAKING),
    ENDPOINT_ADDED("endpoint-added", ApiChangeSeverity.NON_BREAKING),
    INPUT_REQUIRED_FIELD_ADDED("input-required-field-added", ApiChangeSeverity.BREAKING),
    INPUT_FIELD_BECAME_REQUIRED("input-field-became-required", ApiChangeSeverity.BREAKING),
    INPUT_OPTIONAL_FIELD_ADDED("input-optional-field-added", ApiChangeSeverity.NON_BREAKING),
    OUTPUT_FIELD_REMOVED("output-field-removed", ApiChangeSeverity.BREAKING),
    OUTPUT_FIELD_BECAME_NULLABLE("output-field-became-nullable", ApiChangeSeverity.BREAKING),
    OUTPUT_FIELD_ADDED("output-field-added", ApiChangeSeverity.NON_BREAKING),
    TYPE_CHANGED("type-changed", ApiChangeSeverity.BREAKING),
    OUTPUT_ENUM_WIDENED("output-enum-widened", ApiChangeSeverity.BREAKING),
    OUTPUT_ENUM_NARROWED("output-enum-narrowed", ApiChangeSeverity.NON_BREAKING),
    INPUT_ENUM_NARROWED("input-enum-narrowed", ApiChangeSeverity.BREAKING),
    INPUT_ENUM_WIDENED("input-enum-widened", ApiChangeSeverity.NON_BREAKING),

    /**
     * An enum's options were reordered without otherwise changing the set. Flagged because some serializers encode
     * enums by ordinal (declaration order) rather than by name, so a reorder can silently remap previously
     * persisted/serialized values. Reported as [POTENTIALLY_BREAKING][ApiChangeSeverity.POTENTIALLY_BREAKING] since
     * name-based encodings are unaffected.
     */
    ENUM_REORDERED("enum-reordered", ApiChangeSeverity.POTENTIALLY_BREAKING),
    AUTH_TIGHTENED("auth-tightened", ApiChangeSeverity.BREAKING),
    AUTH_LOOSENED("auth-loosened", ApiChangeSeverity.NON_BREAKING),
    SEALED_SUBTYPE_REMOVED("sealed-subtype-removed", ApiChangeSeverity.BREAKING),
    TYPE_REMOVED("type-removed", ApiChangeSeverity.BREAKING),
    ;

    public companion object {
        public fun byCode(code: String): ApiChangeCode? = entries.find { it.code == code }
    }
}

/**
 * A single detected difference between two schemas.
 *
 * @property code The taxonomy classification.
 * @property location A stable, human-readable pointer to where the change occurred (endpoint path/method, type field, etc.).
 *                    This is also what [ApiAllowlist] entries match against.
 * @property message A human-readable explanation of the change.
 * @property suppressed Whether this change was matched by the supplied [ApiAllowlist] and therefore should not fail the check.
 */
@Serializable
public data class ApiChange(
    val code: ApiChangeCode,
    val location: String,
    val message: String,
    val suppressed: Boolean = false,
) {
    val severity: ApiChangeSeverity get() = code.severity
}

/**
 * The full result of diffing a baseline schema against a current one.
 *
 * @property changes Every detected change, in stable order.
 */
@Serializable
public data class ApiDiffReport(
    val changes: List<ApiChange>,
) {
    /** Changes that are unconditionally breaking and not suppressed. */
    public val breaking: List<ApiChange>
        get() = changes.filter { it.severity == ApiChangeSeverity.BREAKING && !it.suppressed }

    /** Changes that are only breaking in strict mode and not suppressed. */
    public val potentiallyBreaking: List<ApiChange>
        get() = changes.filter { it.severity == ApiChangeSeverity.POTENTIALLY_BREAKING && !it.suppressed }

    /**
     * Whether this diff should fail a compatibility gate.
     *
     * @param strict When true, [POTENTIALLY_BREAKING][ApiChangeSeverity.POTENTIALLY_BREAKING] changes also fail.
     */
    public fun hasFailures(strict: Boolean = false): Boolean =
        breaking.isNotEmpty() || (strict && potentiallyBreaking.isNotEmpty())

    /** A grouped, printable summary of the report. */
    public fun render(strict: Boolean = false): String = buildString {
        if (changes.isEmpty()) {
            appendLine("API contract: no changes detected.")
            return@buildString
        }
        val groups = changes.groupBy { it.severity }
        for (severity in ApiChangeSeverity.entries) {
            val group = groups[severity] ?: continue
            appendLine("== ${severity.name} (${group.size}) ==")
            for (change in group.sortedWith(compareBy({ it.code.code }, { it.location }))) {
                val marker = if (change.suppressed) "[suppressed] " else ""
                appendLine("  $marker${change.code.code} @ ${change.location}: ${change.message}")
            }
        }
        val failures = hasFailures(strict)
        appendLine(if (failures) "RESULT: FAIL" else "RESULT: OK")
    }
}

/**
 * A committed allowlist of intentional breaking changes that should not fail a compatibility check.
 *
 * Each entry matches a detected [ApiChange] by its [ApiChangeCode.code] and [ApiChange.location]. Commit this file
 * alongside the baseline whenever you deliberately make a breaking change.
 *
 * @property entries The suppressions.
 */
@Serializable
public data class ApiAllowlist(
    val entries: List<Entry> = listOf(),
) {
    /**
     * One suppression rule.
     *
     * @property code The taxonomy code to suppress (see [ApiChangeCode.code]).
     * @property location The exact change location to suppress. If null, suppresses all changes with [code].
     */
    @Serializable
    public data class Entry(
        val code: String,
        val location: String? = null,
    )

    public fun suppresses(change: ApiChange): Boolean = entries.any {
        it.code == change.code.code && (it.location == null || it.location == change.location)
    }

    public companion object {
        public val EMPTY: ApiAllowlist = ApiAllowlist()

        /** JSON configuration for reading/writing committed allowlist files; matches [apiBaselineJson]. */
        public val json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}
