package com.lightningkite.lightningserver.typed.jsonrpc

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.HttpAccess
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.serializerOrContextual
import kotlinx.serialization.KSerializer

/**
 * Creates a JSON-RPC method with explicit serializers.
 *
 * Use this when you need to provide custom serializers or when reified type parameters aren't available.
 * For most cases, prefer the reified [JsonRpcMethod] function.
 *
 * @param name The method name (matched against "method" field in JSON-RPC requests)
 * @param description Human-readable description of what this method does
 * @param inputType Serializer for the input parameter type
 * @param outputType Serializer for the output result type
 * @param auth Authentication requirements for this method
 * @param implementation Business logic handler receiving authenticated access and parsed input
 * @return A configured JSON-RPC method
 */
public fun <PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT> explicitJsonRpcMethod(
    name: String,
    description: String = "",
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    auth: AuthRequirement<USER>,
    implementation: suspend context(ServerRuntime) HttpAccess<PATH, USER>.(INPUT) -> OUTPUT,
): JsonRpcMethod<PATH, USER, INPUT, OUTPUT> =
    JsonRpcMethodData(name, description, auth, inputType, outputType, implementation)

/**
 * Creates a JSON-RPC method with reified type parameters for automatic serializer resolution.
 *
 * This is the recommended way to create JSON-RPC methods. Serializers are automatically
 * resolved from the type parameters.
 *
 * Example:
 * ```kotlin
 * val addMethod = JsonRpcMethod<_, User?, AddParams, Int>(
 *     name = "add",
 *     description = "Adds two numbers",
 *     auth = authOptions<User>(),
 *     implementation = { params ->
 *         params.a + params.b
 *     }
 * )
 * ```
 *
 * @param PATH The path specification type
 * @param USER The authenticated user type (or null for unauthenticated methods)
 * @param INPUT Request input parameter type (must be serializable)
 * @param OUTPUT Response output result type (must be serializable)
 * @param name The method name
 * @param description Method description
 * @param auth Authentication requirements
 * @param implementation Business logic handler
 * @return A configured JSON-RPC method
 */
public inline fun <PATH : PathSpec, USER : HasId<*>?, reified INPUT, reified OUTPUT> JsonRpcMethod(
    name: String,
    description: String = "",
    auth: AuthRequirement<USER>,
    noinline implementation: suspend context(ServerRuntime) HttpAccess<PATH, USER>.(INPUT) -> OUTPUT,
): JsonRpcMethod<PATH, USER, INPUT, OUTPUT> =
    explicitJsonRpcMethod(
        name = name,
        description = description,
        inputType = serializerOrContextual<INPUT>(),
        outputType = serializerOrContextual<OUTPUT>(),
        auth = auth,
        implementation = implementation
    )
