# Database

**OUT OF DATE**

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

Next we need to declare a model.  All models are serializable via `kotlinx.serialization`, and need the additional annotation `@GenerateDataClassPaths` which we'll discuss later.  To make `UUID`s serializable, we must also place `@file:UseContextualSerialization(UUID::class)` at the top of the file. 

It is strongly recommended you define the primary key yourself by making the class implement `HasId<T>`.

```kotlin
@file:UseContextualSerialization(UUID::class, Instant::class)

//...

@Serializable
@GenerateDataClassPaths
data class Post(
    @Contextual override val _id: UUID = UUID.randomUUID(),
    val title: String,
    val poster: String,
    val body: String,
    val privateNotes: String? = null,
    val updatedAt: Instant = Instant.now(),
    val editable: Boolean = true
) : HasId<UUID>
```

## Accessing the database

To access a database, you must create a class that implements the `ModelInfoWithDefault` interface.
It is a generic interface that takes 3 type arguments. The first is the data class used for auth, the second is the model
represented in the database, and the third is the type used in the model acting as the id. Implementing the methods will give you access
to the collections Ex.

```kotlin
class PostInfo: ModelInfoWithDefault<User, Post, UUID> {

    override val authOptions: AuthOptions<User> = authOptions<User>()
    override val serialization: ModelSerializationInfo<Post, UUID> = ModelSerializationInfo()

    @RawCollection
    val rawCollection by lazy {
        // logCollections defined elsewhere in your server, maps collection name to the collection log
        logCollections.putIfAbsent(collectionName, database().collection("$collectionName-log"))
    }
    override fun baseCollection(): FieldCollection<Post> = rawCollection

    @OptIn(RawCollection::class)
    private val mainCollection: FieldCollection<Disclosure> by lazy {
        rawCollection
    }
    override fun collection(): FieldCollection<Post> = mainCollection

    // Someone else will need to explain this, I've never used it
    override fun registerChangeListener(action: suspend (CollectionChanges<Post>) -> Unit) {
        TODO("Not yet implemented")
    }

    override suspend fun collection(auth: AuthAccessor<User>): FieldCollection<Post> = mainCollection
        .withPermissions(permissions(auth))

    override suspend fun permissions(auth: AuthAccessor<User>): ModelPermissions<Post> {
        // more on this later
    }
}
```

Inside a suspend function, you can now access the functionality of the collections:

```kotlin
suspend fun collectionCalls() {
    collection.insertOne(Post(title = "Test", poster = "joseph@lightningkite.com", body = "Example"))
    collection.find(condition { it.title eq "Test" }).toList()
    collection.updateOne(
        condition { it.title eq "Test" },
        modification { it.title assign "Test Post" }
    )
    collection.deleteMany(condition { it.always })
    collection.count()
}
```

## Conditions and Modifications

There are many conditions and modifications available. Conditions are used to pull specific objects from the database, modifications are used to change them

To write a condition or modification, start with the `condition { it }` and `modification { it }` starters like you see above.
`condition` and `modification` provide context allowing you to build conditions and modifications on fields found in the model being queried.

### Condition builders
* **eq, neq, eqNn**: direct comparison between field and value. In the case of eqNn, it checks if the value is null
  * Ex. `condition { it._id eq <UUID> }`
* **inside, notInside**: checks for a field in a database column either inside or not inside of a list. **Requires**: a database column with a single value, a list of values of that same type
  * Ex. `condition { it._id inside <UUID[]> }`
* **any**: checks to see if a single value inside a field contains a value. **Requires**: a database column with a list of value, a single value
  * Ex. `condition { it.colors any { it eq Color.RED } }`
* **all**: Same as any, except all entries in value must match. **Requires**: a database column with a list of value, a single value
  * Ex. `condition { it.colors any { it eq Color.RED } }`
* **gt, lt, gte, lte**: number comparisons. **Requires**: a database column with a number, a number
  * Ex. `condition { it.size gt 2 }`
* **allClear, allSet, anyClear, anySet**: TODO
* **contains**: compares a database column to see if it contains a substring
  * Ex. `condition { it.title contains "foo" }`
* **distanceBetween** compares a database column to a GeoCoordinate and checks to see if it is within a certain range. **Requires**: database column of type GeoCoordinate, raw GeoCoordinate, two com.LightningServer.Length objects for greaterThan and lessThan
  * Ex. `condition { it.title.distanceBetween(<GeoLocation object>, greaterThan = Length(meters = 1), lessThan = Length(meters = 10)) }`


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

The following is a list of the signals that occur after a database change

* `postNewValue`: Runs after change to value in database column. Provides the inserted value
* `postCreate`: Runs after object is inserted into the database. Provides the new object
* `postRawChanges`: Runs after changes are made to objects in the database. Provides the list of changes
* `postChange`: Runs after a change is made to a field in the database. Provides the old object and new object
* `postDelete`: Runs after an object is deleted from the database. Provides the deleted object

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

The following is a list of the signals that occur after a database change

* `interceptCreate`: Runs before adding an object into the database. Provides the new object
* `interceptCreates`: Runs before adding multiple objects into the database. Provides the list of new objects
* `interceptModification`: Runs before modifying 1+ objects. Provides the modification being made
* `interceptModificationPerInstance`: Runs before modifying 1+ objects. Provides the object being changed and the modification being made
* `interceptChange`: Runs before any change is sent to the database, including inserting, replacing, upserting, and updating. Provides the modification being made
* `interceptChangePerInstance`: Runs before any change is sent to the database, including insert, replacing, upserting, and updating. Provides the object being changed and the modification being made
* `interceptDelete`: Runs before any object is deleted from the database. Provides the object being removed from the database
* `interceptReplace`: Runs before any object in the database gets replaced. Provides the object being changed

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
                it.privateNotes.maskedTo(null, unless = condition { it.poster eq currentUser })
            },
            update = condition { it.poster eq currentUser },
            updateRestrictions = updateRestrictions {
                it.updatedAt.cannotBeModified()
                it.content.requires(condition { it.editable eq true })
            },
            delete = condition { it.poster eq currentUser },
        )
    )
```

Creating views of databases like this is incredibly useful for centralizing rules about what users can and cannot do.

## REST endpoints

Lightning Server can automatically generate REST endpoints for a collection. First off, the class must implement the
`ModelInfoWithDefault` and `ServerPathGroup` interfaces. ModelInfoWithDefault to include the collections to pass to the
REST endpoints, and ServerPathGroup to include it in the url routing

```kotlin
class PostEndpoints(path: ServerPath): ServerPathGroup(path), ModelInfoWithDefault<User, Post, UUID> {
    // implement abstract members
}
```

Then use Lightning Server's ModelRestEndpoints class that will automatically create endpoints for you that will also be included in the sdk

```kotlin
class PostEndpoints: ModelInfoWithDefault<User, Post, UUID> {
    // your implementation 
    private val restPath = path("rest")
    // ModelRestEndpoints uses the context to reference the collections
    val rest = ModelRestEndpoints(restPath, this)
}
```

And there, your model now is REST compatible!

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