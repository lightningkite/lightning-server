package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.rpc.jsonRpc
import kotlinx.serialization.Serializable

/**
 * Example showing how to use JSON-RPC with Lightning Server.
 *
 * The key benefit is that handlers can be shared between REST and RPC endpoints,
 * eliminating code duplication.
 */
object RpcExample : ServerBuilder() {

    // Example request/response types
    @Serializable
    data class EchoRequest(val message: String)

    @Serializable
    data class EchoResponse(val echo: String)

    @Serializable
    data class AddRequest(val a: Int, val b: Int)

    @Serializable
    data class AddResponse(val result: Int)

    @Serializable
    data class ListRequest(val limit: Int = 10)

    // Define handlers once - they can be used for both REST and RPC
    val echoHandler = ApiHttpHandler<PathSpec0, Nothing?, EchoRequest, EchoResponse>(
        summary = "Echo",
        description = "Echoes back the provided message",
        auth = noAuth,
        errorCases = listOf(),
        implementation = { request: EchoRequest ->
            EchoResponse(echo = request.message)
        }
    )

    val addHandler = ApiHttpHandler<PathSpec0, Nothing?, AddRequest, AddResponse>(
        summary = "Add Numbers",
        description = "Adds two numbers together",
        auth = noAuth,
        errorCases = listOf(),
        implementation = { request: AddRequest ->
            AddResponse(result = request.a + request.b)
        }
    )

    val listModelsHandler = ApiHttpHandler<PathSpec0, Nothing?, ListRequest, List<TestModel>>(
        summary = "List Test Models",
        description = "Get test models with limit",
        auth = noAuth,
        errorCases = listOf(),
        implementation = { request: ListRequest ->
            // In a real app, this would query the database
            // For demo purposes, return sample data
            List(request.limit.coerceAtMost(5)) { index ->
                TestModel(
                    name = "Model ${index + 1}",
                    number = index + 1
                )
            }
        }
    )

    // REST endpoints - traditional HTTP REST style
    val restApi = path.path("api") include object : ServerBuilder() {
        val echo = path.path("echo").post bind echoHandler
        val add = path.path("add").post bind addHandler
        val models = path.path("models").get bind listModelsHandler
    }

    // JSON-RPC endpoint - single endpoint for multiple methods
    // Same handlers as REST! No duplication!
    val rpc = path.path("rpc").post bind jsonRpc<PathSpec0> {
        method("echo", echoHandler)
        method("math.add", addHandler)
        method("models.list", listModelsHandler)
    }

    // Documentation endpoint
    val docs = path.path("rpc-docs").get bind HttpHandler {
        HttpResponse.html("""
            <!DOCTYPE html>
            <html>
            <head>
                <title>JSON-RPC Example</title>
                <style>
                    body { font-family: sans-serif; max-width: 1000px; margin: 50px auto; padding: 20px; }
                    h1 { color: #333; }
                    h2 { color: #666; margin-top: 30px; }
                    pre { background: #f4f4f4; padding: 15px; border-radius: 5px; overflow-x: auto; }
                    code { background: #f4f4f4; padding: 2px 5px; border-radius: 3px; }
                    .example { margin: 20px 0; }
                </style>
            </head>
            <body>
                <h1>JSON-RPC Example</h1>

                <p>This demonstrates how Lightning Server supports JSON-RPC 2.0 with zero code duplication.</p>

                <h2>Key Benefits</h2>
                <ul>
                    <li><strong>Shared Handlers</strong> - Same <code>ApiHttpHandler</code> serves both REST and RPC</li>
                    <li><strong>Type Safety</strong> - Full compile-time type checking</li>
                    <li><strong>Unified Auth</strong> - Same authentication for both protocols</li>
                    <li><strong>Standard JSON</strong> - Uses existing JSON serialization</li>
                </ul>

                <h2>Available Methods</h2>

                <div class="example">
                    <h3>echo</h3>
                    <p>Echoes back your message</p>
                    <pre>POST /rpc
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "method": "echo",
  "params": {"message": "Hello, World!"},
  "id": 1
}

Response:
{
  "jsonrpc": "2.0",
  "result": {"echo": "Hello, World!"},
  "id": 1
}</pre>
                </div>

                <div class="example">
                    <h3>math.add</h3>
                    <p>Adds two numbers</p>
                    <pre>POST /rpc
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "method": "math.add",
  "params": {"a": 5, "b": 3},
  "id": 2
}

Response:
{
  "jsonrpc": "2.0",
  "result": {"result": 8},
  "id": 2
}</pre>
                </div>

                <div class="example">
                    <h3>models.list</h3>
                    <p>Lists test models</p>
                    <pre>POST /rpc
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "method": "models.list",
  "params": {"limit": 3},
  "id": 3
}

Response:
{
  "jsonrpc": "2.0",
  "result": [
    {"_id": "...", "name": "Model 1", "number": 1, ...},
    {"_id": "...", "name": "Model 2", "number": 2, ...},
    {"_id": "...", "name": "Model 3", "number": 3, ...}
  ],
  "id": 3
}</pre>
                </div>

                <h2>Batch Requests</h2>
                <p>JSON-RPC supports sending multiple requests in a single HTTP call:</p>
                <pre>POST /rpc
Content-Type: application/json

[
  {"jsonrpc": "2.0", "method": "echo", "params": {"message": "First"}, "id": 1},
  {"jsonrpc": "2.0", "method": "math.add", "params": {"a": 10, "b": 20}, "id": 2}
]

Response:
[
  {"jsonrpc": "2.0", "result": {"echo": "First"}, "id": 1},
  {"jsonrpc": "2.0", "result": {"result": 30}, "id": 2}
]</pre>

                <h2>REST Equivalents</h2>
                <p>The same handlers are also available via REST endpoints:</p>
                <ul>
                    <li><code>POST /api/echo</code> - Echo handler</li>
                    <li><code>POST /api/add</code> - Add handler</li>
                    <li><code>GET /api/models?limit=10</code> - List models handler</li>
                </ul>

                <h2>Error Handling</h2>
                <p>Errors are automatically mapped from HTTP exceptions to JSON-RPC error codes:</p>
                <pre>{
  "jsonrpc": "2.0",
  "error": {
    "code": -32602,
    "message": "Invalid params",
    "data": {"detail": "validation", "data": "limit must be positive"}
  },
  "id": 1
}</pre>

                <h2>Try It!</h2>
                <p>Use curl or your favorite HTTP client:</p>
                <pre>curl -X POST http://localhost:8080/rpc \\
  -H "Content-Type: application/json" \\
  -d '{"jsonrpc":"2.0","method":"echo","params":{"message":"Hello"},"id":1}'</pre>
            </body>
            </html>
        """.trimIndent())
    }
}
