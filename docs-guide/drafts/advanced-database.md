> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Advanced Database

This chapter extends [Database & the Query DSL](database.md), which covers
model definition, basic CRUD, and the `condition {}`/`modification {}` DSL.
Read that chapter first.  Here you will learn how to sort and paginate large
result sets, run aggregations, declare database indexes, issue bulk writes, and
react to table changes with interceptor hooks.

## Imports

All examples in this chapter use the following imports:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlin.time.*
import kotlin.uuid.*
```

## Example Model

The examples below use a `Post` model that extends the one from the basic
chapter.  The extra `viewCount` field gives a numeric target for aggregations,
and the `updatedAt` field gives a sortable timestamp:

```kotlin
// Illustrative — not a drift-checked sample region.
@Serializable
@GenerateDataClassPaths
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val author: String,
    val body: String,
    val viewCount: Int = 0,
    val updatedAt: Instant = Clock.System.now(),
) : HasId<Uuid>
```

## Sort & Pagination

`Table.find()` accepts four parameters beyond the condition:

```kotlin
suspend fun find(
    condition: Condition<Model>,
    orderBy: List<SortPart<Model>> = listOf(),
    skip: Int = 0,
    limit: Int = Int.MAX_VALUE,
    maxQueryMs: Long = 15_000,
): Flow<Model>
```

`orderBy` is a list of `SortPart` values.  Each `SortPart` names a field path
and a direction:

```kotlin
// Illustrative.
val posts = database().table<Post>()

// Ten most-recently updated posts.
val recent = posts.find(
    condition = Condition.Always,
    orderBy = listOf(SortPart(Post.path.updatedAt, ascending = false)),
    limit = 10,
).toList()

// Next page — skip the first ten.
val page2 = posts.find(
    condition = Condition.Always,
    orderBy = listOf(SortPart(Post.path.updatedAt, ascending = false)),
    skip = 10,
    limit = 10,
).toList()

// Sort by author ascending, then by title descending within each author.
val byAuthor = posts.find(
    condition = Condition.Always,
    orderBy = listOf(
        SortPart(Post.path.author, ascending = true),
        SortPart(Post.path.title, ascending = false),
    ),
).toList()

// Case-insensitive sort — ignoreCase = true only applies to String fields.
val caseInsensitive = posts.find(
    condition = Condition.Always,
    orderBy = listOf(SortPart(Post.path.title, ascending = true, ignoreCase = true)),
).toList()
```

Key points:

- Without `orderBy`, result order is database-dependent and may vary between
  backends or across queries.  Always supply `orderBy` when the order matters.
- `skip`/`limit` pagination is simple to implement but becomes slow for large
  offsets on backends that scan from the beginning.  For high-cardinality tables
  consider filtering by `_id` range instead.
- `maxQueryMs` is a server-side timeout.  The default (15 seconds) is generous;
  narrow it for user-facing endpoints.

## Aggregations

`Table` exposes four aggregation operations.

### count

Count the number of matching documents:

```kotlin
// Illustrative.
val table = database().table<Post>()

// Total number of posts.
val total: Int = table.count()

// Posts from a specific author.
val alicePosts: Int = table.count(condition { it.author eq "alice@example.com" })
```

### aggregate

Compute a single statistic over a numeric field:

```kotlin
// Illustrative.
// Aggregate enum values: Sum, Average, StandardDeviationSample, StandardDeviationPopulation
val totalViews: Double? = table.aggregate(
    aggregate = Aggregate.Sum,
    condition = Condition.Always,
    property = Post.path.viewCount,
)

val avgViews: Double? = table.aggregate(
    aggregate = Aggregate.Average,
    condition = condition { it.author eq "alice@example.com" },
    property = Post.path.viewCount,
)
```

Returns `Double?` — `null` when no documents match the condition.

### groupCount

Count documents broken down by a field value:

```kotlin
// Illustrative.
// Returns Map<String, Int> — author email -> post count.
val countByAuthor: Map<String, Int> = table.groupCount(
    condition = Condition.Always,
    groupBy = Post.path.author,
)
```

### groupAggregate

Combine grouping and a numeric aggregation:

```kotlin
// Illustrative.
// Returns Map<String, Double?> — author email -> total view count.
val viewsByAuthor: Map<String, Double?> = table.groupAggregate(
    aggregate = Aggregate.Sum,
    condition = Condition.Always,
    groupBy = Post.path.author,
    property = Post.path.viewCount,
)
```

> **Backend note:** All four aggregation methods are implemented natively on
> MongoDB and via SQL `GROUP BY` / aggregate functions on PostgreSQL.  The
> in-memory (`"ram"`) backend computes them in Kotlin by scanning all matching
> documents — equivalent results, higher memory use on large collections.

## Indexes

Declare indexes as annotations on your model.  Backends read these at startup
and ensure the required indexes exist in the database.

### @Index — single-field index

Applied to a property:

```kotlin
// Illustrative.
@Serializable
@GenerateDataClassPaths
data class Post(
    override val _id: Uuid = Uuid.random(),
    @Index                           // plain B-tree index on title
    val title: String,
    @Index(unique = IndexUniqueness.Unique)   // unique constraint on slug
    val slug: String,
    val author: String,
    val body: String,
    val viewCount: Int = 0,
    val updatedAt: Instant = Clock.System.now(),
) : HasId<Uuid>
```

`IndexUniqueness` values:

| Value | Effect |
|---|---|
| `NotUnique` (default) | Standard B-tree index; duplicate values allowed |
| `Unique` | Unique constraint; insert fails if value already exists |
| `UniqueNullSparse` | Unique only among non-null values; nulls are not indexed |

### @IndexSet — multi-field (compound) index

Applied to the class:

```kotlin
// Illustrative.
@Serializable
@GenerateDataClassPaths
@IndexSet(fields = ["author", "updatedAt"])          // compound index
@IndexSet(fields = ["author", "slug"], unique = IndexUniqueness.Unique)  // unique compound
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val slug: String,
    val author: String,
    val body: String,
    val viewCount: Int = 0,
    val updatedAt: Instant = Clock.System.now(),
) : HasId<Uuid>
```

Field names in `@IndexSet` must match the serialized field names (usually the
Kotlin property names, unless you have `@SerialName` overrides).

### @TextIndex — full-text search

Applied to the class; marks fields for full-text search in backends that
support it (MongoDB `$text`):

```kotlin
// Illustrative.
@Serializable
@GenerateDataClassPaths
@TextIndex(fields = ["title", "body"])
data class Post(/* ... */) : HasId<Uuid>
```

> **Backend note:** `@TextIndex` is recognized by MongoDB.  PostgreSQL uses
> `tsvector` columns instead.  The in-memory backend ignores text indexes
> entirely.  Backends that do not support a particular index type will log a
> warning and continue.

## Bulk Writes

### Bulk insert

`insert()` (the primitive method) accepts an `Iterable<Model>` and returns the
list of stored documents.  The `insertMany` convenience extension wraps it:

```kotlin
// Illustrative.
val table = database().table<Post>()

val newPosts = listOf(
    Post(title = "First",  author = "alice@example.com", body = "..."),
    Post(title = "Second", author = "alice@example.com", body = "..."),
    Post(title = "Third",  author = "bob@example.com",   body = "..."),
)
val stored: List<Post> = table.insertMany(newPosts)
```

All backends that support batching will insert these in a single round trip.

### updateMany

Apply a modification to every document matching a condition:

```kotlin
// Illustrative.
// Bump the viewCount on all of Alice's posts.
val updatedCount: Int = table.updateManyIgnoringResult(
    condition { it.author eq "alice@example.com" },
    modification { it.viewCount += 1 },
)

// If you need the before/after values of every changed document:
val changes: CollectionChanges<Post> = table.updateMany(
    condition { it.author eq "alice@example.com" },
    modification { it.viewCount += 1 },
)
// changes.changes is List<EntryChange<Post>>
// Each EntryChange has .old and .new — both may be null (null old = insert, null new = delete).
```

`CollectionChanges<T>` wraps `List<EntryChange<T>>`.  Each `EntryChange` holds
the document before (`old`) and after (`new`) the operation.

### deleteMany

Remove all documents matching a condition:

```kotlin
// Illustrative.
// Remove all posts by a deleted author.
val deletedCount: Int = table.deleteManyIgnoringOld(
    condition { it.author eq "alice@example.com" },
)

// Or retrieve the deleted documents:
val deletedPosts: List<Post> = table.deleteMany(
    condition { it.author eq "alice@example.com" },
)
```

## Upsert

`upsertOne` either inserts (if no document matches the condition) or applies a
modification (if one does).  You supply both the modification and the model to
insert on miss:

```kotlin
// Illustrative.
// Insert a post, or bump its viewCount if the slug already exists.
val change: EntryChange<Post> = table.upsertOne(
    condition = condition { it.slug eq "hello-world" },
    modification = modification { it.viewCount += 1 },
    model = Post(
        title = "Hello World",
        slug = "hello-world",
        author = "alice@example.com",
        body = "...",
    ),
)
// change.old == null  → was inserted
// change.old != null  → was updated; change.new holds the new state
```

The `upsertOneById` convenience extension targets a document by its `_id`:

```kotlin
// Illustrative.
val change = table.upsertOneById(
    id = existingId,
    model = Post(
        _id = existingId,
        title = "Updated title",
        author = "alice@example.com",
        body = "...",
    ),
)
```

## Table Interceptors and Change Hooks

`Table` can be wrapped with interceptor functions that fire before or after
write operations.  This is the primary mechanism for running side effects
(cache invalidation, audit logging, sending notifications) alongside database
writes without coupling them into your endpoint implementations.

All interceptor functions are extension functions on `Table<Model>` and return
a new `Table<Model>` that wraps the original.  Call them when you first obtain
your table reference, usually inside a lazy property or a helper:

```kotlin
// Illustrative — setting this up in a helper property.
fun notesTableWithHooks(): Table<Note> =
    database().table<Note>()
        .postCreate { note ->
            // Runs after every successful insert or upsert-that-inserted.
            println("Note created: ${note._id}")
        }
        .postDelete { note ->
            // Runs after every successful delete (deleteOne / deleteMany).
            println("Note deleted: ${note._id}")
        }
        .postChange { old, new ->
            // Runs after every successful update where both old and new are known.
            // updateOneIgnoringResult does NOT trigger this — use updateOne instead
            // if you need the change intercepted.
            println("Note changed: ${old._id}")
        }
```

### Available interceptors

| Function | Fires when |
|---|---|
| `postCreate(onCreate: (Model) -> Unit)` | After any insert or upsert-that-inserted |
| `postDelete(onDelete: (Model) -> Unit)` | After any delete |
| `postChange(changed: (Model, Model) -> Unit)` | After any update where old and new are both available |
| `postNewValue(changed: (Model) -> Unit)` | After any write that produces a new stored value |
| `postRawChanges(changed: (List<EntryChange<Model>>) -> Unit)` | After any write; receives raw `EntryChange` list |
| `withChangeListener(listener: (CollectionChanges<Model>) -> Unit)` | After any write; receives `CollectionChanges` |
| `interceptCreate(interceptor: (Model) -> Model)` | Before insert/upsert — returns the modified model to store |
| `interceptModification(interceptor: (Modification<Model>) -> Modification<Model>)` | Before update/upsert — returns the modified operation |
| `interceptDelete(onDelete: (Model) -> Unit)` | Before delete — receives the document about to be removed |

> **Important:** Interceptors that fire *after* the write (`postCreate`,
> `postDelete`, `postChange`, `postNewValue`) only receive the document when
> the underlying operation returns it.  Operations with `IgnoringResult` in the
> name skip fetching the old/new value for performance; calling them through an
> interceptor wrapper forces the wrapped table to use the non-ignoring variant
> so the interceptor gets the data it needs.  This means using these interceptors
> on high-throughput tables has a real cost — only add them when the side effect
> justifies it.

### Using `withChangeListener` for fan-out

`withChangeListener` is useful when you need to forward every table change to
another system — a PubSub channel, a websocket topic, or an audit log:

```kotlin
// Illustrative.
val table = database().table<Post>()
    .withChangeListener { changes ->
        for (change in changes.changes) {
            // Forward to a PubSub channel so other instances know.
            pubsub().get<EntryChange<Post>>("post-changes").emit(change)
        }
    }
```

## What's Next

- **PubSub** — publish table-change events across multiple server instances for
  real-time fan-out.  See [PubSub](pubsub.md).
- **WebSocket push** — subscribe WebSocket connections to a topic and push
  table-change events to clients.  See [WebSockets](../guide/websockets.md).
- **Model REST endpoints** — generate a full CRUD API with permissions from a
  single `modelInfo` declaration.  See the drafts directory.
