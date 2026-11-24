package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.ListRegistryExtension
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.getValue
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.instrument
import com.lightningkite.lightningserver.sessions.proofs.ProofMethodInfo
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.telemetry.telemetryAttributesOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * Represents something that happened while authenticating, delivered to every installed [AuthEventListener].
 *
 * [request] is the request the event arrived on, or `null` if there was none.
 */
public sealed interface AuthEvent {
    public val request: Request<*>?

    /**
     * Represents a refresh token that was rejected before it named a real session.
     *
     * A refresh token is unsigned, so anyone can produce these at will.
     *
     * For example, a client sends a garbled token, or a token naming a session id that was never issued.
     */
    public data class TokenRejected(val reason: Reason, override val request: Request<*>? = null) : AuthEvent {
        public enum class Reason {
            /** The token did not parse, or carried no usable session reference. */
            Malformed,

            /** The token's declared type does not match the principal it was presented to. */
            TypeMismatch,

            /** The token names a session that does not exist. */
            NoSuchSession,
        }
    }

    /**
     * Represents a refresh token that named the real session [sessionId] of [principal] and was rejected.
     *
     * For example, a user returns after a month and their session has expired, or they log in from a
     * device whose session they ended from another one.
     */
    public data class SessionRejected(
        val reason: Reason,
        val principal: String,
        val sessionId: Uuid,
        override val request: Request<*>? = null,
    ) : AuthEvent {
        public enum class Reason {
            /** The subject is not currently permitted to authenticate, such as when deactivated. */
            AuthenticationNotPermitted,

            /** The presented secret did not match the session's stored hash. */
            SecretMismatch,

            /** The session is past its hard expiry. */
            Expired,

            /** The session is past its sliding staleness window. */
            Stale,

            /** The session was explicitly terminated. */
            Terminated,

            /**
             * A rotating session's previous refresh token was presented after the grace following its rotation, so
             * someone else may hold a copy. The session is terminated.
             */
            Reused,

            /** A rotating session's refresh token was presented somewhere other than an endpoint that rotates it. */
            RotatingSession,
        }
    }

    /**
     * Represents a proof attempt that was turned away before any account's credential was checked.
     *
     * For example, someone tries a password for an email with no account, or keeps guessing after
     * the attempt limit is reached.
     */
    public data class ProofRejected(
        val method: ProofMethodInfo,
        val reason: Reason,
        override val request: Request<*>? = null,
    ) : AuthEvent {
        public enum class Reason {
            /** The attempt could not be read as identifying anyone. */
            MalformedRequest,

            /** The attempt limiter answered before the credential was looked at. */
            RateLimited,

            /** The identifying property and value matched no account. */
            NoSuchSubject,

            /** The attempt referred to a PIN or challenge that is not live: expired, spent, never issued, or not the one signed. */
            NoSuchChallenge,
        }
    }

    /**
     * Represents a credential presented for [principal] that was checked and rejected.
     *
     * For example, a user types the wrong password, or reuses a TOTP code they already logged in with.
     */
    public data class ProofCredentialRejected(
        val method: ProofMethodInfo,
        val reason: Reason,
        val principal: String,
        override val request: Request<*>? = null,
    ) : AuthEvent {
        public enum class Reason {
            /** The presented secret did not match. */
            SecretMismatch,

            /** The secret existed but is past its window or out of attempts. */
            SecretExpired,

            /** A single-use secret was presented a second time. */
            SecretAlreadyUsed,
        }
    }

    /**
     * Represents the session [sessionId] of [principal] being created.
     *
     * For example, a user logs in after proving their password.
     */
    public data class SessionCreated(
        val principal: String,
        val sessionId: Uuid,
        override val request: Request<*>? = null,
    ) : AuthEvent

    /**
     * Represents the session [sessionId] of [principal] being ended, by termination or deletion.
     *
     * For example, a user logs out, or ends their session on a lost phone from their laptop.
     */
    public data class SessionTerminated(
        val principal: String,
        val sessionId: Uuid,
        override val request: Request<*>? = null,
    ) : AuthEvent

    /**
     * Represents a credential presented to a proof endpoint for [principal] that was accepted.
     *
     * For a method that proves ownership of an address, such as an emailed PIN, [principal] is that
     * address, since no account is resolved until login.
     *
     * For example, a user enters the correct password, or the PIN emailed to them.
     */
    public data class ProofAccepted(
        val method: ProofMethodInfo,
        val principal: String,
        override val request: Request<*>? = null,
    ) : AuthEvent

    /**
     * Represents a proof that was minted and sent to [principal] without anyone presenting a credential, as with a magic link.
     *
     * Unlike [ProofAccepted], nothing was proven, but whoever receives the proof can authenticate.
     *
     * For example, a frontend asks the server to email a user a login link.
     */
    public data class ProofIssued(
        val method: ProofMethodInfo,
        val principal: String,
        override val request: Request<*>? = null,
    ) : AuthEvent

    /**
     * Represents a change to the credentials the subject [principal] (of principal type [principalType]) can log in with by [method].
     *
     * Raised after the change is written, by the password, TOTP, WebAuthN, known device and backup code endpoints, however
     * it was made: their own endpoints, their REST endpoints, or app code writing through their `modelInfo`.
     *
     * Nothing tells the user unless the app installs an [AuthEventListener] that does, which it should: an unexpected
     * change is how a user finds out someone else is in their account.  As with every [AuthEvent], a listener that
     * throws is logged and the change stands.
     *
     * Email addresses and phone numbers are properties of the app's own subject model, so changes to them are not
     * reported here; watch them with that model's signals.
     *
     * [request] is `null` for every change but a backup code reset or clear, since the others are observed at the database.
     *
     * For example, a user sets a new password, or revokes a passkey.
     */
    public data class FactorChanged(
        val method: ProofMethodInfo,
        val principalType: String,
        val principal: String,
        val change: Change,
        override val request: Request<*>? = null,
    ) : AuthEvent {
        public enum class Change {
            /** The subject gained a credential of this method. */
            Added,

            /** The subject's single credential of this method was swapped for a new one, such as a password change. */
            Replaced,

            /** A credential of this method was revoked.  Expiry is not reported. */
            Removed,
        }
    }
}

/** Receives each [AuthEvent] once installed with [install]. */
public interface AuthEventListener {
    /** Returns the name of this listener, used for instrumentation and debugging. */
    public val name: String get() = this::class.simpleName ?: "anonymous"

    /**
     * Handles the given [event].
     *
     * An exception thrown here is logged and does not reach the authentication path that raised the event.
     */
    context(runtime: ServerRuntime)
    public suspend fun onEvent(event: AuthEvent)
}

private object AuthEventListeners : ListRegistryExtension<AuthEventListener>

private val ServerDefinition.authEventListeners: List<AuthEventListener> by AuthEventListeners

/** Installs [listener] so that it receives every [AuthEvent] raised on this server, and returns [listener]. */
public fun <T : AuthEventListener> ServerBuilder.install(listener: T): T {
    extensions[AuthEventListeners].register(listener)
    return listener
}

/**
 * Delivers [event] to every installed [AuthEventListener].
 *
 * An exception thrown by a listener is logged and does not stop delivery to the remaining listeners.
 */
public suspend fun ServerRuntime.reportAuthEvent(event: AuthEvent) {
    for (listener in server.authEventListeners) {
        // Claude: events come from paths already rejecting a login; a listener failure must not replace that answer.
        try {
            instrument(listener.name) { listener.onEvent(event) }
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            server.logger.error(e) { "Auth event listener ${listener.name} failed on $event" }
        }
    }
}
