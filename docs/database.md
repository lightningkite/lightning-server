# Database

Lightning Server contains a database abstraction that enables you to build applications without worrying about exactly which database will be used.  It is abstracted over both NoSQL and SQL databases.

## Declaring the need for a database

Add a setting as follows:

```kotlin
object Server {
    //...
    val database = setting(name = "database", default = DatabaseSettings())
    //...
}
```

## Declaring a model

Next we need to declare a model.  All models are serializable via `kotlinx.serialization`, and need the additional annotation `@DatabaseModel` which we'll discuss later.  To make `UUID`s serializable, we must also place `@file:UseContextualSerialization(UUID::class)` at the top of the file. 

It is strongly recommended you define the primary key yourself by making the class implement `HasId<T>`.

```kotlin
@file:UseContextualSerialization(UUID::class, Instant::class)

//...

@Serializable
@DatabaseModel
data class Post(
    @Contextual override val _id: UUID = UUID.randomUUID(),
    val title: String,
    val poster: String,
    val body: String,
    val privateNotes: String? = null,
    val updatedAt: Instant = Instant.now()
) : HasId<UUID>
```

## Accessing the database

You can now access a table of these objects like this:

```kotlin
collection.insertOne(Post(title = "Test", poster = "joseph@lightningkite.com", body = "Example"))
collection.find(condition { it.title eq "Test" }).toList()
collection.updateOne(
    condition { it.title eq "Test" },
    modification { it.title assign "Test Post" }
)
collection.deleteMany(condition { it.always })
collection.count()
```

## Conditions and Modifications

There are many conditions and modifications available.

To write a condition or modification, simply use  the `condition { it }` and `modification { it }` starters like you see above.


## Adding Signals

Signals occur when a change is made to the database.

You can wrap a collection with actions that will occur on those changes.

```kotlin
val collection = Server.database().collection<Post>()
    .postNewValue { value ->
        println("$value was inserted or updated in the database.")
    }
    .postDelete { value ->
        println("$value was removed from the database.")
    }
```

There are many more signals available than the above, and there are intercepting options available as well:

```kotlin
val collection = Server.database().collection<Post>()
    .interceptCreate { it.copy(title = it.title + " (Unverified)") }
    .interceptChange { m ->
        modification {
            add(m)
            it.updatedAt assign Instant.now()
        }
    }
```

## Permissions

You can also restrict the usage of a collection to a certain set of permissions.

```kotlin
val currentUser = "joseph@lightningkite.com"
val collection = Server.database().collection<Post>()
    .withPermissions(
        ModelPermissions(
            create = condition { it.always },
            read = condition { it.always },
            readMask = mask {
                it.privateNotes.maskedTo(null).unless(condition { it.poster eq currentUser })
            },
            update = condition { it.poster eq currentUser },
            updateRestrictions = updateRestrictions {
                it.updatedAt.cannotBeModified()
            },
            delete = condition { it.poster eq currentUser },
        )
    )
```

Creating views of databases like this is incredibly useful for centralizing rules about what users can and cannot do.

Later, we'll even show you how to use this to automatically generate REST endpoints with proper permissions.

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
  "database": { "url": "ram-unsafe-persist://path-to-folder" }
}
```

#### In-Memory + Preset from JSON file

```json5
// settings.json
{
  "database": { "url": "ram-preload://path-to-folder" }
}
```

#### In-Memory + Store to JSON File

```json5
// settings.json
{
  "database": { "url": "ram-unsafe-persist://path-to-folder" }
}
```

### MongoDB

```kotlin
// Server.kt
object Server: ServerPathGroup(ServerPath.root) {
    // Adds MongoDB to the possible database loaders
    init { MongoDatabase }
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

Useful for running on a local machine for testing.  Downloads and runs a copy of Mongo on the machine with the database files stored at the given path.

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

**WARNING** - Support is not considered ready for production.  If you wish to use this, reach out to us and we'll polish it off.

Most things work, but `Map` modifications do not.

```kotlin
// Server.kt
object Server: ServerPathGroup(ServerPath.root) {
    // Adds MongoDB to the possible database loaders
    init { PostgresDatabase }
}
```


```json5
// settings.json
{
  // Normal PostgreSQL connection string
  "database": { "url": "postgresql://YourUserName:YourPassword@YourHostname:5432/YourDatabaseName" }
}
```