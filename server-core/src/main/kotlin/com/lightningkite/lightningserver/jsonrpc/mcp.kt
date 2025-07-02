package com.lightningkite.lightningserver.jsonrpc

import com.lightningkite.lightningdb.HasId
import io.modelcontextprotocol.kotlin.sdk.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.collections.contains


//    Initialize("initialize"),
//    Ping("ping"),
//    ResourcesList("resources/list"),
//    ResourcesTemplatesList("resources/templates/list"),
//    ResourcesRead("resources/read"),
//    ResourcesSubscribe("resources/subscribe"),
//    ResourcesUnsubscribe("resources/unsubscribe"),
//    PromptsList("prompts/list"),
//    PromptsGet("prompts/get"),
//    NotificationsCancelled("notifications/cancelled"),
//    NotificationsInitialized("notifications/initialized"),
//    NotificationsProgress("notifications/progress"),
//    NotificationsMessage("notifications/message"),
//    NotificationsResourcesUpdated("notifications/resources/updated"),
//    NotificationsResourcesListChanged("notifications/resources/list_changed"),
//    NotificationsToolsListChanged("notifications/tools/list_changed"),
//    NotificationsRootsListChanged("notifications/roots/list_changed"),
//    NotificationsPromptsListChanged("notifications/prompts/list_changed"),
//    ToolsList("tools/list"),
//    ToolsCall("tools/call"),
//    LoggingSetLevel("logging/setLevel"),
//    SamplingCreateMessage("sampling/createMessage"),
//    CompletionComplete("completion/complete"),
//    RootsList("roots/list")


//        Method.Defined.Ping.value -> PingRequest.serializer()
//        Method.Defined.SamplingCreateMessage.value -> CreateMessageRequest.serializer()
//        Method.Defined.RootsList.value -> ListRootsRequest.serializer()
//        Method.Defined.NotificationsCancelled.value -> CancelledNotification.serializer()
//        Method.Defined.NotificationsProgress.value -> ProgressNotification.serializer()
//        Method.Defined.NotificationsMessage.value -> LoggingMessageNotification.serializer()
//        Method.Defined.NotificationsResourcesUpdated.value -> ResourceUpdatedNotification.serializer()
//        Method.Defined.NotificationsResourcesListChanged.value -> ResourceListChangedNotification.serializer()
//        Method.Defined.ToolsList.value -> ToolListChangedNotification.serializer()
//        Method.Defined.PromptsList.value -> PromptListChangedNotification.serializer()


//internal fun selectServerRequestDeserializer(method: String): DeserializationStrategy<ServerRequest>? {
//    return when (method) {
//        Method.Defined.Ping.value -> PingRequest.serializer()
//        Method.Defined.SamplingCreateMessage.value -> CreateMessageRequest.serializer()
//        Method.Defined.RootsList.value -> ListRootsRequest.serializer()
//        else -> null
//    }
//}
//
//internal fun selectServerNotificationDeserializer(element: JsonElement): DeserializationStrategy<ServerNotification>? {
//    return when (element.jsonObject.getValue("method").jsonPrimitive.content) {
//        Method.Defined.NotificationsCancelled.value -> CancelledNotification.serializer()
//        Method.Defined.NotificationsProgress.value -> ProgressNotification.serializer()
//        Method.Defined.NotificationsMessage.value -> LoggingMessageNotification.serializer()
//        Method.Defined.NotificationsResourcesUpdated.value -> ResourceUpdatedNotification.serializer()
//        Method.Defined.NotificationsResourcesListChanged.value -> ResourceListChangedNotification.serializer()
//        Method.Defined.ToolsList.value -> ToolListChangedNotification.serializer()
//        Method.Defined.PromptsList.value -> PromptListChangedNotification.serializer()
//        else -> null
//    }
//}
//
//private fun selectServerResultDeserializer(element: JsonElement): DeserializationStrategy<ServerResult>? {
//    val jsonObject = element.jsonObject
//    return when {
//        jsonObject.contains("tools") -> ListToolsResult.serializer()
//        jsonObject.contains("resources") -> ListResourcesResult.serializer()
//        jsonObject.contains("resourceTemplates") -> ListResourceTemplatesResult.serializer()
//        jsonObject.contains("prompts") -> ListPromptsResult.serializer()
//        jsonObject.contains("capabilities") -> InitializeResult.serializer()
//        jsonObject.contains("description") -> GetPromptResult.serializer()
//        jsonObject.contains("completion") -> CompleteResult.serializer()
//        jsonObject.contains("toolResult") -> CompatibilityCallToolResult.serializer()
//        jsonObject.contains("contents") -> ReadResourceResult.serializer()
//        jsonObject.contains("content") -> CallToolResult.serializer()
//        else -> null
//    }
//}
//
//private fun selectClientResultDeserializer(element: JsonElement): DeserializationStrategy<ClientResult>? {
//    val jsonObject = element.jsonObject
//    return when {
//        jsonObject.contains("model") -> CreateMessageResult.serializer()
//        jsonObject.contains("roots") -> ListRootsResult.serializer()
//        else -> null
//    }
//}

//@Serializable data class McpInitializeRequest(
//    val protocolVersion: LocalDate,
//    val capabilities: McpCapabilitiesClient,
//    val clientInfo: McpClientInfo,
//)
//
//@Serializable data class McpCapabilitiesClient(
//    val roots: McpCapability? = null,
//    val sampling: McpCapability? = null,
//    val elicitation: McpCapability? = null,
//    val experimental: McpCapability? = null,
//)
//@Serializable data class McpCapabilitiesServer(
//	val prompts: McpCapability? = null,
//	val resources: McpCapability? = null,
//	val tools: McpCapability? = null,
//	val logging: McpCapability? = null,
//	val completions: McpCapability? = null,
//	val experimental: McpCapability? = null,
//)
//@Serializable data class McpClientInfo(val name: String, val title: String, val version: String)
//
//@Serializable data class McpInitializeResponse(
//    val protocolVersion: LocalDate,
//    val capabilities: McpCapabilitiesServer,
//    val serverInfo: McpClientInfo,
//    val instructions: String? = null,
//)
//
//@Serializable data class McpCapability(
//    val listChanged: Boolean = false,
//    val subscribe: Boolean = false
//)
//@Serializable data class McpRoots()
//@Serializable data class McpSampling()
//@Serializable data class McpElicitation()
//@Serializable data class McpExperimental()
//@Serializable data class McpListRequest(
//    val cursor: String? = null
//)
//@Serializable data class McpPromptPage(
//    val prompts: List<McpPrompt>,
//    val nextCursort: String?= null,
//)
//@Serializable data class McpPrompt(
//    val name: String,
//    val title: String,
//    val description: String,
//    val arguments: List<McpArgument> = listOf()
//)
//@Serializable data class McpPromptResult(
//    val description: String,
//    val message: List<McpPromptMessage>
//)
//@Serializable data class McpPromptMessage(
//    val role: String,
//    val content: McpPromptMessageContent
//)
//@Serializable data class McpPromptMessageContent(
//    val type: McpPromptMessageContentType = McpPromptMessageContentType.text,
//    val text: String? = null,
//    val data: String? = null,
//    val mimeType: String? = null,
//    val resource: McpPromptMessageContentResource? = null
//)
//@Serializable data class McpPromptMessageContentResource(
//    val uri: String,
//    val name: String,
//    val title: String,
//    val mimeType: String,
//    val text: String? = null,
//    val data: String? = null,
//)
//@Serializable enum class McpPromptMessageContentType { text, image, audio, resource }
//@Serializable data class McpResource()
//@Serializable data class McpTool()
//@Serializable data class McpLogging()
//@Serializable data class McpCompletion()
//@Serializable data class McpArgument(val name: String, val description: String, val required: Boolean)
//@Serializable data class McpPromptRequest(
//    val name: String,
//    val arguments: Map<String, String> = mapOf(),
//)
