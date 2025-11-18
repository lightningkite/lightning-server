package com.lightningkite.lightningserver.demo.models

import com.lightningkite.services.data.Description
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Multiline
import com.lightningkite.services.data.References
import com.lightningkite.services.database.HasId
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Comment model for blog posts.
 * 
 * This model demonstrates:
 * - Nested relationships (comments on posts)
 * - Self-referencing for threaded comments
 * - Simple moderation features
 */
@Serializable
@GenerateDataClassPaths
@Description("A comment on a blog post, with support for threaded replies.")
data class Comment(
    override val _id: Uuid = Uuid.random(),
    
    @Description("The blog post this comment belongs to")
    @References(BlogPost::class)
    val postId: Uuid,
    
    @Description("The author's user ID")
    @References(User::class)
    val authorId: Uuid,
    
    @Description("The comment content")
    @Multiline
    val content: String,
    
    @Description("Parent comment ID for threaded replies")
    @References(Comment::class)
    val parentCommentId: Uuid? = null,
    
    @Description("When the comment was created")
    val createdAt: Instant = Clock.System.now(),
    
    @Description("When the comment was last edited")
    val editedAt: Instant? = null,
    
    @Description("Whether the comment has been approved by a moderator")
    val isApproved: Boolean = true,
    
    @Description("Whether the comment has been flagged for moderation")
    val isFlagged: Boolean = false
) : HasId<Uuid>
