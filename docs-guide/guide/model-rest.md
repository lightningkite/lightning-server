# Model REST endpoints

`ModelRestEndpoints` generates a typed CRUD, query, and aggregation API for a model from one `modelInfo`
declaration.  The endpoints enforce the model's [permissions](permissions.md), show up in the API docs, and get
client functions in the [generated SDKs](client-sdks.md).

## Minimal example

<!-- sample: com/lightningkite/lightningserver/guide/samples/ModelRestSamples.kt#mr-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlin.uuid.*
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/ModelRestSamples.kt#mr-model -->
```kotlin
@Queryable
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val author: String,
    val body: String,
) : HasId<Uuid>
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/ModelRestSamples.kt#mr-server -->
```kotlin
object PostRestServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())

    val postInfo = database.modelInfo<HasId<*>?, Post, Uuid>(
        auth = noAuth,
        tableName = "Post",
        permissions = { ModelPermissions.allowAll() },
    )

    val posts = path.path("posts") include ModelRestEndpoints(postInfo)
}
```

!!! warning
    `noAuth` with `ModelPermissions.allowAll()` lets anyone on the internet read and rewrite the table.  It keeps
    this example short; real models require auth and narrow permissions, as shown in [Permissions](permissions.md).

`modelInfo` registers the table (as [`registerTable`](database.md) does) and binds it to an auth requirement and a
permissions function.  Its type arguments are the user type, the model, and the id type; with a typed auth
requirement such as `UserAuth.require()`, you can usually let them be inferred.  `tableName` is required and is the
collection name in the database.

## Calling the endpoints

Each generated endpoint is a property on the `ModelRestEndpoints`, so tests (and other server code) call them
directly:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ModelRestSamples.kt#mr-test -->
```kotlin
fun modelRestTest() = PostRestServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    val posts = PostRestServer.posts

    val post = posts.insert.test(null, Post(title = "Hello", author = "alice", body = "First post"))

    val fetched = posts.detail.test(post._id, null, Unit)
    val page = posts.query.test(null, Query(Post.author eq "alice", limit = 20))
    val modified = posts.modify.test(post._id, null, Modification { it.title assign "Updated" })
    posts.deleteItem.test(post._id, null, Unit)
    val remaining = posts.count.test(null, Condition.Always)

    check(fetched == post)
    check(page == listOf(post))
    check(modified.title == "Updated" && modified.body == "First post")
    check(remaining == 0)
}
```

For endpoints with an `{id}` in the path, `test` takes the id first, then the auth (`null` here), then the body.
See [Testing](testing.md).

## Generated routes

Paths are relative to where you mount the endpoints (`/posts` above).

| Property | Method and path | Body | Returns |
|---|---|---|---|
| `list` | `GET /` | `Query<T>` as query parameters | `List<T>` |
| `query` | `POST /query` | `Query<T>` | `List<T>` |
| `queryPartial` | `POST /query-partial` | `QueryPartial<T>` | `List<Partial<T>>` |
| `textQuery` | `POST /text-query` | `TextIndexQuery<T>` | `List<ScoredResult<T>>` |
| `detail` | `GET /{id}` | | `T` |
| `insert` | `POST /` | `T` | `T` |
| `insertBulk` | `POST /bulk` | `List<T>` | `List<T>` |
| `upsert` | `POST /{id}` | `T` | `T` |
| `replace` | `PUT /{id}` | `T` | `T` |
| `bulkReplace` | `PUT /bulk` | `List<T>` | `List<T>` |
| `modify` | `PATCH /{id}` | `Modification<T>` | `T` |
| `modifyWithDiff` | `PATCH /{id}/delta` | `Modification<T>` | `EntryChange<T>` |
| `modifySimple` | `PATCH /{id}/simplified` | `Partial<T>` (only the fields to set) | `T` |
| `bulkModify` | `PATCH /bulk` | `MassModification<T>` (condition + modification) | `Int` changed |
| `deleteItem` | `DELETE /{id}` | | `Unit` |
| `bulkDelete` | `POST /bulk-delete` | `Condition<T>` | `Int` deleted |
| `count` | `POST /count` | `Condition<T>` | `Int` |
| `groupCount` | `POST /group-count` | `GroupCountQuery<T>` | `Map<String, Int>` |
| `aggregate` | `POST /aggregate` | `AggregateQuery<T>` | `Double?` |
| `groupAggregate` | `POST /group-aggregate` | `GroupAggregateQuery<T>` | `Map<String, Double?>` |
| `groupCount2`, `groupAggregate2` | `POST /group-count-2`, `/group-aggregate-2` | as above | group keys JSON-encoded instead of `toString()` |
| `permissions` | `GET /_permissions_` | | the caller's `ModelPermissions<T>` |
| `default` | `GET /_default_` | | a default `T` to start a create form from |

Conditions, modifications, and queries travel as JSON, so clients can ask any question the
[query DSL](database.md#conditions) can express; permissions decide what the answer contains.  `queryPartial`
always includes `_id` in the returned fields.

## Conditions and modifications as JSON

Clients that aren't using a generated SDK send conditions and modifications as nested single-key objects.  A field
name wraps a condition or modification on that field; an operator name holds its argument.  Field names are serial
names, and values use the model's own JSON encoding.  These examples use the `Note` model from
[Database](database.md).

Views at least 10, tagged `home`, title not containing "draft" (for `query`, `count`, `bulk-delete`, ...):

```json
{"And": [
  {"views": {"GreaterThanOrEqual": 10}},
  {"tags": {"CollectionAnyElements": {"Equal": "home"}}},
  {"Not": {"title": {"StringContains": {"value": "draft", "ignoreCase": true}}}}
]}
```

A nullable field is stepped into with `IfNotNull`, which is false when the value is null:

```json
{"archivedAt": {"IfNotNull": {"LessThan": "2026-01-01T00:00:00Z"}}}
```

Set the title, add one view, and remove the `home` tag (the body of `PATCH /{id}`, or the `modification` in
`bulkModify`'s `{"condition": ..., "modification": ...}`):

```json
{"Chain": [
  {"title": {"Assign": "Groceries"}},
  {"views": {"Increment": 1}},
  {"tags": {"SetRemoveInstances": ["home"]}}
]}
```

| Condition | Argument |
|---|---|
| `Always`, `Never` | `true` |
| `And`, `Or` | array of conditions |
| `Not` | a condition |
| `Equal`, `NotEqual` | a value (`null` allowed) |
| `Inside`, `NotInside` | array of values |
| `GreaterThan`, `GreaterThanOrEqual`, `LessThan`, `LessThanOrEqual` | a value |
| `StringContains` | `{"value": "...", "ignoreCase": true}` |
| `CollectionAnyElements`, `CollectionAllElements` | a condition on the element (lists and sets) |
| `IfNotNull` | a condition on the non-null value |

| Modification | Argument |
|---|---|
| `Assign` | a value |
| `Chain` | array of modifications, applied in order |
| `Increment`, `Multiply` | a number |
| `CoerceAtLeast`, `CoerceAtMost` | a value |
| `SetAppend`, `ListAppend` | array of items to add |
| `SetRemoveInstances`, `ListRemoveInstances` | array of items to remove |
| `SetRemove`, `ListRemove` | a condition; removes matching elements |
| `IfNotNull` | a modification on the non-null value |

## Errors

| Situation | Response |
|---|---|
| No row with that id, or the caller can't read it | 404 |
| An update or delete the caller isn't allowed to make | 404 (the row doesn't match the permitted condition) |
| An insert the caller isn't allowed to make | 403 "Value was not posted as requested." |
| A unique index would be violated | 400, detail `unique` |
| `text-query` on a model without `@TextIndex` | 400, detail `no-text-index` |

`insertBulk` silently drops the items the caller may not create and returns only the ones it inserted.

## Custom endpoints next to generated ones

Write your own endpoint against `info.table(this)`, which applies the same permissions for the current caller:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ModelRestSamples.kt#mr-custom -->
```kotlin
object PostStatsServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())

    val postInfo = database.modelInfo<HasId<*>?, Post, Uuid>(
        auth = noAuth,
        tableName = "Post",
        permissions = { ModelPermissions(read = Condition.Always, manage = Condition.Never) },
    )

    val posts = path.path("posts") include ModelRestEndpoints(postInfo)

    val postsPerAuthor = path.path("post-stats").get bind ApiHttpHandler(
        summary = "Count posts per author",
        auth = postInfo.auth,
        implementation = { _: Unit ->
            postInfo.table(this).groupCount(groupBy = Post.author)
        }
    )
}
```

The ways to get a table from a model info:

| Call | Permissions | [Signals](advanced-database.md#signals-and-interceptors) | Auditing |
|---|---|---|---|
| `info.table(this)` / `info.table(auth)` in a handler | the caller's | yes | yes |
| `info.table()` | none (system access) | yes | yes |
| `info.baseTable()` | none | no | yes |
| `info.dangerouslyDirectTable()` (opt-in `@UnauditedDatabaseAccess`) | none | no | no |

Use `info.table()` for background jobs and server-internal logic; never return its results to a caller unfiltered.

## Including vs. `module`

`include` mounts the endpoints.  `module` (from `com.lightningkite.lightningserver.typed.sdk`) does the same and also
gives the group its own interface in generated SDKs (`PostRestEndpoints`, from the table name):

```kotlin
val posts = path.path("posts") module ModelRestEndpoints(postInfo)
```

## Auth scopes

`modelInfo` narrows its `auth` to a subscope, by default the lowercased table name, and each endpoint narrows it
again by operation.  A token granted `post` can use every Post endpoint; one granted `post:read` only the reading
ones.  Tokens with the root scope (the default) pass everything.  Pass `subscope = null` to skip the table level.

| Scope (`ModelInfo.Scopes`) | Endpoints |
|---|---|
| `read` | `list`, `query`, `queryPartial`, `textQuery`, `detail`, `count`, the group/aggregate endpoints, `permissions`, `default` |
| `create` | `insert`, `insertBulk` |
| `create` and `update` | `upsert` |
| `update` | `replace`, `bulkReplace`, `modify`, `modifyWithDiff`, `modifySimple`, `bulkModify` |
| `delete` | `deleteItem`, `bulkDelete` |

See [Proof & session authentication](proof-session.md) for issuing scoped tokens.

## `modelInfo` parameters

| Parameter | Purpose |
|---|---|
| `auth` | who may call at all, e.g. `UserAuth.require()` or `UserAuth.require() or AuthRequirement.None` |
| `tableName` | collection name; required |
| `subscope` | scope prefix for the endpoints (default: lowercased table name) |
| `permissions` | `suspend AuthAccess<USER>.() -> ModelPermissions<T>`, run per request; see [Permissions](permissions.md) |
| `signals` | wraps the table for every access; see [Advanced database](advanced-database.md#signals-and-interceptors) |
| `systemAccess` | wraps only `info.table()` |
| `prePermissionsForUser`, `postPermissionsForUser` | wrap a caller's table outside / inside the permission layer |
| `log` | the auditing decorator |

## Next

- [Permissions](permissions.md): row rules, field masks, and update restrictions.
- [Realtime model updates](model-realtime.md): add a live-query websocket to these endpoints.
- [Client SDKs](client-sdks.md): calling the generated endpoints from Kotlin and TypeScript.
- [Bulk endpoints](bulk-endpoints.md): batching many calls into one request.
