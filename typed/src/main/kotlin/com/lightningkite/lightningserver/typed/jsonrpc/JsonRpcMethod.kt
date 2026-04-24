package com.lightningkite.lightningserver.typed.jsonrpc

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.HttpAccess
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer

/**
 * Represents a single JSON-RPC method that can be invoked.
 *
 * Each method has a name, input/output types, authentication requirements,
 * and an implementation that handles the actual business logic.
 *
 * @param PATH The path specification type defining URL parameters
 * @param USER The authenticated user type (or null for unauthenticated methods)
 * @param INPUT The method input parameter type
 * @param OUTPUT The method output result type
 */
public interface JsonRpcMethod<PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT> {
    /**
     * The name of this RPC method. This is matched against the "method" field in JSON-RPC requests.
     */
    public val name: String

    /**
     * Optional description of what this method does (for documentation).
     */
    public val description: String

    /**
     * Authentication requirements for this method.
     */
    public val auth: AuthRequirement<USER>

    /**
     * Serializer for the input parameter type.
     */
    public val inputType: KSerializer<INPUT>

    /**
     * Serializer for the output result type.
     */
    public val outputType: KSerializer<OUTPUT>

    /**
     * Handles the method invocation with typed input and output.
     *
     * @param access Authenticated access object with request context and user
     * @param input Parsed and validated input parameters
     * @return Method result to be serialized
     */
    context(server: ServerRuntime)
    public suspend fun handle(access: HttpAccess<PATH, USER>, input: INPUT): OUTPUT

    /**
     * Handles the method invocation with typed input and output.
     *
     * @param access Authenticated access object with request context and user
     * @param input Parsed and validated input parameters
     * @return Method result to be serialized
     */
    context(server: ServerRuntime)
    public suspend fun handleWithCustomHeaders(
        access: HttpAccess<PATH, USER>,
        input: INPUT,
    ): Pair<OUTPUT, HttpHeaders> = handle(access, input) to HttpHeaders.EMPTY
}

/**
 * Internal implementation of [JsonRpcMethod].
 */
internal data class JsonRpcMethodData<PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT>(
    override val name: String,
    override val description: String,
    override val auth: AuthRequirement<USER>,
    override val inputType: KSerializer<INPUT>,
    override val outputType: KSerializer<OUTPUT>,
    val implementation: suspend context(ServerRuntime) HttpAccess<PATH, USER>.(INPUT) -> OUTPUT,
) : JsonRpcMethod<PATH, USER, INPUT, OUTPUT> {
    context(server: ServerRuntime)
    override suspend fun handle(access: HttpAccess<PATH, USER>, input: INPUT): OUTPUT =
        access.implementation(input)
}
