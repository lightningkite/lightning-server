package com.lightningkite.lightningserver.demo.endpoints

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.typed.jsonrpc.JsonRpcHandler
import com.lightningkite.lightningserver.typed.jsonrpc.JsonRpcMethod
import kotlinx.serialization.Serializable

/**
 * JSON-RPC endpoint examples demonstrating how to create RPC-style APIs.
 *
 * JSON-RPC is a remote procedure call protocol encoded in JSON. It's useful for:
 * - Creating RPC-style APIs that feel more like function calls
 * - Batch operations and notifications
 * - APIs that don't fit well with REST semantics
 *
 * This example creates a single endpoint at `/api/rpc` that routes to multiple RPC methods.
 */
object JsonRpcExamplesEndpoints : ServerBuilder() {

    // Input/Output data classes for our RPC methods

    @Serializable
    data class MathOperationParams(
        val a: Double,
        val b: Double
    )

    @Serializable
    data class EchoParams(
        val message: String,
        val times: Int = 1
    )

    @Serializable
    data class GetTimeResult(
        val timestamp: Long,
        val iso: String
    )

    // Define individual RPC methods

    /**
     * Adds two numbers together.
     *
     * Example request:
     * ```json
     * {
     *   "jsonrpc": "2.0",
     *   "method": "math.add",
     *   "params": {"a": 5, "b": 3},
     *   "id": 1
     * }
     * ```
     *
     * Example response:
     * ```json
     * {
     *   "jsonrpc": "2.0",
     *   "result": 8.0,
     *   "id": 1
     * }
     * ```
     */
    val addMethod = JsonRpcMethod<PathSpec0, Nothing?, MathOperationParams, Double>(
        name = "math.add",
        description = "Adds two numbers",
        auth = noAuth,
        implementation = { params ->
            params.a + params.b
        }
    )

    /**
     * Subtracts the second number from the first.
     */
    val subtractMethod = JsonRpcMethod<PathSpec0, Nothing?, MathOperationParams, Double>(
        name = "math.subtract",
        description = "Subtracts b from a",
        auth = noAuth,
        implementation = { params ->
            params.a - params.b
        }
    )

    /**
     * Multiplies two numbers.
     */
    val multiplyMethod = JsonRpcMethod<PathSpec0, Nothing?, MathOperationParams, Double>(
        name = "math.multiply",
        description = "Multiplies two numbers",
        auth = noAuth,
        implementation = { params ->
            params.a * params.b
        }
    )

    /**
     * Divides the first number by the second.
     */
    val divideMethod = JsonRpcMethod<PathSpec0, Nothing?, MathOperationParams, Double>(
        name = "math.divide",
        description = "Divides a by b",
        auth = noAuth,
        implementation = { params ->
            require(params.b != 0.0) { "Cannot divide by zero" }
            params.a / params.b
        }
    )

    /**
     * Echoes a message multiple times.
     *
     * Example request:
     * ```json
     * {
     *   "jsonrpc": "2.0",
     *   "method": "echo",
     *   "params": {"message": "Hello", "times": 3},
     *   "id": 2
     * }
     * ```
     */
    val echoMethod = JsonRpcMethod<PathSpec0, Nothing?, EchoParams, String>(
        name = "echo",
        description = "Echoes a message multiple times",
        auth = noAuth,
        implementation = { params ->
            (1..params.times).joinToString(" ") { params.message }
        }
    )

    /**
     * Gets the current server time.
     *
     * Example request:
     * ```json
     * {
     *   "jsonrpc": "2.0",
     *   "method": "getTime",
     *   "params": null,
     *   "id": 3
     * }
     * ```
     */
    val getTimeMethod = JsonRpcMethod<PathSpec0, Nothing?, Unit, GetTimeResult>(
        name = "getTime",
        description = "Gets the current server time",
        auth = noAuth,
        implementation = { _ ->
            val now = kotlin.time.Clock.System.now()
            GetTimeResult(
                timestamp = now.toEpochMilliseconds(),
                iso = now.toString()
            )
        }
    )

    /**
     * Returns a greeting for the given name.
     */
    val greetMethod = JsonRpcMethod<PathSpec0, Nothing?, String, String>(
        name = "greet",
        description = "Returns a greeting",
        auth = noAuth,
        implementation = { name ->
            "Hello, $name!"
        }
    )

    /**
     * The main JSON-RPC endpoint that routes to all the methods defined above.
     *
     * All RPC methods are accessed through this single POST endpoint at `/api/rpc`.
     * The "method" field in the JSON-RPC request determines which method is called.
     *
     * Try it with curl:
     * ```bash
     * curl -X POST http://localhost:8080/api/rpc \
     *   -H "Content-Type: application/json" \
     *   -d '{"jsonrpc":"2.0","method":"math.add","params":{"a":5,"b":3},"id":1}'
     * ```
     */
    val rpcEndpoint = path.path("api").path("rpc").post bind JsonRpcHandler(
        methods = listOf(
            addMethod,
            subtractMethod,
            multiplyMethod,
            divideMethod,
            echoMethod,
            getTimeMethod,
            greetMethod
        )
    )
}
