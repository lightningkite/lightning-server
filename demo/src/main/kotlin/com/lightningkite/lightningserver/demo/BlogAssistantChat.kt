package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.ai.*
import com.lightningkite.lightningserver.auth.fetch
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.demo.models.BlogPost
import com.lightningkite.lightningserver.auth.require
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.ai.koog.LLMClientAndModel
import com.lightningkite.services.ai.koog.LLMClientAndModelSettings
import com.lightningkite.services.database.*
import kotlinx.serialization.KSerializer
import kotlin.uuid.Uuid

/**
 * A practical implementation of LLMChatEndpoints for blog management.
 *
 * This assistant helps users:
 * - Query blog posts (search, filter, count)
 * - Create new blog posts
 * - Update existing posts (edit, publish, archive)
 * - Delete posts (with approval required)
 *
 * Read-only tools execute automatically, while write operations require approval
 * (unless the user has pre-authorized them for the conversation).
 */
class BlogAssistantChat(
    database: ServerSetting<Database.Settings, Database>,
    override val defaultLlm: ServerSetting<LLMClientAndModelSettings, LLMClientAndModel>,
    private val blogPostInfo: ModelInfo<User, BlogPost, Uuid>,
) : LLMChatEndpoints<User>(
    database = database,
    authRequirement = Server.UserAuth.require(),
    conversationPermissions = {
        val subjectId = authOrNull?.rawId?.toString() ?: ""
        ModelPermissions(
            create = Condition.Always,
            read = condition { it.subjectId eq subjectId },
            update = condition { it.subjectId eq subjectId },
            delete = condition { it.subjectId eq subjectId },
        )
    },
    messagePermissions = {
        val subjectId = authOrNull?.rawId?.toString() ?: ""
        ModelPermissions(
            create = Condition.Always,
            read = condition { it.subjectId eq subjectId },
            update = Condition.Never,
            delete = Condition.Never,
        )
    },
) {

    override val tools: Map<String, ChatTool<User, *>> =
        (blogPostInfo.readTools(queryLimit = 20) + blogPostInfo.writeTools(writeLimit = 5, modelExamples = emptyList()))
            .associateBy { it.name }

    context(serverRuntime: ServerRuntime)
    override suspend fun prompt(
        builder: PromptBuilderAlt,
        auth: AuthAccess<User>,
        conversation: SystemChatConversation
    ) = with(builder) {
        system("""
            You are a helpful blog management assistant. You can help users:
            - Search and browse blog posts
            - Create new blog posts
            - Edit existing posts (title, content, excerpt, tags)
            - Publish or archive posts
            - Delete posts (requires user approval)
            
            When creating or updating posts, be helpful and ask clarifying questions if needed.
            For destructive operations like delete, always explain what will happen first.
            """.trimIndent())
        super.prompt(builder, auth, conversation)
        system("You are working on behalf of this user: ${auth.auth.fetch()}")
    }
}
