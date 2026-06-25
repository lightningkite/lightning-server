> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Model REST Endpoints (Auto-CRUD)

`ModelRestEndpoints` is Lightning Server's flagship Django-like feature: given a
database model and a permissions policy, it auto-generates a full suite of
typed CRUD, query, and aggregation endpoints — with OpenAPI documentation and
SDK generation — without writing a single handler by hand.

## Before You Begin

> **KSP plugin required.**  `@GenerateDataClassPaths` is processed at build
> time.  Add these two lines to your module's `build.gradle.kts`:
>
> ```kotlin
> plugins {
>     alias(libs.plugins.ksp)
> }
> dependencies {
>     ksp(libs.services.database.processor)
> }
> ```

## Imports

All examples in this chapter use the following imports:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebsocket.Companion.plus
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlinx.serialization.*
import kotlin.uuid.*
import kotlin.time.*
```

## Defining a Model

A model must implement `HasId<ID>` and carry two annotations:

```kotlin
@Serializable
@GenerateDataClassPaths
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val author: String,
    val body: String,
    val createdAt: Instant = Clock.System.now(),
) : HasId<Uuid>
```

- **`HasId<Uuid>`** — declares the primary-key type.  Any `Comparable` type is
  supported: `Uuid`, `String`, `Int`, `Long`.
- **`@Serializable`** — required for kotlinx.serialization support; every field
  must be serializable.
- **`@GenerateDataClassPaths`** — KSP generates `Post.path.title`,
  `Post.path.author`, etc., the typed handles used by `condition {}` and
  `modification {}` queries.
- **Default `_id`** — defaulting to `Uuid.random()` lets callers omit the ID;
  the server assigns one automatically.

## Creating a ModelInfo

`ModelInfo` is the glue between your model, your database setting, and your
permission policy.  Create one with the `modelInfo` extension on a
`Runtime<Database>` (i.e., a `ServerSetting<Database.Settings, Database>`):

```kotlin
object PostServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    // modelInfo<USER, Model, ID> captures auth requirements and per-user permissions.
    // The USER type parameter matches PrincipalType.require()'s inferred type.
    val postInfo = database.modelInfo(
        auth = UserAuth.require(),   // require an authenticated User
        permissions = {
            // `this` is an AuthAccess<User>; call auth.fetch() to get the User.
            val user = auth.fetch()
            ModelPermissions(
                create = condition { it.author eq user.email },
                read = Condition.Always,
                update = condition { it.author eq user.email },
                delete = condition { it.author eq user.email },
            )
        }
    )

    val posts = path.path("posts").path("rest") module ModelRestEndpoints(postInfo)
}
```

Key points:

- **`auth`** — an `AuthRequirement<USER>`.  Use `UserAuth.require()` for
  mandatory authentication, `UserAuth.require() or AuthRequirement.None` for
  optional authentication (see the _Optional auth_ section below).
- **`permissions`** — a suspend lambda that receives `AuthAccess<USER>` as its
  context.  Inside, `auth` is the validated `Authentication<USER>` and
  `auth.fetch()` resolves the full user object from the database.
  `authOrNull?.fetch()` is the nullable variant for optional-auth policies.
- **`module`** vs **`include`** — `module` (from the `typed` module) is
  preferred over bare `include` because it registers SDK and documentation
  metadata alongside the endpoints.  Both wire the routes; only `module`
  participates in `FetcherSdk` / `TypescriptFetcherSdk` generation.
- **`tableName`** — defaults to the model's short class name (e.g. `"Post"`).
  Override with `tableName = "custom_name"` if needed.
- **`subscope`** — by default wraps auth in a `Subscope(tableName.lowercase())`
  so auth token scoping works at the model level.  Pass `subscope = null` to
  disable.

## Generated Routes

`ModelRestEndpoints` mounts on the path you choose and generates the following
routes (verified against
`typed/src/main/kotlin/…/typed/ModelRestEndpoints.kt`):

| Property | Method | Sub-path | Input | Output |
|---|---|---|---|---|
| `permissions` | GET | `/_permissions_` | — | `ModelPermissions<T>` |
| `list` | GET | `/` | `Query<T>` (query param) | `List<T>` |
| `query` | POST | `/query` | `Query<T>` | `List<T>` |
| `queryPartial` | POST | `/query-partial` | `QueryPartial<T>` | `List<Partial<T>>` |
| `detail` | GET | `/{id}` | — | `T` |
| `insert` | POST | `/` | `T` | `T` |
| `insertBulk` | POST | `/bulk` | `List<T>` | `List<T>` |
| `upsert` | POST | `/{id}` | `T` | `T` |
| `replace` | PUT | `/{id}` | `T` | `T` |
| `bulkReplace` | PUT | `/bulk` | `List<T>` | `List<T>` |
| `modify` | PATCH | `/{id}` | `Modification<T>` | `T` |
| `modifyWithDiff` | PATCH | `/{id}/delta` | `Modification<T>` | `EntryChange<T>` |
| `modifySimple` | PATCH | `/{id}/simplified` | `Partial<T>` | `T` |
| `bulkModify` | PATCH | `/bulk` | `MassModification<T>` | `Int` |
| `deleteItem` | DELETE | `/{id}` | — | — |
| `bulkDelete` | POST | `/bulk-delete` | `Condition<T>` | `Int` |
| `count` | POST | `/count` | `Condition<T>` | `Int` |
| `groupCount` | POST | `/group-count` | `GroupCountQuery<T>` | `Map<String, Int>` |
| `groupCount2` | POST | `/group-count-2` | `GroupCountQuery<T>` | `Map<String, Int>` |
| `aggregate` | POST | `/aggregate` | `AggregateQuery<T>` | `Double?` |
| `groupAggregate` | POST | `/group-aggregate` | `GroupAggregateQuery<T>` | `Map<String, Double?>` |
| `groupAggregate2` | POST | `/group-aggregate-2` | `GroupAggregateQuery<T>` | `Map<String, Double?>` |

`group-count-2` and `group-aggregate-2` are variants that JSON-encode the group
key rather than calling `toString()` on it, which matters for non-string key
types.

> The sub-paths above are illustrative — all routes are relative to the path
> you pass to `module`/`include`.  If you mount at `/posts/rest`, then the list
> endpoint is `GET /posts/rest` and the detail endpoint is
> `GET /posts/rest/{id}`.

### Understanding Query Types

**`Query<T>`** — packages a `condition`, `orderBy` list, `skip`, and `limit`
into a single object.  The `list` endpoint accepts it via query parameters
(limited to simple conditions); `query` accepts it as a POST body (supports the
full condition DSL).

**`Modification<T>`** — a serialisable representation of a field-level update
built with `modification { }`.  The `modify` endpoint applies it to one document
by ID; `bulkModify` wraps it in `MassModification<T>` (condition + modification)
and applies it to many documents at once.

**`Partial<T>`** — a sparse object where only the fields that were provided are
present; `modifySimple` converts it to a `Modification<T>` internally, making it
easy for clients that just want to send changed fields without constructing a
full modification DSL.

**`EntryChange<T>`** — holds `old: T?` and `new: T?`; returned by
`modifyWithDiff` so callers can inspect before and after.

## Auth Scopes

Each endpoint category maps to a `ModelInfo.Scope`:

| Scope | Endpoints |
|---|---|
| `ModelInfo.Scopes.read` | `list`, `query`, `queryPartial`, `detail`, `count`, `groupCount`, `groupCount2`, `aggregate`, `groupAggregate`, `groupAggregate2`, `permissions` |
| `ModelInfo.Scopes.create` | `insert`, `insertBulk` |
| `ModelInfo.Scopes.create` + `update` | `upsert` |
| `ModelInfo.Scopes.update` | `replace`, `bulkReplace`, `modify`, `modifyWithDiff`, `modifySimple`, `bulkModify` |
| `ModelInfo.Scopes.delete` | `deleteItem`, `bulkDelete` |

Auth token scoping (via the sessions system) can selectively grant only read
access, only create access, etc., to a token without changing the permissions
logic.

## Permissions in Depth

### Full Permissions

`ModelPermissions<T>` is a data class with six fields:

```kotlin
ModelPermissions(
    create = Condition<T>,             // which items the user may insert (checked post-insert)
    read = Condition<T>,               // which items the user may read
    readMask = Mask<T>,                // field-level read masking
    update = Condition<T>,             // which items the user may modify
    updateRestrictions = UpdateRestrictions<T>,  // which fields may be modified
    delete = Condition<T>,             // which items the user may delete
)
```

All fields default to `Condition.Never` — the constructor is **whitelist by
default**, so forgetting a field is safe.

**Shorthand constructors:**

```kotlin
// read + manage (create/update/delete use the same condition)
ModelPermissions(read = everyone, manage = admin)

// all operations use the same condition
ModelPermissions(all = admin)

// full whitelist — use only for trusted internal callers
ModelPermissions.allowAll<Post>()
```

### Read Masking

`readMask` hides or replaces field values before the document is sent to the
client:

```kotlin
val info = database.modelInfo(
    auth = UserAuth.require() or AuthRequirement.None,
    permissions = {
        val user = authOrNull?.fetch()
        val self: Condition<Post> = condition { it.author eq (user?.email ?: "") }
        ModelPermissions(
            read = Condition.Always,
            readMask = mask {
                // Hide the body unless the caller is the author
                it.body.mask(value = "", unless = self)
            },
            manage = Condition.Never,
        )
    }
)
```

`mask { }` builds a `Mask<T>`.  Inside the lambda, the `DataClassPath<T, V>.mask(value, unless)` extension hides a field — it is replaced by `value` whenever `unless` is false.  The alternative `maskedTo(value) unless condition` reads more naturally in some situations.

### Update Restrictions

`updateRestrictions` prevents certain fields from being modified regardless of
the `update` condition:

```kotlin
val info = database.modelInfo(
    auth = UserAuth.require(),
    permissions = {
        ModelPermissions(
            create = Condition.Always,
            read = Condition.Always,
            update = Condition.Always,
            delete = Condition.Always,
            updateRestrictions = updateRestrictions {
                it._id.cannotBeModified()           // ID is immutable
                it.createdAt.cannotBeModified()     // creation date is immutable
                it.author requires (it.author eq auth.fetch().email) // only owner may change author
            }
        )
    }
)
```

`updateRestrictions { }` is in **blacklist mode** by default — every field is
modifiable unless explicitly restricted.  Pass `mode = UpdateRestrictions.Mode.Whitelist`
to flip to a whitelist where only explicitly named fields can change.

### Optional Auth

Allow unauthenticated callers for read operations while requiring authentication
for writes:

```kotlin
val info = database.modelInfo(
    auth = UserAuth.require() or AuthRequirement.None,
    permissions = {
        val user = authOrNull?.fetch()
        ModelPermissions(
            create = if (user != null) condition { it.author eq user.email } else Condition.Never,
            read = Condition.Always,
            update = if (user != null) condition { it.author eq user.email } else Condition.Never,
            delete = if (user != null) condition { it.author eq user.email } else Condition.Never,
        )
    }
)
```

`authOrNull` is non-null only when a valid session token was provided.  When
absent, `auth.fetch()` would throw — use `authOrNull?.fetch()` for optional-auth
policies.

## Accessing Endpoints Directly

Each generated endpoint is a named property on the `ModelRestEndpoints` instance.
Store the instance in a variable to reference endpoints from tests or internal
calls:

```kotlin
object PostServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val postInfo = database.modelInfo(
        auth = UserAuth.require(),
        permissions = { ModelPermissions.allowAll() }
    )
    // Store the ModelRestEndpoints so its typed endpoints are accessible.
    val postRest = ModelRestEndpoints(postInfo).also { path.path("posts").path("rest") module it }
}

// In tests or other endpoints:
// PostServer.postRest.insert
// PostServer.postRest.list
// PostServer.postRest.deleteItem
```

> The `also { }` pattern above is illustrative; idiomatic usage stores the
> `module` return value: `val postRest = path.path("posts").path("rest") module ModelRestEndpoints(postInfo)`.

## Demo Reference

The live demo server wires `ModelRestEndpoints` in two places:

- `demo/…/BlogEndpoints.kt` — combines `ModelRestEndpoints` and
  `ModelRestUpdatesWebsocket` with the `+` operator.
- `demo/…/Server.kt` (`val user`) — mounts `ModelRestEndpoints(userInfo)` for
  the `User` model.

Both are good references for real permission policies.

## SDK and Documentation

Every endpoint created by `ModelRestEndpoints` is a typed `ApiHttpHandler`.
When you call `module` (rather than bare `include`), the whole group participates
in `FetcherSdk` and `TypescriptFetcherSdk` generation, and the generated client
gets a dedicated interface class named after the model (e.g.
`PostRestEndpoints`).

The `MetaEndpoints` live docs at `/meta/docs` display all model endpoints with
their input/output schemas, and `/meta/openapi` exports a full OpenAPI 3.0 spec.
See [Typed Endpoints, Errors & SDK Generation](typed-endpoints.md) for details.

## What's Next

- **Realtime updates** — add a WebSocket endpoint that pushes insert/update/delete
  deltas to subscribed clients: [Realtime Model Sync](model-realtime.md).
- **Typed endpoints** — understand how `ApiHttpHandler` metadata (summaries,
  error cases, examples) flows into the generated SDK:
  [Typed Endpoints, Errors & SDK Generation](typed-endpoints.md).
- **Database DSL** — `condition { }` and `modification { }` reference:
  [Database & the Query DSL](database.md).
