package com.lightningkite.lightningserver.runtime

/**
 * Receives authentication events so that something can record them.
 *
 * The seam lives here, in `core`, rather than in the module that raises the events or the one that
 * records them, because those two do not depend on each other and should not have to. `sessions`
 * raises; an audit module installs a reporter that writes. A deployment that installs none pays a
 * null check.
 *
 * ## Why authentication needs its own seam at all
 * The other audit layers attach to something the framework already routes through — a typed output,
 * a table, an execution. An authentication *failure* touches none of those: the login endpoints are
 * `noAuth` and throw before any authentication resolves, so the access log structurally cannot see
 * them, and nothing is written to a table. Without a seam the event has nowhere to be observed.
 * See `plans/audit-logging.md` 7.2.
 *
 * ## Contract
 * Implementations must not throw. Unlike the disclosure log, an auth event is reported from paths
 * that are already failing — rejecting a login, ending a session — and turning a recording failure
 * into a second, different failure there would obscure the original. A reporter that cannot write
 * should say so through its own logging and return. This is a deliberate departure from the
 * fail-closed rule the disclosure and data access logs follow, and the reason is that those two
 * gate *disclosure*, which must not happen unrecorded, while this one observes events that have
 * already happened and cannot be un-happened by throwing.
 */
public interface AuthEventReporter {
    /** Identifies this reporter for instrumentation. */
    public val name: String get() = this::class.simpleName ?: "anonymous"

    /**
     * Records one authentication event.
     *
     * @param type A value from the recorder's own vocabulary, passed as a string so that `core` does
     *   not have to own the taxonomy. The audit module maps these onto its record type.
     * @param principal The subject the event is about, or null when the attempt failed before one
     *   was resolved.
     * @param actor The principal that caused the event when it differs from [principal].
     * @param detail Free text for the reason, where there is one.
     */
    context(runtime: ServerRuntime)
    public suspend fun report(
        type: String,
        principal: String? = null,
        actor: String? = null,
        sessionId: String? = null,
        detail: String? = null,
    )
}
