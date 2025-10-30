# Automatically Generated REST Endpoints

Last updated January 2025 (`version-5`)

By combining [typed endpoints](typed-endpoints.md), [authentication](authentication.md), and [databases](database.md), we can conveniently generate REST endpoints for any given model automatically.

## Basic Example

Here's a complete example showing how to create REST endpoints for a Post model:

```kotlin
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.services.database.*
import com.lightningkite.services.data.GenerateDataClassPaths
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import kotlin.time.Instant
import kotlin.time.Clock

@Serializable
@GenerateDataClassPaths
@AdminTableColumns(["title", "author", "createdAt"])
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val author: String,
    val body: String,
    val createdAt: Instant = Clock.System.now()
) : HasId<Uuid>

object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())

    val posts = path.path("posts") include object : ServerBuilder() {
        val info = database.modelInfo(
            auth = UserAuth.require(),
            permissions = {
                val user = auth.fetch()
                ModelPermissions(
                    create = condition { it.author eq user.email },
                    read = condition { it.always },
                    update = condition { it.author eq user.email },
                    delete = condition { it.author eq user.email }
                )
            }
        )
        val rest = path.path("rest") module ModelRestEndpoints(info)
    }
}
```

## Understanding ModelInfo

`ModelInfo` is the key to automatic REST endpoint generation. It combines:
- **Authentication requirements** - Who can access the endpoints
- **Permissions** - What operations users can perform on which items
- **Database access** - How to access the underlying data

```kotlin
val postInfo = database.modelInfo(
    auth = UserAuth.require(),  // Require authenticated user
    permissions = {
        // Context: `auth` is the authenticated user
        val user = auth.fetch()
        ModelPermissions(
            create = condition { it.author eq user.email },
            read = condition { it.always },
            update = condition { it.author eq user.email },
            delete = condition { it.author eq user.email }
        )
    }
)
```

## Generated Endpoints

When you create `ModelRestEndpoints`, the following endpoints are automatically generated:

- `GET /posts/rest` - List all posts (respecting read permissions)
  - Supports query parameters for filtering, sorting, and pagination
- `POST /posts/rest` - Create a new post (respecting create permissions)
- `GET /posts/rest/{id}` - Get a specific post
- `PATCH /posts/rest/{id}` - Update a specific post (respecting update permissions)
- `DELETE /posts/rest/{id}` - Delete a specific post (respecting delete permissions)
- `POST /posts/rest/query` - Advanced query endpoint
- `POST /posts/rest/count` - Count posts matching a condition
- `POST /posts/rest/bulk-delete` - Delete multiple posts

## Permission Masks

You can also hide or mask certain fields based on conditions:

```kotlin
val postInfo = database.modelInfo(
    auth = UserAuth.require() or AuthRequirement.None,
    permissions = {
        val user = authOrNull?.fetch()
        ModelPermissions(
            create = condition { it.always },
            read = condition { it.always },
            readMask = mask {
                // Hide private notes unless you're the author
                it.privateNotes.maskedTo(null).unless(
                    condition { it.author eq user?.email }
                )
            },
            update = condition { it.author eq user?.email },
            delete = condition { it.author eq user?.email }
        )
    }
)
```

## Update Restrictions

You can also prevent certain fields from being modified:

```kotlin
ModelPermissions(
    // ...
    updateRestrictions = updateRestrictions {
        it.author.cannotBeModified()
        it.createdAt.cannotBeModified()
    }
)
```

## Adding WebSocket Updates

You can also add real-time WebSocket updates for your REST endpoints:

```kotlin
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.ModelRestUpdatesWebsocket
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebsocket.Companion.plus

val rest = path.path("rest") module (
    ModelRestEndpoints(info) + ModelRestUpdatesWebsocket(info)
)
```

This adds a WebSocket endpoint at `/posts/rest/updates` that sends real-time notifications when posts are created, updated, or deleted.

## Example: Public Read, Authenticated Write

A common pattern is to allow anyone to read but require authentication to write:

```kotlin
val postInfo = database.modelInfo(
    auth = UserAuth.require() or AuthRequirement.None,
    permissions = {
        val user = authOrNull?.fetch()
        val isAuthenticated = user != null

        ModelPermissions(
            create = if (isAuthenticated) condition { it.always } else condition { it.never },
            read = condition { it.always },  // Anyone can read
            update = if (isAuthenticated)
                condition { it.author eq user!!.email }
            else
                condition { it.never },
            delete = if (isAuthenticated)
                condition { it.author eq user!!.email }
            else
                condition { it.never }
        )
    }
)
```

NEXT: [Tasks](tasks.md)
