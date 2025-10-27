package com.lightningkite.lightningserver.typed.rpc

import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.services.database.HasId

/**
 * Builder for registering JSON-RPC methods.
 *
 * Example usage:
 * ```
 * val rpc = path.path("rpc").post bind jsonRpc {
 *     method("models.list", listModelsHandler)
 *     method("models.get", getModelHandler)
 *     method("models.create", createModelHandler)
 * }
 * ```
 */
public class JsonRpcEndpoints<PATH : PathSpec> {
    private val methods = mutableMapOf<String, ApiHttpHandler<PATH, *, *, *>>()

    /**
     * Register a JSON-RPC method by name.
     *
     * @param name The method name (e.g., "models.list", "users.create")
     * @param handler The existing ApiHttpHandler to handle this method
     */
    public fun <USER : HasId<*>?, IN, OUT> method(
        name: String,
        handler: ApiHttpHandler<PATH, USER, IN, OUT>
    ) {
        methods[name] = handler
    }

    internal fun build(): JsonRpcHandler<PATH> {
        return JsonRpcHandler(methods.toMap())
    }
}

/**
 * Creates a JSON-RPC handler with the given method registrations.
 *
 * Example:
 * ```
 * object MyServer : ServerBuilder() {
 *     val rpc = path.path("rpc").post bind jsonRpc {
 *         method("models.list", listModelsHandler)
 *         method("models.get", getModelHandler)
 *     }
 * }
 * ```
 *
 * This allows the same ApiHttpHandler to be used for both REST and RPC:
 * ```
 * val listHandler = ApiHttpHandler(...) { ... }
 *
 * // REST
 * val rest = path.path("api").path("models").get bind listHandler
 *
 * // RPC (same handler!)
 * val rpc = path.path("rpc").post bind jsonRpc {
 *     method("models.list", listHandler)
 * }
 * ```
 */
public fun <PATH : PathSpec> jsonRpc(
    configure: JsonRpcEndpoints<PATH>.() -> Unit
): JsonRpcHandler<PATH> {
    val endpoints = JsonRpcEndpoints<PATH>()
    endpoints.configure()
    return endpoints.build()
}
