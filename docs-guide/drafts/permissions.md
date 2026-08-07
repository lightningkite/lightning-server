> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Permissions & Field Masks

Lightning Server's permission system is one of its most important security
features.  Rather than writing ad-hoc access checks inside every endpoint
handler, you declare *once* what each type of caller may do to a collection,
and the framework enforces those rules on every read, write, and delete —
including partial queries, aggregations, and websocket update streams.

The system has three distinct layers, which compose cleanly:

| Layer | Type | Controls |
|---|---|---|
| Row-level access | `ModelPermissions<T>` | Which rows a caller may read, create, update, or delete |
| Field-level redaction | `Mask<T>` | Which fields a caller receives on read |
| Field-level write rules | `UpdateRestrictions<T>` | Which fields a caller may write to, under what conditions, with what value constraints |

All three are declared together in a `ModelPermissions<T>` value, which is then
handed to `modelInfo(...)` so that `ModelRestEndpoints` (or any manual endpoint
that calls `info.table(auth)`) gets a secured `Table<T>` automatically.

## Imports

All examples in this chapter use the following imports:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlin.uuid.*
```

Lightning Server follows the wildcard-import idiom throughout — every example
in this chapter assumes those wildcard imports are in scope.

## The example model

The examples throughout this chapter use a simple `Post` model with an owner,
a published flag, and a sensitive `internalNotes` field that should only be
visible to the post's author:

```kotlin
@Serializable
@GenerateDataClassPaths
data class Post(
    override val _id: Uuid = Uuid.random(),
    val authorId: Uuid,
    val title: String,
    val body: String,
    val published: Boolean = false,
    val internalNotes: String = "",  // must not be visible to other users
) : HasId<Uuid>
```

And a minimal `User` model:

```kotlin
@Serializable
@GenerateDataClassPaths
data class User(
    override val _id: Uuid = Uuid.random(),
    val email: String,
    val isSuperUser: Boolean = false,
) : HasId<Uuid>

object UserAuth : PrincipalType<User, Uuid> {
    override val idSerializer = Uuid.serializer()
    override val subjectSerializer = User.serializer()

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): User =
        PostServer.userTable().get(id) ?: throw NotFoundException()
}
```

## ModelPermissions — row-level access

`ModelPermissions<T>` is the top-level container.  It holds one `Condition<T>`
for each CRUD operation, plus the two field-level layers:

```kotlin
// From service-abstractions/database-shared ModelPermissions.kt (verified)
data class ModelPermissions<Model>(
    val create: Condition<Model> = Condition.Never,   // default: deny everything
    val read:   Condition<Model> = Condition.Never,
    val readMask: Mask<Model>   = Mask(emptyList()),  // empty = no masking
    val update: Condition<Model> = Condition.Never,
    val updateRestrictions: UpdateRestrictions<Model> = UpdateRestrictions(fields = emptyList()),
    val delete: Condition<Model> = Condition.Never,
    val maxQueryTimeMs: Long = 1_000L,
)
```

The default for every condition is `Condition.Never` — nothing is allowed
unless you explicitly open it up.  This whitelist-by-default stance means a
newly created `ModelPermissions()` value is safe to hand to untrusted callers:
they will see nothing and can change nothing.

### Convenience constructors

Two secondary constructors let you express common patterns more concisely:

```kotlin
// Separate read and manage conditions (manage = create + update + delete)
ModelPermissions(
    read = Condition.Always,
    manage = condition { it.authorId eq currentUserId },
)

// Same condition for all four operations
ModelPermissions(all = Condition.Always)

// Allow everything (useful for super-user / admin paths)
ModelPermissions.allowAll<Post>()
```

### Row-level conditions in practice

Conditions use the same DSL documented in the [Database chapter](database.md).
Inside the `permissions` lambda of `modelInfo(...)`, you have access to
`authOrNull` (the raw `Authentication<USER>?` token) and `auth` (non-null form,
only safe to access if `SUBJECT` is non-nullable).  Call `auth.fetch()` to load
the full user object — it is cached on the token for the lifetime of the request.

```kotlin
val postInfo = database.modelInfo(
    auth = UserAuth.require() or AuthRequirement.None,
    tableName = "Post",
    permissions = {
        val user: User? = authOrNull?.fetch()
        val isAdmin: Boolean = user?.isSuperUser == true
        val selfCondition: Condition<Post> = condition { it.authorId eqNn user?._id }

        ModelPermissions(
            // Anyone (even anonymous) may read published posts
            read = if (isAdmin) Condition.Always
                   else condition { it.published eq true } or selfCondition,

            // Only the author (or admin) may create / update / delete
            create = if (isAdmin) Condition.Always else selfCondition,
            update = if (isAdmin) Condition.Always else selfCondition,
            delete = if (isAdmin) Condition.Always else selfCondition,
        )
    }
)
```

**How row-level conditions are enforced**

When the framework calls `info.table(auth)` it wraps the raw `Table<Post>` in
a `ModelPermissionsTable<Post>`.  Every subsequent operation on that table
automatically ANDs your condition into the query before hitting the database:

- `find(userQuery)` becomes `find(userQuery and permissions.read)` — rows that
  fail the read condition are never returned.
- `updateMany(condition, mod)` becomes `updateMany(condition and permissions.allowed(mod), mod)` — if `permissions.allowed(mod)` resolves to `Condition.Never`, no rows are touched.
- `insert(models)` filters out any models that fail `permissions.create` before
  insertion; models that fail are silently dropped (not thrown as an error).
- `delete` is ANDed with `permissions.delete`.

The database receives only the combined condition, so the security boundary is
at the storage layer, not the application layer.

## Field masks — hiding fields on read

`Mask<T>` is a list of `(Condition<T>, Modification<T>)` pairs.  Each pair says:
"if the condition is NOT satisfied, apply this modification to the returned
document."  In practice this means: "unless the caller passes the condition,
overwrite these fields with neutral values."

Build a mask with the `mask<T> { }` DSL:

```kotlin
// From service-abstractions/database-shared Mask.kt (verified API)
val postReadMask = mask<Post> {
    // it.internalNotes.mask(value, unless = condition)
    // "Replace internalNotes with "" UNLESS the caller is the author or admin"
    it.internalNotes.mask(
        value = "",
        unless = selfCondition or adminCondition,
    )
}
```

The `unless` parameter is a `Condition<Post>` evaluated on the record being
returned — not on the caller directly.  Because the condition references the
document itself (e.g. `it.authorId eq user?._id`), the mask can selectively
redact fields based on the relationship between the record and the caller.

> **Note:** `selfCondition` and `adminCondition` in the above snippet are
> illustrative names for the conditions computed from `user?._id` and
> `user?.isSuperUser`.  In real code you close over those values from the
> `permissions` lambda's scope — see the full example below.

### How masks interact with queries

The mask layer has a subtle but important property: if a caller's query filter
or sort key references a masked field, `ModelPermissionsTable` adds the "reveal"
condition (the `unless` side) to the query automatically.  This prevents a
caller from inferring secret field values by issuing queries like
`internalNotes neq ""` and observing which records come back.

Concretely: if `internalNotes` is masked (overwritten with `""`) for a caller,
then a `find(condition { it.internalNotes eq "secret" })` will match nothing,
because the `self or admin` condition is added to the database query,
restricting the result set to only those rows where the field is actually
visible to the caller.

### Applying the mask in ModelPermissions

Pass the mask as the `readMask` field of `ModelPermissions`:

```kotlin
ModelPermissions(
    read = ...,
    readMask = mask<Post> {
        it.internalNotes.mask("", unless = selfCondition or adminCondition)
    },
    update = ...,
    delete = ...,
)
```

The `ModelPermissionsTable` calls `permissions.mask(model)` on every result
before returning it to the caller.  This happens after the row-level read
condition is applied, so masked fields are only redacted on rows that the
caller could already see.

## UpdateRestrictions — field-level write rules

`UpdateRestrictions<T>` controls which fields may be written and under what
conditions.  It operates in one of two modes:

- **Blacklist** (default): all fields are modifiable unless you explicitly
  restrict them.
- **Whitelist**: all fields are blocked unless you explicitly allow them.

Build one with `updateRestrictions<T> { }`:

```kotlin
// From service-abstractions/database-shared UpdateRestrictions.kt (verified API)
val postUpdateRestrictions = updateRestrictions<Post> {
    // The author ID must never change after creation
    it.authorId.cannotBeModified()

    // published can only be set to true by the author (never to false via this path)
    // illustrative: in real code you'd use the condition from the permissions lambda scope
    it.published.mustBe { it eq true }
}
```

Available restriction helpers on each field path:

| Method | Effect |
|---|---|
| `path.cannotBeModified()` | Blocks the field entirely (blacklist mode) |
| `path.canBeModified()` | Allows the field (whitelist mode) |
| `path requires condition` | Field is only writable when the existing record matches the condition |
| `path.mustBe { valueCond }` | The new value must satisfy `valueCond` |
| `path.requires(requires, valueMust)` | Combines both a pre-condition and a value constraint |

`UpdateRestrictions` evaluates lazily against the modification being sent.
When a caller sends an update, `updateRestrictions(modification)` returns a
`Condition<T>` that must hold on the *existing* record for the update to be
allowed.  If the field is completely forbidden, it returns `Condition.Never` —
the update affects zero rows regardless of the caller's query.

### Composing restrictions

Use `include(otherRestrictions)` to layer restrictions from multiple sources:

```kotlin
val baseRestrictions = updateRestrictions<Post> {
    it.authorId.cannotBeModified()
}

val userRestrictions = updateRestrictions<Post> {
    include(baseRestrictions)
    it.published.mustBe { it eq true }
}
```

The extension function `ModelPermissions.withAdditionalUpdateRestrictions { }`
lets you add field rules to an existing permissions object without re-specifying
the other conditions (verified in `UpdateRestrictions.kt`):

```kotlin
val tightenedPermissions = basePermissions.withAdditionalUpdateRestrictions {
    it.internalNotes.cannotBeModified()
}
```

## Putting it all together with modelInfo

The `modelInfo(...)` function on a `Runtime<Database>` is where you assemble
all three layers per caller:

```kotlin
object PostServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    // Raw table access for UserAuth.fetch; ModelRestEndpoints uses postInfo below.
    val userTable = database.registerTable<User>("User")

    val postInfo = database.modelInfo(
        auth = UserAuth.require() or AuthRequirement.None,
        tableName = "Post",
        permissions = {
            // authOrNull is Authentication<User>? — null for unauthenticated callers
            val user: User? = authOrNull?.fetch()
            val isAdmin = user?.isSuperUser == true

            val selfCondition: Condition<Post> =
                condition { it.authorId eqNn user?._id }
            val adminOrSelf: Condition<Post> =
                if (isAdmin) Condition.Always else selfCondition

            ModelPermissions(
                create = adminOrSelf,
                read = if (isAdmin) Condition.Always
                       else condition { it.published eq true } or selfCondition,
                readMask = mask {
                    // Visitors (not the author) see internalNotes redacted to ""
                    it.internalNotes.mask("", unless = adminOrSelf)
                },
                update = adminOrSelf,
                updateRestrictions = updateRestrictions {
                    it.authorId.cannotBeModified()
                },
                delete = adminOrSelf,
            )
        }
    )

    val posts = path.path("posts") include ModelRestEndpoints(postInfo)
}
```

**The call chain** (verified in `ModelInfo.kt` and `ModelPermissionsTable.kt`):

1. Each incoming request calls `info.table(auth)` on the `ModelInfo`.
2. `modelInfo` evaluates your `permissions` lambda with the caller's
   `AuthAccess<User>` as receiver — here you call `authOrNull?.fetch()` and
   compute all conditions.
3. The resulting `ModelPermissions<Post>` is passed to
   `table.withPermissions(permissions)`, producing a `ModelPermissionsTable<Post>`.
4. Every database call from `ModelRestEndpoints` goes through that secured table.
   The security is enforced at the storage layer, not in the endpoint handler.

## ModelRestEndpoints — generated CRUD with built-in enforcement

`ModelRestEndpoints(info)` generates the following endpoints automatically,
all of which respect the permissions declared in `modelInfo`:

| Endpoint | Method | Path | Auth scope |
|---|---|---|---|
| List | GET | `/posts` | `read` |
| Query | POST | `/posts/query` | `read` |
| Query Partial | POST | `/posts/query-partial` | `read` |
| Get | GET | `/posts/{id}` | `read` |
| Insert | POST | `/posts` | `create` |
| Insert Bulk | POST | `/posts/bulk` | `create` |
| Upsert | POST | `/posts/{id}` | `create` + `update` |
| Replace | PUT | `/posts/{id}` | `update` |
| Bulk Replace | PUT | `/posts/bulk` | `update` |
| Modify (patch) | PATCH | `/posts/{id}` | `update` |
| Modify with Diff | PATCH | `/posts/{id}/delta` | `update` |
| Bulk Modify | PATCH | `/posts/bulk` | `update` |
| Delete | DELETE | `/posts/{id}` | `delete` |
| Bulk Delete | DELETE | `/posts/bulk` | `delete` |
| Permissions | GET | `/posts/_permissions_` | `read` |

The `_permissions_` endpoint is particularly useful for clients: it returns the
serialized `ModelPermissions<T>` for the current caller, allowing the UI to
show or hide buttons based on what the user is actually allowed to do.

You add a websocket update feed with the `+` operator (verified in
`BlogEndpoints.kt`):

```kotlin
val posts = path.path("posts") include
    ModelRestEndpoints(postInfo) + ModelRestUpdatesWebsocket(postInfo)
```

## Manual endpoints

When you write a custom endpoint that needs to respect the same permissions,
call `info.table(this)` inside the handler — `this` is an `AuthAccess<User>`:

```kotlin
val publishedFeed = path.path("feed").get bind ApiHttpHandler(
    summary = "Public feed",
    auth = UserAuth.require() or AuthRequirement.None,
    errorCases = emptyList(),
    implementation = { _: Unit ->
        // info.table(this) returns a ModelPermissionsTable enforcing all rules
        // for the current caller.  No further access checks needed.
        postInfo.table(this).find(Condition.Always).toList()
    }
)
```

Do not call `postTable()` directly in secured endpoints — that
bypasses all permission enforcement.

## Blog post example (from demo)

The live demo (`demo/src/main/kotlin/.../BlogEndpoints.kt`) shows a minimal
real-world example:

```kotlin
object BlogEndpoints : ServerBuilder() {
    val info = Server.database.modelInfo(
        auth = Server.UserAuth.require(),
        tableName = "BlogPost",
        permissions = {
            if (auth.fetch().isSuperUser)
                ModelPermissions.allowAll<BlogPost>()
            else
                ModelPermissions(
                    read = Condition.Always,
                    manage = Condition.Never,  // secondary constructor
                )
        },
    )
    val rest = path.path("rest") include ModelRestEndpoints(info) + ModelRestUpdatesWebsocket(info)
}
```

Superusers get full access; everyone else can read but cannot create, modify, or
delete.  The `manage` secondary constructor of `ModelPermissions` sets `create`,
`update`, and `delete` to the same condition.

The `User` permissions in `demo/src/main/kotlin/.../Server.kt` show field-level
masking in production use — `isSuperUser`, `phone`, and `hashedPassword` are
masked to safe sentinel values for any caller who is neither the user themselves
nor an admin:

```kotlin
// From Server.kt (verified)
ModelPermissions(
    create = Condition.Never,
    read = Condition.Always,
    readMask = mask {
        it.hashedPassword.mask(value = "", unless = self or admin)
        it.phone.mask(value = null, unless = self or admin)
        it.isSuperUser.mask(value = false, unless = self or admin)
    },
    update = self or admin,
    delete = self or admin,
)
```

## Security notes

- **Default-deny**: `ModelPermissions()` with no arguments denies all
  operations.  Open only what is needed.
- **Conditions are evaluated per-request**: the `permissions` lambda runs on
  every request, so you can make decisions based on the caller's live state
  (e.g. a freshly-loaded role or subscription status).
- **Mask bypasses are impossible through the normal table API**: sorting and
  filtering by masked fields is automatically blocked by `ModelPermissionsTable`
  to prevent inference attacks.  A caller cannot learn a field's value by
  querying for it, sorting on it, or aggregating it — those operations receive
  the "reveal" condition added to the query, restricting results to only rows
  where the field would actually be visible.
- **System-level table access**: code that needs unrestricted access (background
  jobs, admin tooling) should call `info.table()` (no argument), which skips
  the permission layer entirely.  Never expose this path to external callers.
- **UpdateRestrictions blocks at the database level**: if a forbidden field is
  included in a `Modification`, the framework produces `Condition.Never`, which
  causes the update to match zero rows.  The modification is not silently
  stripped — it is rejected outright.

## What's Next

- **Typed endpoints** — for endpoints outside the CRUD pattern, see
  [Typed Endpoints](../guide/typed-endpoints.md) for how to require auth and
  read the current user inside custom handlers.
- **Authentication setup** — see [Authentication & Sessions](../guide/auth.md)
  for how `PrincipalType`, proofs, and session tokens are established before
  permissions are evaluated.
- **Database DSL** — see [Database & the Query DSL](../guide/database.md) for
  the full set of `condition { }` and `modification { }` operators used in
  permission rules and update restrictions.
