package com.lightningkite.lightningserver.ai

import ai.koog.prompt.dsl.PromptBuilder


public fun PromptBuilderAlt.append(
    messages: List<SystemChatMessage>
) {
    messages.forEach { append(it) }
}

public fun PromptBuilderAlt.append(
    msg:SystemChatMessage
) {
    val msgContent = msg.content
    when (msg.role) {
        SystemChatMessage.Role.User -> user(msgContent)
        SystemChatMessage.Role.Assistant -> assistant(msgContent)
        SystemChatMessage.Role.System -> system(msgContent)
        SystemChatMessage.Role.Summary -> {
            // Render summary as system context
            system("=== Conversation Summary ===\n$msgContent\n=== Recent Messages ===")
        }
        SystemChatMessage.Role.ToolRequest -> {
            val toolData = msg.tool ?: return
            val toolName = toolData.toolName
            val toolCallId = msg._id.toString()
            val toolArgs = toolData.arguments
            val toolResult = toolData.result
            val toolError = toolData.error
            val approval = toolData.approval

            tool {
                call(toolCallId, toolName, toolArgs)
            }

            when {
                toolResult != null -> tool { result(toolCallId, toolName, toolResult) }
                toolError != null -> tool { result(toolCallId, toolName, "Error: $toolError") }
                approval != null && !approval.approved -> {
                    tool { result(toolCallId, toolName, "Tool execution rejected: ${approval.reason ?: "User declined"}") }
                }
            }
        }
        SystemChatMessage.Role.Thinking -> { /* skip thinking messages */ }
        SystemChatMessage.Role.Error -> system("Previous error: $msgContent")
    }
}