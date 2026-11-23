# Database

Lightning Server stores models through Service Abstractions' database layer: one type-safe API over MongoDB,
PostgreSQL, the in-memory `ram` backend, and others.  Queries are Kotlin expressions checked at compile time, and
switching backends is a settings change.

## Minimal example

<!-- sample: com/lightningkite/lightningserver/guide/samples/DatabaseSamples.kt#db-imports -->
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
import com.lightningkite.services.database.optics.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.*
```

A model is a data class annotated `@Queryable` that implements `HasId`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/DatabaseSamples.kt#note-model -->
```kotlin
@Queryable
data class Note(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val body: String = "",
    val tags: Set<String> = setOf(),
    val views: Int = 0,
    val archivedAt: Instant? = null,
) : HasId<Uuid>
```

`@Queryable` makes the class `@Serializable` and has the KSP processor generate a field for each property
(`Note.title`, `Note.tags`, ...).  Those fields are what queries are written with.  The processor must be on the
module's KSP classpath:

```kotlin
// build.gradle.kts
plugins {
    alias(libs.plugins.ksp)
}
dependencies {
    ksp(sa.database.processor)
}
```

Declare a database setting and register each table once, by name:

<!-- sample: com/lightningkite/lightningserver/guide/samples/DatabaseSamples.kt#note-server -->
```kotlin
object NoteDbServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())

    val notes = database.registerTable<Note>("Note")

    val list = path.path("notes").get bind ApiHttpHandler(
        summary = "List notes that are not archived",
        auth = noAuth,
        implementation = { _: Unit ->
            notes().find(Note.archivedAt eq null).toList()
        }
    )

    val create = path.path("notes").post bind ApiHttpHandler(
        summary = "Create a note",
        auth = noAuth,
        successCode = HttpStatus.Created,
        implementation = { input: Note ->
            notes().insertOne(input)
        }
    )
}
```

`registerTable` returns an accessor: call it (`notes()`) inside a handler, task, or test to get the live
`Table<Note>`.  Registering also creates a [pre-deploy task](tasks.md) that creates the collection and its
indexes, so you don't prepare tables yourself.  Pick the table name deliberately: it is the collection name in the
database, and renaming it orphans existing data.

## Reading and writing

<!-- sample: com/lightningkite/lightningserver/guide/samples/DatabaseSamples.kt#db-crud -->
```kotlin
fun databaseTest() = NoteDbServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    val notes = NoteDbServer.notes()

    val shopping = notes.insertOne(Note(title = "Shopping", body = "Eggs, milk", tags = setOf("home")))
    notes.insert(listOf(Note(title = "Ideas"), Note(title = "Old", archivedAt = Clock.System.now())))

    check(notes.find(Note.tags.any { it eq "home" }).toList() == listOf(shopping))
    check(notes.get(shopping._id) == shopping)
    check(notes.count(Note.archivedAt eq null) == 2)

    notes.updateOneById(shopping._id, Modification {
        it.body assign "Eggs, milk, bread"
        it.views += 1
    })
    check(notes.get(shopping._id)!!.views == 1)

    notes.deleteMany(Note.archivedAt neq null)
    check(notes.count() == 2)

    check(NoteDbServer.list.test(null, Unit).size == 2)
}
```

`find` returns a `Flow`: `toList()` collects it, `collect { }` streams it.  The `ram` backend gives every test run a
fresh, empty database; see [Testing](testing.md).

| Operation | Returns |
|---|---|
| `insertOne(model)`, `insert(models)` | the stored models |
| `find(condition, orderBy, skip, limit)` | `Flow<T>` |
| `findOne(condition)`, `get(id)`, `getMany(ids)` | `T?` / `List<T>` |
| `count(condition)` | `Int` |
| `updateOne(condition, modification)`, `updateOneById(id, modification)` | `EntryChange<T>` (`old`, `new`) |
| `updateMany(condition, modification)` | `CollectionChanges<T>` |
| `replaceOneById(id, model)`, `upsertOneById(id, model)` | `EntryChange<T>` |
| `deleteOne(condition)`, `deleteMany(condition)` | the deleted models |
| `deleteOneById(id)` | `Boolean` |

Every write also has an `...IgnoringResult` / `...IgnoringOld` variant (`updateOneIgnoringResult`,
`updateManyIgnoringResult`, `deleteManyIgnoringOld`, ...) that returns only a `Boolean` or a count.  Use it when you
don't need the changed rows; it saves the database a read.

## Conditions

A condition is built from generated fields and infix operators:

<!-- sample: com/lightningkite/lightningserver/guide/samples/DatabaseSamples.kt#db-conditions -->
```kotlin
val noteConditions: List<Condition<Note>> = listOf(
    Note.title eq "Shopping",
    Note.views gte 10,
    Note.title inside listOf("Shopping", "Ideas"),
    Note.body contains "milk",                           // case-insensitive by default
    Note.tags.any { it eq "home" },
    Note.archivedAt.notNull lt Clock.System.now(),       // false when archivedAt is null
    (Note.views gt 0) and (Note.archivedAt eq null),
    (Note.title eq "A") or (Note.title eq "B"),
    !Note.tags.any { it eq "spam" },
    Condition { (it.views gt 0) and (it.title neq "") }, // lambda form
    Condition.Always,
)
```

`Condition { it.field ... }` is the same thing written as a lambda over the model; it's handy when a condition
names several fields.  Nested fields chain (`User.address.city`); `.notNull` steps into a nullable value and
`.elements` into a collection.  Those two live in `com.lightningkite.services.database.optics`, so import it.

| Operator | Meaning |
|---|---|
| `eq`, `neq` | equal, not equal |
| `eqNn` | equal, or `Condition.Never` when the value is `null` (handy with optional users) |
| `gt`, `gte`, `lt`, `lte` | comparisons on `Comparable` values |
| `inside`, `notInside` | value is (not) in a collection |
| `contains` | substring; `contains(text, ignoreCase = false)` for case-sensitive |
| `any { }`, `all { }` | some / every element of a list or set matches |
| `and`, `or`, `!` | combine; `Condition.Always`, `Condition.Never` are the constants |
| `allSet`, `anySet`, `allClear`, `anyClear` | bit flags on an `Int` field |

Wrapper types expose their contents: `User.email.raw contains "@example.com"`, `Box.height.meters gt 1.8`.

## Modifications

<!-- sample: com/lightningkite/lightningserver/guide/samples/DatabaseSamples.kt#db-modifications -->
```kotlin
val tidyUp: Modification<Note> = Modification {
    it.title assign "Groceries"
    it.views += 1
    it.tags.removeAll { tag -> tag eq "home" }
    it.archivedAt assign null
}
```

Several statements in one `Modification { }` apply together, atomically for that row.  Change each field once per
modification: some backends reject two operators on the same field.

| Operator | Applies to |
|---|---|
| `assign value` | any field |
| `+= n`, `*= n` | numbers |
| `coerceAtLeast v`, `coerceAtMost v` | `Comparable` values |
| `+= item`, `+= items`, `addAll items` | lists and sets |
| `-= item`, `-= items`, `removeAll items`, `removeAll { condition }` | lists and sets |
| `forEach { }`, `forEachIf(condition, modification)` | modify each element of a list or set |
| `it.address.notNull.city assign "Provo"` | nested fields; no change when `address` is null |

Strings, maps, and dropping the first or last element have no special operator: `assign` the new value.

Conditions and modifications are serializable values, which is how clients send them to
[generated REST endpoints](model-rest.md).  You can also evaluate them locally: `condition(note)` returns a
`Boolean`, `modification(note)` returns the modified copy.

## Switching backends

The setting is a URL.  In `settings.json`:

```json
{
  "database": "mongodb+srv://user:password@cluster0.example.mongodb.net/myapp?retryWrites=true"
}
```

| URL | Backend | Module, and the class to reference |
|---|---|---|
| `ram` | In memory, empty at start | built in |
| `ram-preload://path` | In memory, loaded from a JSON file or folder | built in |
| `json-files://path` | In memory, persisted to JSON files | `sa.database.jsonfile` |
| `mongodb://...`, `mongodb+srv://...` | MongoDB | `sa.database.mongodb`, `MongoDatabase` |
| `mongodb-test` | Throwaway local MongoDB | `sa.database.mongodb`, `MongoDatabase` |
| `mongodb-file://path` | Local MongoDB stored at `path` | `sa.database.mongodb`, `MongoDatabase` |
| `postgresql://...` | PostgreSQL | `sa.database.postgres`, `PostgresDatabase` |
| `delay://300/ram` | Any backend with artificial latency (ms), for testing UIs | built in |

A backend registers its URL schemes when its class is loaded, so reference it once in your server:

```kotlin
object Server : ServerBuilder() {
    init { MongoDatabase }
    val database = setting("database", Database.Settings())
}
```

Service Abstractions also has Cassandra / AWS Keyspaces (`cassandra`, `keyspaces`) and SQL (`sql-h2`, `sql-mysql`,
`sql-mariadb`, `sql-sqlite`) backends.  See [Settings](settings.md) for how settings files are loaded and
[Services](services.md) for how URL-configured services work in general.

## Gotchas

- **One `registerTable` per table name.**  Registering the same name twice for the same type returns the same
  registration; for a different type it throws `DuplicateRegistrationException`.
- **Tables are prepared by pre-deploy tasks.**  A deployment that never runs them has no indexes; see
  [Tasks](tasks.md).
- **No transactions.**  Single-row writes are atomic; `updateMany`, `deleteMany`, and multi-model `insert` are not.
  For compare-and-swap, see [Advanced database](advanced-database.md#compare-and-swap).
- **`EntryChange.new` is computed**, by applying the modification to the row that was read.  Only `old` is what the
  database actually held.
- **Unordered by default.**  Without `orderBy`, result order depends on the backend.
- **Import `kotlin.time.Instant` by name in model files**, not `kotlin.time.*`.  The generated field code
  star-imports `kotlinx.datetime`, and two star-imported `Instant`s make the generated code fail to compile.

## Next

- [Advanced database](advanced-database.md): sorting, paging, aggregates, indexes, text search, signals.
- [Model REST endpoints](model-rest.md): CRUD endpoints for a model without writing handlers.
- [Permissions](permissions.md): who may read and write which rows and fields.
- [Admin panel](meta-admin.md): the `@Admin...`, `@Description`, and `@References` annotations.
