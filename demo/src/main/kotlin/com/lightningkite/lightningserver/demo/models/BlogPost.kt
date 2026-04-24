package com.lightningkite.lightningserver.demo.models

import com.lightningkite.services.data.*
import com.lightningkite.services.database.HasId
import com.lightningkite.services.files.ServerFile
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * BlogPost model demonstrating database operations.
 * 
 * This model showcases:
 * - Database model with various field types
 * - References to other models (author)
 * - File uploads (cover image)
 * - Timestamps and enums
 * - Type-safe query DSL with GenerateDataClassPaths
 */
@Serializable
@GenerateDataClassPaths
@AdminTableColumns(["title", "author", "status", "createdAt"])
@Description("A blog post with title, content, author, and publishing status.")
data class BlogPost(
    override val _id: Uuid = Uuid.random(),

    @Description("The title of the blog post")
    val title: String,

    @Description("The main content of the blog post")
    @MimeType("text/markdown")
    @Multiline
    val content: String,

    @Description("A brief excerpt or summary")
    @Multiline
    val excerpt: String = "",

    @Description("The author's user ID")
    @References(User::class)
    val authorId: Uuid,

    @Description("Optional cover image for the post")
    @MimeType("image/*")
    val coverImage: ServerFile? = null,

    @Description("Tags for categorizing the post")
    val tags: List<String> = listOf(),

    @Description("Current publication status")
    val status: PostStatus = PostStatus.DRAFT,

    @Description("When the post was created")
    val createdAt: Instant = Clock.System.now(),

    @Description("When the post was last updated")
    val updatedAt: Instant = Clock.System.now(),

    @Description("When the post was published (null if not yet published)")
    val publishedAt: Instant? = null,

    @Description("Number of views")
    @AdminHidden
    val viewCount: Int = 0,
) : HasId<Uuid>

@Serializable
enum class PostStatus {
    @DisplayName("Draft")
    DRAFT,

    @DisplayName("Published")
    PUBLISHED,

    @DisplayName("Archived")
    ARCHIVED
}
