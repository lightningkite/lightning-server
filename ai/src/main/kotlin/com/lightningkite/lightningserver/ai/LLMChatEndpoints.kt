package com.lightningkite.lightningserver.ai

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.markdown.markdown
import ai.koog.prompt.message.Message
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.services.ai.koog.LLMClientAndModel
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.and
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.eq
import com.lightningkite.services.database.gte
import com.lightningkite.services.database.insertOne
import com.lightningkite.services.database.modification
import com.lightningkite.services.database.sort
import com.lightningkite.services.database.updateOneById
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

public abstract class LLMChatEndpoints<Subject : HasId<*>>(
    database: ServerSetting<Database.Settings, Database>,
    authRequirement: AuthRequirement<Subject>,
    conversationPermissions: suspend context(ServerRuntime) AuthAccess<Subject>.() -> ModelPermissions<SystemChatConversation>,
    messagePermissions: suspend context(ServerRuntime) AuthAccess<Subject>.() -> ModelPermissions<SystemChatMessage>,
    responseLockTimeout: Duration = 5.minutes,
    toolLockTimeout: Duration = 5.minutes,
) : SystemChatEndpoints<Subject>(
    database = database,
    authRequirement = authRequirement,
    conversationPermissions = conversationPermissions,
    messagePermissions = messagePermissions,
    responseLockTimeout = responseLockTimeout,
    toolLockTimeout = toolLockTimeout,
) {

    /**
     * Map of tool name to ChatTool. Typically built from ModelInfo.readTools() and writeTools().
     */
    public abstract val tools: Map<String, ChatTool<Subject, *>>

    public abstract val defaultLlm: Runtime<LLMClientAndModel>

    context(serverRuntime: ServerRuntime)
    public open suspend fun prompt(
        builder: PromptBuilderAlt,
        auth: AuthAccess<Subject>,
        conversation: SystemChatConversation
    ) {
        builder.append(
            messages.info.table().find(
                condition<SystemChatMessage> {
                    val range = conversation.summaryUpTo?.let { s -> it.createdAt.gte(s) } ?: Condition.Always
                    it.conversationId.eq(conversation._id) and range
                },
                orderBy = sort<SystemChatMessage> { it.createdAt.ascending() },
                skip = 0,
                limit = Int.MAX_VALUE
            ).toList()
        )
    }

    public open val maxIterations: Int = 50

    context(serverRuntime: ServerRuntime)
    override suspend fun respond(
        auth: AuthAccess<Subject>,
        conversation: SystemChatConversation
    ) {
        with(serverRuntime) {
            val toolDescriptors = tools.values.map {
                it.koogDescriptor(com.lightningkite.lightningserver.runtime.serverRuntime.externalSerialization.json.serializersModule)
            }
            try {
                repeat(maxIterations) {
                    defaultLlm()
                        .execute(getPrompt(conversation, auth), toolDescriptors)
                        .handle(conversation, auth)
                }
            } finally {
                if(getPrompt(conversation, auth).messages.sumOf { it.content.estimateTokens() } > compressAfter)
                    compressTask.invoke(conversation)
            }
        }
    }






    context(serverRuntime: ServerRuntime)
    public suspend fun getPrompt(
        conversation: SystemChatConversation,
        auth: AuthAccess<Subject>,
    ): Prompt = promptAlt(existing = Prompt.Empty) { prompt(this, auth, conversation) }

    context(serverRuntime: ServerRuntime) override fun findToolByName(
        auth: AuthAccess<Subject>,
        conversation: SystemChatConversation,
        toolName: String
    ): ChatTool<Subject, *>? = tools[toolName]

    context(serverRuntime: ServerRuntime)
    protected open val compressAfter: Long get() = defaultLlm().model.contextLength / 2

    public val compressTask: Task<SystemChatConversation> =
        path.path("compress") bind Task { conversation: SystemChatConversation ->
            val toCompress = messages.info.table().find(
                condition<SystemChatMessage> {
                    val range = conversation.summaryUpTo?.let { s -> it.createdAt.gte(s) } ?: Condition.Always
                    it.conversationId.eq(conversation._id) and range
                },
                orderBy = sort<SystemChatMessage> { it.createdAt.ascending() },
                skip = 0,
                limit = Int.MAX_VALUE
            ).toList().let { it.subList(0, it.size / 2) }

            val newMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = conversation.subjectId,
                role = SystemChatMessage.Role.Summary,
                createdAt = toCompress.last().createdAt + 0.01.seconds,
                content = defaultLlm().execute(
                    promptAlt(existing = Prompt.Empty) {
                        append(toCompress)
                        user {
                            markdown {
                                +"Create a comprehensive summary of this conversation."
                                br()
                                +"Include the following in your summary:"
                                numbered {
                                    item("Key objectives and problems being addressed")
                                    item("All tools used along with their purpose and outcomes")
                                    item("Critical information discovered or generated")
                                    item("Current progress status and conclusions reached")
                                    item("Any pending questions or unresolved issues")
                                }
                                br()
                                +"FORMAT YOUR SUMMARY WITH CLEAR SECTIONS for easy reference, including:"
                                bulleted {
                                    item("Key Objectives")
                                    item("Tools Used & Results")
                                    item("Key Findings")
                                    item("Current Status")
                                    item("Next Steps")
                                }
                                br()
                                +"This summary will be the ONLY context available for continuing this conversation, along with the system message."
                                +"Ensure it contains ALL essential information needed to proceed effectively."
                            }
                        }
                    },
                    listOf()
                )
                    .mapNotNull { (it as? Message.Assistant)?.content }
                    .joinToString("\n")
            )
            messages.info.table().insertOne(newMessage)
            conversations.info.table().updateOneById(conversation._id, modification {
                it.summaryUpTo assign newMessage.createdAt
            })
        }

    context(serverRuntime: ServerRuntime)
    protected suspend fun List<Message.Response>.handle(
        conversation: SystemChatConversation,
        auth: AuthAccess<Subject>
    ) {
        if (this.isEmpty()) throw Exception("LLM returned no response")

        // Get channel info from the conversation to propagate to response messages
        val (channel, externalIdentifier) = findChannelInfo(conversation._id)

        var needsToWait = false
        var hasToolCalls = false
        var hasFinalAssistantMessage = false

        this.forEach {
            when (it) {
                is Message.Assistant -> {
                    hasFinalAssistantMessage = true
                    messageInfo.table().insertOne(
                        SystemChatMessage(
                            conversationId = conversation._id,
                            subjectId = conversation.subjectId,
                            role = SystemChatMessage.Role.Assistant,
                            channel = channel,
                            externalIdentifier = externalIdentifier,
                            content = it.content,
                            createdAt = now()
                        )
                    )
                }

                is Message.Reasoning -> messageInfo.table().insertOne(
                    SystemChatMessage(
                        conversationId = conversation._id,
                        subjectId = conversation.subjectId,
                        role = SystemChatMessage.Role.Thinking,
                        channel = channel,
                        externalIdentifier = externalIdentifier,
                        content = it.content,
                        createdAt = now()
                    )
                )

                is Message.Tool.Call -> {
                    hasToolCalls = true
                    val tool = findToolByName(auth, conversation, it.tool) ?: run {
                        messageInfo.table().insertOne(
                            SystemChatMessage(
                                conversationId = conversation._id,
                                subjectId = conversation.subjectId,
                                role = SystemChatMessage.Role.ToolRequest,
                                channel = channel,
                                externalIdentifier = externalIdentifier,
                                content = it.content,
                                tool = ToolRequestData(
                                    toolName = it.tool,
                                    arguments = it.content,
                                    requiresApproval = false,
                                    error = "Tool '${it.tool}' not found."
                                ),
                                createdAt = now()
                            )
                        )
                        return@forEach
                    }
                    when (val result = processToolCall(auth, conversation, tool, it.content)) {
                        is ToolCallResult.Error -> messageInfo.table().insertOne(
                            SystemChatMessage(
                                conversationId = conversation._id,
                                subjectId = conversation.subjectId,
                                role = SystemChatMessage.Role.ToolRequest,
                                channel = channel,
                                externalIdentifier = externalIdentifier,
                                content = it.content,
                                tool = ToolRequestData(
                                    toolName = it.tool,
                                    arguments = it.content,
                                    requiresApproval = false,
                                    error = result.error
                                ),
                                createdAt = now()
                            )
                        )
                        // This execution tree thing kinda sucks
                        is ToolCallResult.Executed -> messageInfo.table().insertOne(
                            SystemChatMessage(
                                conversationId = conversation._id,
                                subjectId = conversation.subjectId,
                                role = SystemChatMessage.Role.ToolRequest,
                                channel = channel,
                                externalIdentifier = externalIdentifier,
                                content = it.content,
                                tool = ToolRequestData(
                                    toolName = it.tool,
                                    arguments = it.content,
                                    requiresApproval = false,
                                    result = result.result
                                ),
                                createdAt = now()
                            )
                        )

                        ToolCallResult.WaitingForApproval -> {
                            needsToWait = true
                            messageInfo.table().insertOne(
                                SystemChatMessage(
                                    conversationId = conversation._id,
                                    subjectId = conversation.subjectId,
                                    role = SystemChatMessage.Role.ToolRequest,
                                    channel = channel,
                                    externalIdentifier = externalIdentifier,
                                    content = it.content,
                                    tool = ToolRequestData(
                                        toolName = it.tool,
                                        arguments = it.content,
                                        requiresApproval = true,
                                    ),
                                    createdAt = now()
                                )
                            )
                        }
                    }
                }
            }
        }
        if (needsToWait) throw StopProcessing("Tool needs to be approved.")
        if (hasFinalAssistantMessage && !hasToolCalls) throw StopProcessing("Response complete.")
    }
}



/**
 * Estimate token count for a string.
 * This is a rough approximation - actual tokenization varies by model.
 * ~4 characters per token for English text.
 */
public fun String.estimateTokens(): Long {
    return (length / 4).coerceAtLeast(1).toLong()
}