package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.data.toSealedList

/**
 * Receives authentication events so that something can record them.
 *
 * The seam lives in `auth` because that is the nearest module both sides already depend on:
 * `sessions` raises the events and depends on `auth` directly, while an audit module installs a
 * reporter and reaches `auth` through `typed`. The two never learn about each other, and a
 * deployment that installs no reporter pays an empty list.
 *
 * It is registered through [MutableExtensions.WritableKey], the same facility `typed` uses for its
 * table registry, so `core` carries nothing about authentication — including the vocabulary. That
 * matters: an earlier version put this interface in `core`, which could not name its own event
 * types and had to take them as free text, so a typo silently dropped the event.
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
     * @param type What happened. An enum rather than free text, which is what moving this out of
     *   `core` bought: an unrecognised value is now a compile error rather than a dropped event.
     * @param principal The subject the event is about, or null when the attempt failed before one
     *   was resolved.
     * @param actor The principal that caused the event when it differs from [principal].
     * @param sessionId The session the event concerns, where there is one.
     * @param sourceIp Where the attempt came from, when it was actually observed. Pass null rather
     *   than a placeholder: a fabricated origin reads as a real one to whoever queries the log.
     * @param userAgent As [sourceIp].
     * @param detail Free text for the reason, where there is one.
     * @param method How the attempt was made, where the event came from a particular mechanism — a
     *   proof method's `via`, for instance. A string for the same reason [type] is: the taxonomy
     *   belongs to whoever raises the event, not here. Null on paths that have no such distinction,
     *   such as a refresh token exchange.
     * @param methodProperty The property [method] was applied to, where it has one — the `email` in
     *   an emailed PIN. Null for methods that identify the subject directly.
     */
    context(runtime: ServerRuntime)
    public suspend fun report(
        type: AuthEventType,
        principal: String? = null,
        actor: String? = null,
        sessionId: String? = null,
        sourceIp: String? = null,
        userAgent: String? = null,
        detail: String? = null,
        method: String? = null,
        methodProperty: String? = null,
    )
}

private object AuthEventReporters : ListRegistryExtension<AuthEventReporter>

/**
 * Every auth event reporter installed on this server. Empty when nothing records them, which is the
 * normal case for a deployment that does not audit.
 */
public val ServerDefinition.authEventReporters: List<AuthEventReporter> by AuthEventReporters

/**
 * Installs [reporter] so that everything raising auth events reaches it.
 */
public fun <T : AuthEventReporter> ServerBuilder.installAuthEventReporter(reporter: T): T {
    extensions[AuthEventReporters].register(reporter)
    return reporter
}
