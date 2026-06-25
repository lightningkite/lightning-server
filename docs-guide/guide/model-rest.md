# Model REST Endpoints (Auto-CRUD)

`ModelRestEndpoints` is Lightning Server's flagship Django-like feature: given a
database model and a permissions policy, it auto-generates a full suite of typed
CRUD, query, and aggregation endpoints — complete with OpenAPI documentation and
SDK generation — without writing a single handler by hand.

## Before You Begin

> **KSP plugin required.**  `@GenerateDataClassPaths` is processed at build time.
> Add these two lines to your module's `build.gradle.kts`:
>
> ```kotlin
> // build.gradle.kts
> plugins {
>     alias(libs.plugins.ksp)
> }
> dependencies {
>     ksp(libs.services.database.processor)
> }
> ```

## Imports

All examples in this chapter use the following imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ModelRestSamples.kt#mr-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlinx.serialization.*
import kotlin.uuid.*
```

## Defining a Model

A model must implement `HasId<ID>` and carry two annotations:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ModelRestSamples.kt#mr-model -->
```kotlin
@Serializable
@GenerateDataClassPaths
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val author: String,
    val body: String,
) : HasId<Uuid>
```

- **`HasId<Uuid>`** — declares the primary-key type.  Any `Comparable` type works:
  `Uuid`, `String`, `Int`, `Long`.
- **`@Serializable`** — required for kotlinx.serialization; every field must be
  serializable.
- **`@GenerateDataClassPaths`** — KSP generates `Post.path.title`, `Post.path.author`,
  etc. — the typed handles used by `condition {}` and `modification {}` queries.
  See [Database & the Query DSL](database.md) for how these work.
- **Default `_id`** — defaulting to `Uuid.random()` means callers can omit the ID;
  the framework assigns one automatically on insert.

## Wiring Up ModelRestEndpoints

`modelInfo` is the bridge between your model, your database setting, and your
permissions policy.  It is declared as a property on your `ServerBuilder`, and
then passed to `ModelRestEndpoints`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ModelRestSamples.kt#mr-server -->
```kotlin
object PostRestServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    // modelInfo<USER, Model, ID>:
    //   USER = HasId<*>? — the noAuth "user" type (no authenticated caller)
    //   Model = Post, the document type stored in the database
    //   ID = Uuid, the primary-key type
    val postInfo = database.modelInfo<HasId<*>?, Post, Uuid>(
        auth = noAuth,
        permissions = { ModelPermissions.allowAll() }
    )

    // include mounts all generated endpoints under /posts
    val posts = path.path("posts") include ModelRestEndpoints(postInfo)
}
```

Key points:

- **`modelInfo<USER, Model, ID>`** — the three type parameters must be explicit
  when using `noAuth` so the compiler knows which table type to bind to.  With
  an authenticated `PrincipalType`, they are usually inferred.
- **`auth`** — an `AuthRequirement<USER>`.  `noAuth` means no credential is
  required.  Replace with `UserAuth.require()` (see the _Auth-Required Setup_
  section below) to lock down the endpoints.
- **`permissions`** — a suspend lambda returning `ModelPermissions<T>`.  The
  lambda runs inside each request; `auth` (and `authOrNull` for optional auth)
  are available as properties on the receiver.  `ModelPermissions.allowAll()`
  is the simplest policy — it permits every operation for every caller.
- **`include` vs `module`** — `include` mounts the endpoints; `module` (from
  `com.lightningkite.lightningserver.typed`) does the same but also registers
  SDK metadata so the generated client SDK gets a named interface class
  (e.g. `PostRestEndpoints`).  For most guide examples `include` is sufficient.
- **Accessing endpoints** — the return value of `include` is the
  `ModelRestEndpoints` instance itself.  Store it (`val posts = ...`) so you
  can reference `posts.insert`, `posts.detail`, etc. from tests or other
  endpoints.

## Generated Routes

`ModelRestEndpoints` mounts on the path you choose and generates these routes:

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

All sub-paths are relative to the path you pass to `include` (or `module`).  If
you mount at `/posts`, the detail endpoint is `GET /posts/{id}`.

`groupCount2` and `groupAggregate2` JSON-encode the group key instead of calling
`.toString()` on it — important when the key is not a plain string.

### Understanding the Input Types

**`Query<T>`** — packages a `condition`, `orderBy` list, `skip`, and `limit` into
one object.  `list` accepts it via query parameters (limited to simple
conditions); `query` accepts it as a POST body (full condition DSL supported).

**`Modification<T>`** — a serialisable field-level update built with the
`modification { }` builder.  `modify` applies it to one document by ID;
`bulkModify` wraps it in `MassModification<T>` (condition + modification) and
applies it to many documents at once.

**`Partial<T>`** — a sparse object where only the provided fields are present.
`modifySimple` converts it to a `Modification<T>` internally, making it easy for
clients that want to send only the changed fields.

**`EntryChange<T>`** — holds `old: T?` and `new: T?`; `modifyWithDiff` returns
both values so callers can inspect before and after.

## Testing the Generated Endpoints

> To wrap these examples in a test class, annotate your test methods with
> `@Test` — see [Testing Your Server](testing.md) for the full pattern.

<!-- sample: com/lightningkite/lightningserver/guide/samples/ModelRestSamples.kt#mr-test -->
```kotlin
fun modelRestTest() = PostRestServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    // Insert a post — returns the stored copy
    val post = PostRestServer.posts.insert.test(null, Post(title = "Hello", author = "alice", body = "First post"))
    check(post.title == "Hello")

    // Retrieve by ID: first argument is the path arg (ID), second is auth, third is the body
    val fetched = PostRestServer.posts.detail.test(post._id, null, Unit)
    check(fetched._id == post._id)
    check(fetched.title == "Hello")

    // List uses a Query; Condition.Always matches every document
    val all = PostRestServer.posts.list.test(null, Query(Condition.Always))
    check(all.size == 1)

    // Modify: apply a field-level update and receive the new document
    val modified = PostRestServer.posts.modify.test(
        post._id, null,
        modification { it.title assign "Updated Title" }
    )
    check(modified.title == "Updated Title")
    check(modified.author == "alice")  // unchanged

    // Delete by ID
    PostRestServer.posts.deleteItem.test(post._id, null, Unit)

    // Count confirms the document is gone
    val remaining = PostRestServer.posts.count.test(null, Condition.Always)
    check(remaining == 0)
}
```

### How `.test()` arguments map to endpoints

- **Path-arg endpoints** (`detail`, `modify`, `replace`, `upsert`, `deleteItem`,
  `modifyWithDiff`, `modifySimple`) — the first argument is the ID (path
  parameter), the second is the auth token (`null` for `noAuth`), the third is
  the request body.
- **No-path-arg endpoints** (`insert`, `list`, `query`, `count`, `bulkDelete`,
  etc.) — the first argument is the auth token, the second is the request body.

## Permissions in Depth

### The ModelPermissions Data Class

`ModelPermissions<T>` is a data class with six fields — all default to
`Condition.Never`, making the constructor **whitelist by default**:

```kotlin
// Illustrative — not drift-checked.
ModelPermissions(
    create = Condition<T>,             // which items the user may insert (post-insert check)
    read = Condition<T>,               // which items the user may read
    readMask = Mask<T>,                // field-level read masking (default: no masking)
    update = Condition<T>,             // which items the user may modify
    updateRestrictions = UpdateRestrictions<T>,  // which fields may be modified
    delete = Condition<T>,             // which items the user may delete
)
```

Forgetting a field defaults it to `Condition.Never` — a safe failure mode that
blocks access rather than accidentally opening it.

**Shorthand constructors:**

```kotlin
// Illustrative — not drift-checked.
// read + manage (create/update/delete use the same condition)
ModelPermissions(read = everyone, manage = admin)

// all operations use the same condition
ModelPermissions(all = admin)

// full whitelist — only for trusted internal callers
ModelPermissions.allowAll<Post>()
```

### Auth-Required Setup

Replace `noAuth` with `UserAuth.require()` to lock down the endpoints.  The
`permissions` lambda then receives an `AuthAccess<UserProfile>` receiver, giving
access to `auth.fetch()` for the full user object:

```kotlin
// Illustrative — not drift-checked.
// Requires UserAuth and UserProfile from the Authentication chapter.
object AuthPostServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    val postInfo = database.modelInfo<UserProfile, Post, Uuid>(
        auth = UserAuth.require(),
        permissions = {
            val user = auth.fetch()
            ModelPermissions(
                create = condition { it.author eq user.email },
                read = Condition.Always,
                update = condition { it.author eq user.email },
                delete = condition { it.author eq user.email },
            )
        }
    )

    val posts = path.path("posts") include ModelRestEndpoints(postInfo)
}
```

`auth.fetch()` loads the full `UserProfile` from the database; the result is
cached on the `Authentication` object for the lifetime of the request, so
repeated calls within one request are free.

### Optional Auth

Allow unauthenticated callers for reads while requiring a session for writes:

```kotlin
// Illustrative — not drift-checked.
val postInfo = database.modelInfo<UserProfile, Post, Uuid>(
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

`authOrNull` is non-null only when a valid token was presented.  Use
`authOrNull?.fetch()` instead of `auth.fetch()` — calling the latter when no
token is present throws a `NullPointerException`.

### Read Masking

`readMask` hides or replaces field values before the document is returned to the
client:

```kotlin
// Illustrative — not drift-checked.
val postInfo = database.modelInfo<UserProfile, Post, Uuid>(
    auth = UserAuth.require() or AuthRequirement.None,
    permissions = {
        val user = authOrNull?.fetch()
        val self: Condition<Post> = condition { it.author eq (user?.email ?: "") }
        ModelPermissions(
            read = Condition.Always,
            readMask = mask {
                // Replace body with "" unless the caller is the author
                it.body.mask(value = "", unless = self)
            },
            manage = Condition.Never,
        )
    }
)
```

`mask { }` builds a `Mask<T>`.  The `DataClassPath<T, V>.mask(value, unless)`
extension sets the field to `value` whenever the `unless` condition is false.

### Update Restrictions

`updateRestrictions` prevents certain fields from being modified regardless of
the `update` condition:

```kotlin
// Illustrative — not drift-checked.
ModelPermissions(
    create = Condition.Always,
    read = Condition.Always,
    update = Condition.Always,
    delete = Condition.Always,
    updateRestrictions = updateRestrictions {
        it._id.cannotBeModified()       // primary key is immutable
        it.author.cannotBeModified()    // author cannot be changed after creation
    }
)
```

`updateRestrictions { }` is in **blacklist mode** by default — every field is
modifiable unless explicitly restricted.

## Auth Scopes

`ModelInfo.Scopes` maps endpoint categories to auth subscopes, allowing session
tokens to be restricted to a subset of operations:

| Scope | Endpoints |
|---|---|
| `ModelInfo.Scopes.read` | `list`, `query`, `queryPartial`, `detail`, `count`, `groupCount`, `groupCount2`, `aggregate`, `groupAggregate`, `groupAggregate2`, `permissions` |
| `ModelInfo.Scopes.create` | `insert`, `insertBulk` |
| `ModelInfo.Scopes.create` + `update` | `upsert` |
| `ModelInfo.Scopes.update` | `replace`, `bulkReplace`, `modify`, `modifyWithDiff`, `modifySimple`, `bulkModify` |
| `ModelInfo.Scopes.delete` | `deleteItem`, `bulkDelete` |

See [Proof & Session Authentication](proof-session.md) for how to issue
read-only or restricted tokens.

## SDK and Documentation

Every endpoint created by `ModelRestEndpoints` is a typed `ApiHttpHandler`.
When you use `module` (rather than bare `include`), the group participates in
`FetcherSdk` and `TypescriptFetcherSdk` generation, and the generated client
gets a named interface class (e.g. `PostRestEndpoints`).

The live docs at `/meta/docs` display all model endpoints with their
input/output schemas, and `/meta/openapi` exports a full OpenAPI 3.0 spec.
See [Typed Endpoints, Errors & SDK Generation](typed-endpoints.md) for details.

## What's Next

- **Realtime updates** — push insert/update/delete deltas to subscribed clients
  via WebSocket: see the Realtime Model Sync chapter.
- **Typed endpoints** — understand how `ApiHttpHandler` metadata (summaries,
  error cases, examples) flows into the generated SDK:
  [Typed Endpoints, Errors & SDK Generation](typed-endpoints.md).
- **Database DSL** — `condition {}` and `modification {}` reference:
  [Database & the Query DSL](database.md).
- **Authentication** — setting up `PrincipalType` and `UserAuth.require()`:
  [Authentication & Sessions](auth.md).
