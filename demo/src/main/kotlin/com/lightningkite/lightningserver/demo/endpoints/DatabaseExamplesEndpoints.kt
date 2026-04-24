package com.lightningkite.lightningserver.demo.endpoints

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.demo.models.*
import com.lightningkite.lightningserver.demo.models.status
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.route
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * DatabaseExamplesEndpoints - Demonstrates database operations with the query DSL.
 *
 * ⚠️ IMPORTANT: This file demonstrates LOW-LEVEL database operations for educational purposes.
 *
 * For production applications, you should use ModelRestEndpoints instead, which provides:
 * - Pre-built CRUD operations (list, get, create, update, delete)
 * - Built-in permission checks
 * - Automatic pagination and sorting
 * - Query parameter handling
 * - OpenAPI documentation
 * - WebSocket updates (optional)
 *
 * See BlogEndpoints.kt for the RECOMMENDED pattern:
 * ```kotlin
 * val info = database.modelInfo(
 *     auth = noAuth,
 *     permissions = { ModelPermissions.allowAll<MyModel>() }
 * )
 * val rest = path.path("rest") include ModelRestEndpoints(info)
 * ```
 *
 * These manual examples showcase:
 * - How to use the database query DSL directly
 * - Type-safe query conditions
 * - Custom business logic in endpoints
 * - Complex queries with multiple conditions
 * - Aggregations and counting
 *
 * Use manual database operations when you need:
 * - Custom business logic beyond simple CRUD
 * - Complex queries not supported by ModelRestEndpoints
 * - Special validation or transformation logic
 */
class DatabaseExamplesEndpoints(
    private val database: Runtime<Database>,
) : ServerBuilder() {

    /**
     * POST /blog/posts
     *
     * Create a new blog post.
     * Demonstrates: insertOne operation
     */
    val createPost = path.path("blog").path("posts").post bind ApiHttpHandler(
        summary = "Create a new blog post",
        description = "Creates a new blog post with the provided title, content, and author information",
        auth = noAuth,
        errorCases = listOf(
            LSError(http = 400, detail = "invalid-input", message = "Title and content are required")
        ),
        successCode = HttpStatus.Created,
        implementation = { input: CreatePostRequest ->
            if (input.title.isBlank()) {
                throw BadRequestException("Title is required")
            }
            if (input.content.isBlank()) {
                throw BadRequestException("Content is required")
            }

            val post = BlogPost(
                title = input.title,
                content = input.content,
                excerpt = input.excerpt,
                authorId = input.authorId,
                tags = input.tags,
                status = PostStatus.DRAFT
            )

            database().table<BlogPost>().insertOne(post)
        }
    )

    /**
     * GET /blog/posts
     *
     * List blog posts with optional filtering, pagination, and sorting.
     * Demonstrates: find with conditions, pagination, and sorting
     */
    val listPosts = path.path("blog").path("posts").get bind ApiHttpHandler(
        summary = "List blog posts",
        description = "Retrieves a paginated list of blog posts with optional filtering by status and tags",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            // Note: Query parameters would need to be passed differently in ApiHttpHandler
            // For this example, we'll return all published posts
            val posts = database().table<BlogPost>()

            // Simple condition for published posts
            val condition: Condition<BlogPost> = condition { it.status eq PostStatus.PUBLISHED }

            // Execute query with sorting
            val results = posts.find(
                condition = condition,
                orderBy = listOf(SortPart(BlogPost.path.createdAt, false))
            ).toList()

            val total = posts.count(condition)

            ListPostsResponse(
                posts = results,
                total = total,
                page = 0,
                pageSize = results.size
            )
        }
    )

    /**
     * GET /blog/posts/{id}
     *
     * Get a single blog post by ID.
     * Demonstrates: get operation, increment operation
     */
    val getPost = path.path("blog").path("posts").arg<Uuid>("id").get bind ApiHttpHandler(
        summary = "Get a blog post by ID",
        description = "Retrieves a single blog post and increments its view count",
        auth = noAuth,
        errorCases = listOf(
            LSError(http = 404, detail = "not-found", message = "Blog post not found")
        ),
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            val id = route.arg1
            val posts = database().table<BlogPost>()

            val post = posts.get(id) ?: throw NotFoundException("Blog post not found")

            // Increment view count
            posts.updateOne(
                condition { it._id eq id },
                modification { it.viewCount += 1 }
            )

            post.copy(viewCount = post.viewCount + 1)
        }
    )

    /**
     * PUT /blog/posts/{id}
     *
     * Update an existing blog post.
     * Demonstrates: updateOne operation with multiple field updates
     */
    val updatePost = path.path("blog").path("posts").arg<Uuid>("id").put bind ApiHttpHandler(
        summary = "Update a blog post",
        description = "Updates an existing blog post with new content",
        auth = noAuth,
        errorCases = listOf(
            LSError(http = 404, detail = "not-found", message = "Blog post not found")
        ),
        successCode = HttpStatus.OK,
        implementation = { input: UpdatePostRequest ->
            val id = route.arg1
            val posts = database().table<BlogPost>()

            // Check if post exists
            posts.get(id) ?: throw NotFoundException("Blog post not found")

            posts.updateOne(condition { it._id eq id }, modification {
                it.updatedAt assign Clock.System.now()
                if (input.title != null) {
                    it.title assign input.title
                }
                if (input.content != null) {
                    it.content assign input.content
                }
                if (input.excerpt != null) {
                    it.excerpt assign input.excerpt
                }
                if (input.tags != null) {
                    it.tags assign input.tags
                }
                if (input.status != null) {
                    it.status assign input.status
                    if (input.status == PostStatus.PUBLISHED) {
                        it.publishedAt assign Clock.System.now()
                    }
                }
            })

            // Return updated post
            posts.get(id)!!
        }
    )

    /**
     * DELETE /blog/posts/{id}
     *
     * Delete a blog post and all its comments.
     * Demonstrates: deleteOne operation, cascading deletes
     */
    val deletePost =
        path.path("blog").path("posts").arg<Uuid>("id").delete bind ApiHttpHandler<_, Nothing?, Unit, Unit>(
            summary = "Delete a blog post",
            description = "Deletes a blog post and all associated comments",
            auth = noAuth,
            errorCases = listOf(
                LSError(http = 404, detail = "not-found", message = "Blog post not found")
            ),
            successCode = HttpStatus.NoContent,
            implementation = { _: Unit ->
                val id = route.arg1
                val posts = database().table<BlogPost>()
                val comments = database().table<Comment>()

                // Check if post exists
                posts.get(id) ?: throw NotFoundException("Blog post not found")

                // Delete all comments for this post
                comments.deleteMany(condition { it.postId eq id })

                // Delete the post
                posts.deleteOne(condition { it._id eq id })
            }
        )

    /**
     * POST /blog/posts/{id}/comments
     *
     * Add a comment to a blog post.
     * Demonstrates: insertOne with relationships
     */
    val createComment = path.path("blog").path("posts").arg<Uuid>("postId").path("comments").post bind ApiHttpHandler(
        summary = "Add a comment to a blog post",
        description = "Creates a new comment on the specified blog post",
        auth = noAuth,
        errorCases = listOf(
            LSError(http = 404, detail = "not-found", message = "Blog post not found"),
            LSError(http = 400, detail = "invalid-input", message = "Comment content is required")
        ),
        successCode = HttpStatus.Created,
        implementation = { input: CreateCommentRequest ->
            val postId = route.arg1
            val posts = database().table<BlogPost>()

            // Verify post exists
            posts.get(postId) ?: throw NotFoundException("Blog post not found")

            if (input.content.isBlank()) {
                throw BadRequestException("Comment content is required")
            }

            val comment = Comment(
                postId = postId,
                authorId = input.authorId,
                content = input.content,
                parentCommentId = input.parentCommentId
            )

            database().table<Comment>().insertOne(comment)
        }
    )

    /**
     * GET /blog/posts/{id}/comments
     *
     * Get all comments for a blog post.
     * Demonstrates: find with relationships, sorting
     */
    val getComments = path.path("blog").path("posts").arg<Uuid>("postId").path("comments").get bind ApiHttpHandler(
        summary = "Get comments for a blog post",
        description = "Retrieves all approved comments for the specified blog post, sorted by creation date",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            val postId = route.arg1

            database().table<Comment>()
                .find(
                    condition = condition {
                        (it.postId eq postId) and (it.isApproved eq true)
                    },
                    orderBy = listOf(SortPart(Comment.path.createdAt, true))
                )
                .toList()
        }
    )

    /**
     * POST /blog/search
     *
     * Search blog posts using complex queries.
     * Demonstrates: complex conditions, text search patterns
     */
    val searchPosts = path.path("blog").path("search").post bind ApiHttpHandler(
        summary = "Search blog posts",
        description = "Performs a full-text search across blog post titles and content",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { input: SearchPostsRequest ->
            val posts = database().table<BlogPost>()

            // Start with published posts only
            var condition: Condition<BlogPost> = condition { it.status eq PostStatus.PUBLISHED }

            // Filter by tags if provided
            if (input.tags.isNotEmpty()) {
                input.tags.forEach { tag ->
                    condition = condition and condition { it.tags.any { it.eq(tag) } }
                }
            }

            // Get all published posts (with tag filter if any)
            val allResults = posts.find(
                condition = condition,
                orderBy = listOf(SortPart(BlogPost.path.publishedAt, false))
            ).toList()

            // Filter by query text in memory (since database doesn't support case-insensitive contains)
            val filtered = if (input.query.isNotBlank()) {
                allResults.filter { post ->
                    post.title.contains(input.query, ignoreCase = true) ||
                            post.content.contains(input.query, ignoreCase = true)
                }
            } else {
                allResults
            }

            filtered
        }
    )
}

// Request/Response models

@Serializable
data class CreatePostRequest(
    val title: String,
    val content: String,
    val excerpt: String = "",
    val authorId: Uuid,
    val tags: List<String> = listOf(),
)

@Serializable
data class UpdatePostRequest(
    val title: String? = null,
    val content: String? = null,
    val excerpt: String? = null,
    val tags: List<String>? = null,
    val status: PostStatus? = null,
)

@Serializable
data class ListPostsResponse(
    val posts: List<BlogPost>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
)

@Serializable
data class CreateCommentRequest(
    val authorId: Uuid,
    val content: String,
    val parentCommentId: Uuid? = null,
)

@Serializable
data class SearchPostsRequest(
    val query: String = "",
    val tags: List<String> = listOf(),
)
