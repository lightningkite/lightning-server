# Database & the Query DSL

Lightning Server provides a type-safe DSL for querying and updating documents.
Every query is expressed as a Kotlin lambda — the compiler rejects invalid field
references at build time, and refactoring a model field name automatically
updates all queries.

## Before You Begin

> **Before you begin — KSP plugin required.**  `@GenerateDataClassPaths` is
> processed at build time by the KSP plugin.  Add these two lines to your
> module's `build.gradle.kts` before using this chapter's examples:
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

<!-- sample: com/lightningkite/lightningserver/guide/samples/DatabaseSamples.kt#db-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.PreDeployTask
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

## Defining a Model

Database models must implement `HasId<ID>` and be annotated with
`@Serializable` and `@GenerateDataClassPaths`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/DatabaseSamples.kt#note-model -->
```kotlin
@Serializable
@GenerateDataClassPaths
data class Note(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val body: String,
) : HasId<Uuid>
```

`@GenerateDataClassPaths` is processed by the KSP plugin at build time.
It generates a `Note.path` companion-object extension with a typed field for
every property — `Note.path.title`, `Note.path._id`, `Note.path.body`.
These path objects are what `condition {}` and `modification {}` use to build
type-safe queries.

The `_id` field is the primary key.  Defaulting it to `Uuid.random()` means
callers can create a `Note` without supplying an id and get a unique one for
free.

## Declaring the Database Setting

Declare `val database = setting("database", Database.Settings())` in your
`ServerBuilder`.  The default URL is `"ram"` — an in-process, zero-config
database suitable for tests and local development:

<!-- sample: com/lightningkite/lightningserver/guide/samples/DatabaseSamples.kt#note-server -->
```kotlin
object NoteDbServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    // A table definition names the table and its type. Prepare it once per deploy (before serving),
    // then access it at runtime with database().table(notes).
    val notes = DatabaseTableDefinition<Note>()
    val prepareNotes = path.path("prepare") bind PreDeployTask { database().prepare(notes) }

    // GET /notes — list all notes
    val list = path.path("notes").get bind ApiHttpHandler(
        summary = "List all notes",
        auth = noAuth,
        successCode = HttpStatus.OK,
        errorCases = emptyList(),
        implementation = { _: Unit ->
            database().table(notes).find(Condition.Always).toList()
        }
    )

    // POST /notes — create a new note
    val create = path.path("notes").post bind ApiHttpHandler(
        summary = "Create a note",
        auth = noAuth,
        successCode = HttpStatus.Created,
        errorCases = emptyList(),
        implementation = { input: Note ->
            database().table(notes).insertOne(input)
        }
    )
}
```

Key points:

- **`database().table(notes)`** — `database()` resolves the live service from
  the current `ServerRuntime` (only callable inside a handler).  `.table(notes)`
  returns a `Table<Note>` keyed on the serializer, so one table per model type.
- **`Condition.Always`** — matches every document.  Use `condition { }` to
  narrow the query (shown in the test below).
- **`find()` returns a `Flow<Note>`** — call `.toList()` to collect all results
  eagerly, or `.collect { }` to stream them one at a time without loading the
  full result set into memory.
- **`insertOne(input)`** — inserts the document and returns the stored copy
  (which may differ if the backend applies defaults or transformations).

## Testing with the In-Memory Database

The `settings` lambda overrides the `database` setting to the `"ram"` backend,
giving each test run a fresh, empty in-process database.  The same lambda is
used in all previous chapters for the cache setting.  As in previous chapters,
the first argument to `ApiHttpHandler.test()` is the auth token; `null` is
correct for `noAuth` endpoints.  The infix `set` extension comes from
`com.lightningkite.lightningserver.settings` and is already in the imports list.

> To wrap these examples in a test class, annotate your test methods with `@Test` — see [Testing Your Server](testing.md) for the complete `@Test` + `testBlocking` pattern.

<!-- sample: com/lightningkite/lightningserver/guide/samples/DatabaseSamples.kt#db-test -->
```kotlin
fun databaseTest() = NoteDbServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    // Insert two notes
    val first = NoteDbServer.create.test(null, Note(title = "Shopping", body = "Eggs, milk"))
    val second = NoteDbServer.create.test(null, Note(title = "Ideas", body = "Start a blog"))

    // List returns both
    val all = NoteDbServer.list.test(null, Unit)
    check(all.size == 2)

    // Direct table access for condition / modification / delete
    val table = NoteDbServer.database().table(NoteDbServer.notes)

    // condition { } builds a type-safe query using generated path extensions
    val found = table.find(condition { it.title eq "Shopping" }).toList()
    check(found.size == 1)
    check(found[0]._id == first!!._id)

    // modification { } builds a type-safe update
    table.updateOneIgnoringResult(
        condition { it._id eq first._id },
        modification { it.body assign "Eggs, milk, bread" }
    )
    val updated = table.get(first._id)!!
    check(updated.body == "Eggs, milk, bread")

    // delete
    table.deleteOneIgnoringOld(condition { it._id eq second!!._id })
    check(table.count() == 1)
}
```

### Query DSL mechanics

**`condition { it.title eq "Shopping" }`**
- `it` is a `DataClassPath<Note, Note>` — a typed handle on the whole document.
- `.title` is a generated extension that narrows the path to `DataClassPath<Note, String>`.
- `eq "Shopping"` builds a `Condition<Note>` that matches documents where the
  title field equals `"Shopping"`.
- Other operators: `neq`, `lt`, `lte`, `gt`, `gte`, `inside` (IN list), `and`,
  `or`, `not`.

**`modification { it.body assign "Eggs, milk, bread" }`**
- `assign` replaces the field value.
- Other operators: `+= 1` (numeric increment), `+= listOf(...)` (list append),
  `coerceAtLeast`, `coerceAtMost`.

**`*IgnoringResult` variants**
- `updateOneIgnoringResult` and `deleteOneIgnoringOld` skip fetching the old
  or new document.  Use them when you don't need to inspect what changed —
  they are faster because the database does not need to return data.
- The non-ignoring variants (`updateOne`, `deleteOne`) return `EntryChange<Note>`
  or `Model?` so you can log or react to the changed values.

**`table.get(id)`**
- Looks up a single document by `_id`.  Returns `null` if not found.

**`table.count(condition)`**
- Returns the number of documents matching a condition.  Call `count()` with no
  argument (or `Condition.Always`) to count all documents.

## Switching Backends

Because the database is declared as a service-abstracted setting, switching from
the in-memory backend to MongoDB or PostgreSQL is a configuration change, not a
code change.  The generated `settings.json` for a server using `NoteDbServer`
would look like:

```json
{
  "database": "ram"
}
```

Change `"ram"` to `"mongodb://host/dbname"` (or `"postgres://..."`) and restart
— no Kotlin changes required.

> **Note:** This JSON block is illustrative — it is generated output from the
> first run of your server with `loadFromFile()`, not a drift-checked sample
> region.

## What's Next

- **Authentication** — add `auth = authOptions<User>()` to your endpoints and
  enforce access control in handler lambdas.  Chapter 6 covers the proofs-based
  session system.
- **Query parameters and pagination** — `find(condition, orderBy, skip, limit)`
  supports cursor-style pagination; wire query string parameters via the typed
  endpoint API.
- **ModelRestEndpoints** — for standard CRUD APIs, `ModelRestEndpoints` generates
  list/get/create/update/delete endpoints from a single `modelInfo` declaration,
  including OpenAPI documentation and permission enforcement.
