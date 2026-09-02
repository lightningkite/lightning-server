package com.lightningkite.lightningserver.audit

/**
 * Derives the erasure subject for an audited model — whose data a record is about.
 *
 * An immutable audit log and a right-to-erasure request are in direct conflict. The resolution is
 * crypto-shredding: encrypt each subject's records under a per-subject key, then destroy the key when
 * erasure is required. That needs to know **which subject a record belongs to**, and the framework
 * cannot determine that. It is domain knowledge, and inferring it would be guesswork — the subject of
 * a medical record is not reliably its `_id`, nor any field the framework can name.
 *
 * So the framework provides the mechanism and the application supplies the policy.
 *
 * ```kotlin
 * val audit = path.path("audit") include DisclosureAudit(
 *     database = database,
 *     subjectKeys = mapOf(
 *         auditSubjectKey(Patient.serializer()) { it.subjectId },
 *     ),
 * )
 * ```
 *
 * ## Absent by default, and that is correct for US deployments
 * With no key registered there is no per-subject wrapping and no crypto-shredding. The US regime this
 * system targets first does not require erasure; a deployment that needs it registers a key per
 * audited model and accepts the added key management.
 *
 * ## This must be decided before the first record is written
 * The choice determines how records are encrypted at rest, so it **cannot be retrofitted** — that is
 * the whole point of crypto-shredding. Registering a key later does not make earlier records
 * shreddable; they were written unwrapped and stay that way. See `plans/audit-logging.md` 11.2.
 *
 * @param T The audited model this derives a subject for.
 */
public fun interface AuditSubjectKey<T> {
    /**
     * The erasure subject of [model], or null when this record is not subject-scoped.
     *
     * Null means "no natural person's erasure request covers this row" — an audit record about a
     * system action, say. Such a row is never wrapped and never shredded.
     */
    public fun subject(model: T): String?
}

/**
 * Builds a [subjectKeys][com.lightningkite.lightningserver.audit] entry, tying the key to the model
 * it derives from.
 *
 * The map itself is `Map<String, AuditSubjectKey<*>>`, which cannot express that the key's type
 * matches its serial name — so pairing them by hand lets `Patient`'s entry hold a `Doctor` key and
 * compile. This does not: the serializer supplies both halves.
 */
public fun <T> auditSubjectKey(
    serializer: kotlinx.serialization.KSerializer<T>,
    key: AuditSubjectKey<T>,
): Pair<String, AuditSubjectKey<*>> =
    // Same normalisation the registry uses: a nullable descriptor's serial name carries a trailing
    // "?", and keying on the raw name would silently miss the model it refers to.
    serializer.descriptor.serialName.removeSuffix("?") to key
