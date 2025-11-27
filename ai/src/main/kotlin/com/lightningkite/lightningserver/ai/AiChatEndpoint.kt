package com.lightningkite.lightningserver.ai

import ai.koog.agents.core.tools.SimpleTool
import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.invoke
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.ai.koog.LLMClientAndModel
import com.lightningkite.services.ai.koog.LLMClientAndModelSettings
import com.lightningkite.services.database.*
import com.lightningkite.services.files.PublicFileSystem
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.Uuid

/**
 * The AiChatEndpoints module is drop in module for a SIMPLE chat bot experience.
 *
 * It defines two tables for conversations and messages and the endpoints for accessing and creating them. Messages
 * created by the user will automatically be set to the LLM using the ChatBot chat function. The user is expected to
 * listen to websockets for the LLM responses. You can also pass in tools such as QueryTableTool allowing the LLM to
 * make queries to you database and return the results.
 *
 * @property llm Runtime provider for the LLM client and model
 * @property database The database to store the conversations and messages
// * @property files A file system used for historical chat compression and other chat data not necessary for the user to see
// * @property filePath A path in the filesystem for where to store conversation files
 * @property tools Any Simple Tools you want to grant the LLM to use
 * @property systemPrompt System prompt that guides the chatbot's behavior
 * @property maxIterations The max number of iterations an LLM can do for a single prompt from the user
 * @property authRequirement The Auth requirements to access the conversation and message endpoints
 * @property conversationPermissions Permissions for an authenticated subject for the conversation model
 * @property messagePermissions Permissions for an authenticated subject for the message model
 **/
public class AiChatEndpoints<Subject : HasId<*>>(
    private val llm: ServerSetting<LLMClientAndModelSettings, LLMClientAndModel>,
    database: ServerSetting<Database.Settings, Database>,
//    private val files: ServerSetting<PublicFileSystem.Settings, PublicFileSystem>,
//    private val filePath: String,
    private val tools: suspend context(ServerRuntime) AuthAccess<Subject>.() -> List<SimpleTool<*>>,
    private val systemPrompt: String,
    private val maxIterations: Int,
    authRequirement: AuthRequirement<Subject>,
    conversationPermissions: suspend context(ServerRuntime) AuthAccess<Subject>.() -> ModelPermissions<Conversation>,
    messagePermissions: suspend context(ServerRuntime) AuthAccess<Subject>.() -> ModelPermissions<ConversationMessage>,
) : ServerBuilder() {

    // Model Infos
    public val conversationInfo: ModelInfo<Subject, Conversation, Uuid> = database.explicitModelInfo(
        auth = authRequirement,
        serializer = Conversation.serializer(),
        idSerializer = Uuid.serializer(),
        permissions = conversationPermissions,
    )

    public val messageInfo: ModelInfo<Subject, ConversationMessage, Uuid> = database.explicitModelInfo(
        auth = authRequirement,
        serializer = ConversationMessage.serializer(),
        idSerializer = Uuid.serializer(),
        permissions = messagePermissions,
        postPermissionsForUser = {
            it.postCreate { message ->
                if (message.role == ConversationMessage.Role.User)
                    requestLLM(message)
            }
                .interceptCreate { message ->
                    val conversation = conversationInfo.table(this).get(message.conversationId)
                    if (conversation?.subjectId != auth.rawId || message.subjectId != auth.rawId)
                        throw BadRequestException("")
                    message
                }
        }

    )

    // Pathing
    private val conversationPath = path.path("conversations")
    private val messagePath = path.path("messages")

    // Tasks and Signals
    public val requestLLM: Task<ConversationMessage> = messagePath bind Task<ConversationMessage> { userMessage ->

//        val historicFile = files().root.then(filePath).then("${userMessage.conversationId}.json")

        Chatbot(llm(), tools(this), systemPrompt)
            .chat(userMessage, messageInfo.table(), maxIterations)
    }


    // Modules
    public val conversations: ModelRestEndpoints<Subject, Conversation, Uuid> =
        conversationPath include ModelRestEndpoints(conversationInfo)

    public val messages: ModelRestEndpoints<Subject, ConversationMessage, Uuid> =
        messagePath include ModelRestEndpoints(messageInfo)

    public val messageUpdates: ModelRestUpdatesWebsocket<Subject, ConversationMessage, Uuid> =
        messagePath include ModelRestUpdatesWebsocket(messageInfo)


}