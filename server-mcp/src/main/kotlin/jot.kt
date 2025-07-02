package com.lightningkite.lightningserver.mcp

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.handler
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.jsonrpc.JsonRpcEndpoint
import com.lightningkite.lightningserver.jsonschema.JsonSchemaType
import com.lightningkite.lightningserver.jsonschema.singleType
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.AuthAccessor
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class McpServer<USER : HasId<*>?>(
    val name: String,
    val version: String,
) {
    val serverInfo: Implementation = Implementation(name, version)
    inner class Tool< I, O>(
        val name: String,
        val title: String?,
        val description: String?,
        val input: KSerializer<I>,
        val output: KSerializer<O>,
        val implementation: suspend AuthAccessor<USER>.(I)->O
    )
    inner class Resource(
        val uri: String,
        val name: String,
        val description: String?,
        val mimeType: ContentType?,
        public val contents: suspend AuthAccessor<USER>.()->List<ResourceContents>,
    )
    inner class Prompt(
        public val name: String,
        public val description: String?,
        public val arguments: List<PromptArgument>?,
        public val contents: suspend AuthAccessor<USER>.(Map<String, String>)-> GetPromptResult,
    )
    val tools = HashMap<String, Tool<*, *>>()
    val resources = HashMap<String, Resource>()
    val prompts = HashMap<String, Prompt>()
}

fun <USER : HasId<*>?> JsonRpcEndpoint<USER>.mcp(server: McpServer<USER>) {
    registerMethod("initialize") { _: InitializeRequest ->
        InitializeResult(
            serverInfo = server.serverInfo,
            capabilities = ServerCapabilities(
                prompts = if(server.prompts.isNotEmpty()) ServerCapabilities.Prompts(false) else null,
                tools = if(server.tools.isNotEmpty()) ServerCapabilities.Tools(false) else null,
                resources = if(server.resources.isNotEmpty()) ServerCapabilities.Resources(false, false) else null,
            )
        )
    }
    registerMethod("ping") { _: Unit -> Unit }
    registerMethod("resources/list") { _: ListResourcesRequest ->
        ListResourcesResult(
            resources = server.resources.values.map { Resource(it.uri, it.name, it.description, it.mimeType?.toString()) }
        )
    }
    registerMethod("resources/read") { it: ReadResourceRequest ->
        server.resources[it.uri]?.let {
            ReadResourceResult(
                contents = it.contents(this)
            )
        } ?: throw NotFoundException()
    }
    registerMethod("prompts/list") { it: ListPromptsRequest ->
        ListPromptsResult(
            prompts = server.prompts.values.map { Prompt(it.name, it.description, it.arguments) }
        )
    }
    registerMethod("prompts/get") { it: GetPromptRequest ->
        server.prompts[it.name]?.let { p ->
            p.contents(this, it.arguments ?: mapOf())
        } ?: throw NotFoundException()
    }
    registerMethod("tools/list") { it: ListToolsRequest ->
        AltListToolsResult(
            tools = server.tools.values.map {
                AltTool(it.name, it.title, it.description, Serialization.json.singleType(it.input), Serialization.json.singleType(it.output))
            }
        )
    }
    registerMethod("tools/call") { it: CallToolRequest ->
        server.tools[it.name]?.let { p ->
            val input = Serialization.json.decodeFromJsonElement(p.input, it.arguments) as Any?
            val output = (p.implementation as suspend AuthAccessor<USER>.(Any?)->Any?).invoke(this, input)
            CallToolResult(
                listOf(TextContent(Serialization.json.encodeToString(p.output as KSerializer<Any?>, output)))
            )
        } ?: throw NotFoundException()

    }
//    registerMethod("roots/list") { it: ListRootsRequest ->
//        ListRootsResult()
//    }
//    registerMethod("resources/templates/list") { it: ListResourceTemplatesRequest ->
//        ListResourceTemplatesResult()
//    }
//    registerMethod("resources/subscribe") { it: SubscribeRequest ->
//        Unit
//    }
//    registerMethod("resources/unsubscribe") { it: UnsubscribeRequest ->
//        Unit
//    }
//    registerMethod("notifications/cancelled") { it: NotificationsCancelledRequest ->
//        NotificationsCancelledResult()
//    }
//    registerMethod("notifications/initialized") { it: NotificationsInitializedRequest ->
//        NotificationsInitializedResult()
//    }
//    registerMethod("notifications/progress") { it: NotificationsProgressRequest ->
//        NotificationsProgressResult()
//    }
//    registerMethod("notifications/message") { it: NotificationsMessageRequest ->
//        NotificationsMessageResult()
//    }
//    registerMethod("notifications/resources/updated") { it: NotificationsResourcesUpdatedRequest ->
//        NotificationsResourcesUpdatedResult()
//    }
//    registerMethod("notifications/resources/list_changed") { it: NotificationsResourcesListChangedRequest ->
//        NotificationsResourcesListChangedResult()
//    }
//    registerMethod("notifications/tools/list_changed") { it: NotificationsToolsListChangedRequest ->
//        NotificationsToolsListChangedResult()
//    }
//    registerMethod("notifications/roots/list_changed") { it: NotificationsRootsListChangedRequest ->
//        NotificationsRootsListChangedResult()
//    }
//    registerMethod("notifications/prompts/list_changed") { it: NotificationsPromptsListChangedRequest ->
//        NotificationsPromptsListChangedResult()
//    }
//    registerMethod("logging/setLevel") { it: LoggingSetLevelRequest ->
//        LoggingSetLevelResult()
//    }
//    registerMethod("sampling/createMessage") { it: SamplingCreateMessageRequest ->
//        SamplingCreateMessageResult()
//    }
//    registerMethod("completion/complete") { it: CompletionCompleteRequest ->
//        CompletionCompleteResult()
//    }
}

/**
 * Definition for a tool the client can call.
 */
@Serializable
public data class AltTool(
    /**
     * The name of the tool.
     */
    val name: String,
    /**
     * A human-readable description of the tool.
     */
    val title: String?,
    /**
     * A human-readable description of the tool.
     */
    val description: String?,
    /**
     * A JSON object defining the expected parameters for the tool.
     */
    val inputSchema: JsonSchemaType,
    /**
     * A JSON object defining the expected parameters for the tool.
     */
    val outputSchema: JsonSchemaType,
) {
}

/**
 * The server's response to a tools/list request from the client.
 */
@Serializable
public class AltListToolsResult(
    public val tools: List<AltTool>,
    val nextCursor: Cursor? = null,
    val _meta: JsonObject = buildJsonObject {  },
)