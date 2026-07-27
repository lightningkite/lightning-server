# Database

Last updated January 2025 (`version-5`)

Lightning Server contains a database abstraction that enables you to build applications without worrying about exactly
which database will be used. It is abstracted over both NoSQL and SQL databases.

## Declaring the need for a database

Add a setting as follows:

```kotlin
object Server : ServerBuilder() {
    //...
    val database = setting("database", Database.Settings())
    //...
}
```

## Declaring a model

Next we need to declare a model. All models are serializable via `kotlinx.serialization`, and need the additional
annotation `@GenerateDataClassPaths` for the query DSL to work properly.

It is strongly recommended you define the primary key yourself by making the class implement `HasId<T>`.

```kotlin
import kotlinx.serialization.*
import com.lightningkite.services.database.HasId
import com.lightningkite.services.data.GenerateDataClassPaths
import kotlin.uuid.Uuid
import kotlin.time.Instant
import kotlin.time.Clock

@Serializable
@GenerateDataClassPaths
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val author: String,
    val body: String,
    val privateNotes: String? = null,
    val updatedAt: Instant = Clock.System.now()
) : HasId<Uuid>
```

## Accessing the database

Register each table once in your `ServerBuilder` with `registerTable`. A single call defines the table,
registers it (so it can be enumerated later), and creates the once-per-deploy [pre-deploy task](tasks.md)
that prepares its collection/indexes — you don't wire any of that up yourself. **Create one per model and
share it** — don't call `registerTable` again for the same table:

```kotlin
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val postTable = database.registerTable<Post>("Post")   // define + register + prepare, once
}
```

The value it returns is a runtime accessor — **invoke it inside a handler** to get the live `Table<Post>`:

```kotlin
val posts = postTable()

// Insert a new post
posts.insertOne(Post(
    title = "Test",
    author = "joseph@lightningkite.com",
    body = "Example"
))

// Query posts
posts.find(condition { it.title eq "Test" }).toList()

// Update a post
posts.updateOne(
    condition { it.title eq "Test" },
    modification { it.title assign "Test Post" }
)

// Delete posts
posts.deleteMany(condition { it.always })

// Count posts
posts.count()
```

## Working with ModelInfo

For more advanced use cases with permissions and authentication, use `ModelInfo`:

```kotlin
val postInfo = database.modelInfo(
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
```

## Conditions and Modifications

There are many conditions and modifications available.

To write a condition or modification, use the `condition { it }` and `modification { it }` DSL:

```kotlin
import kotlin.time.Duration.Companion.days

// Conditions
condition { it.title eq "Test" }
condition { it.author eq "joe@example.com" }
condition { it.updatedAt gt Clock.System.now() - 1.days }
condition { (it.title eq "Test") and (it.author eq "joe@example.com") }

// Modifications
modification { it.title assign "New Title" }
modification { it.updatedAt assign Clock.System.now() }
modification { it.body.append(" - Updated") }
```

### Common Condition Operations

- `eq` - Equals
- `neq` - Not equals
- `gt` - Greater than
- `gte` - Greater than or equal
- `lt` - Less than
- `lte` - Less than or equal
- `inside` - Value is in a collection
- `notInside` - Value is not in a collection
- `contains` - String contains (case-sensitive)
- `containsIgnoreCase` - String contains (case-insensitive)
- `and` - Logical AND
- `or` - Logical OR
- `always` - Always true
- `never` - Never true

### Common Modification Operations

- `assign` - Set a value
- `plus` - Add to a number
- `times` - Multiply a number
- `append` - Append to a string
- `listAppend` - Add items to a list
- `listRemove` - Remove items from a list based on condition
- `setAppend` - Add items to a set
- `setRemove` - Remove items from a set based on condition

## Adding Signals

Signals occur when a change is made to the database.

You can wrap a collection with actions that will occur on those changes:

```kotlin
val collection = postTable()
    .interceptCreate { value ->
        println("About to insert: $value")
        value.copy(title = value.title + " (New)")
    }
    .postCreate { value ->
        println("$value was inserted in the database.")
    }
    .postChange { value ->
        println("$value was updated in the database.")
    }
    .postDelete { value ->
        println("$value was removed from the database.")
    }
```

Available interceptors and signals:

- `interceptCreate` - Modify a value before creation
- `interceptChange` - Modify a modification before application
- `postCreate` - Called after successful creation
- `postChange` - Called after successful update
- `postDelete` - Called after successful deletion
- `postNewValue` - Called after creation or update

## Available Backends

### In-Memory

#### In-Memory (for testing)

```json5
// settings.json
{
  "database": { "url": "ram" }
}
```

#### In-Memory + Store to JSON File

```json5
// settings.json
{
  "database": { "url": "json://path-to-folder" }
}
```

### MongoDB

```kotlin
// Server.kt
object Server: ServerBuilder() {
    // Adds MongoDB to the possible database loaders
    init { MongoDatabase }

    val database = setting("database", Database.Settings())
}
```

#### MongoDB Standard

```json5
// settings.json
{
  // Standard MongoDB connection string - parameters are allowed
  "database": { "url": "mongodb://myDBReader:D1fficultP%40ssw0rd@mongodb0.example.com:27017/default" }
}
```

#### MongoDB SRV

```json5
// settings.json
{
  // Standard MongoDB SRV connection string - parameters are allowed
  "database": { "url": "mongodb+srv://myDBReader:password@mongodb0.example.com:27017/default" }
}
```

#### MongoDB Run Locally

Useful for running on a local machine for testing. Downloads and runs a copy of Mongo on the machine with the database
files stored at the given path.

```json5
// settings.json
{
  "database": { "url": "mongodb-file://path-to-folder" }
}
```

#### MongoDB Run Locally Temporarily

Good for unit tests.

```json5
// settings.json
{
  "database": { "url": "mongodb-test" }
}
```

### PostgreSQL

**WARNING** - Support is not considered ready for production. If you wish to use this, reach out to us and we'll polish
it off.

Most things work, but `Map` modifications do not.

```kotlin
// Server.kt
object Server: ServerBuilder() {
    // Adds PostgreSQL to the possible database loaders
    init { PostgresDatabase }

    val database = setting("database", Database.Settings())
}
```

```json5
// settings.json
{
  // Normal PostgreSQL connection string
  "database": { "url": "postgresql://YourUserName:YourPassword@YourHostname:5432/YourDatabaseName" }
}
```

## Model Annotations

Lightning Server provides several annotations to enhance your models for use with the admin panel and documentation:

```kotlin
@Serializable
@GenerateDataClassPaths
@AdminTableColumns(["title", "author", "updatedAt"])
@Description("A blog post in the system")
data class Post(
    override val _id: Uuid = Uuid.random(),
    @Description("The post title") val title: String,
    @Description("Email of the author") val author: String,
    @Multiline @MimeType("text/html") val body: String,
    @AdminHidden val privateNotes: String? = null,
    val updatedAt: Instant = Clock.System.now()
) : HasId<Uuid>
```

Available annotations:

- `@GenerateDataClassPaths` - Required for query DSL
- `@AdminTableColumns([...])` - Columns to show in admin table view
- `@Description("...")` - Description for documentation
- `@AdminHidden` - Hide field from admin panel
- `@Multiline` - Render as textarea in admin panel
- `@MimeType("...")` - Specify content type for rich fields
- `@References(Model::class)` - Indicates field references another model
- `@MultipleReferences(Model::class)` - Indicates field references multiple models

NEXT: [Cache](cache.md)
