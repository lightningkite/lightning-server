# LightningServer documentation

LightningServer has a lot of nifty features. It minimizes boilerplate code making it easy to get a server up and
running, and simplifies working with a database and building REST APIs. Here is a step-by-step example of how to program
a server in LightningServer that will run locally on your machine.

## Running a server

To run a server with LightningServer, you simply need to call `loadSettings()` followed by `runServer()`:

<pre><code>fun main() {
    loadSettings(File("settings.json"))
    runServer(LocalPubSub, LocalCache)
}</code></pre>

The first time you try to run this, the program should throw an error saying `Need a settings file - example generated
at ...` and a file called `settings.json` should have been created in your project's directory. You should be able to
run the server a second time without errors.

## HttpEndpoints

If you want your server to actually do anything, you need to implement `HttpEndpoints`. In LightningServer, an
`HttpEndpoint` is the combination of a [`ServerPath`](#serverpaths) and an [`HttpMethod`](#httpmethods) (such as `get`,
`post`, `put`, etc.). `HttpEndpoints` are created within a user-defined object called `Server`, which will inherit from
an abstract class called [`ServerPathGroup`](#serverpathgroups). Create this object in a new file called `Server.kt`
(make sure to include it in the same package):

<pre><code>object Server : ServerPathGroup(ServerPath.root) {
    val root = path.get.handler { HttpResponse.plainText("Hello World!") }
}</code></pre>

Additionally, you'll need to call it in main:

<pre><code>fun main() {
    Server

    loadSettings(File("settings.json"))
    runServer(LocalPubSub, LocalCache)
}</code></pre>

If you make an HTTP get request to `localhost::8080`, you'll get the response `"Hello World!"`, as specified in the
server code. How does it work? The `Server` object inherits from `ServerPathGroup`, which is passed `ServerPath.root`.
This sets up our object so that any `ServerPath` added to it (or `HttpEndpoint` with a `ServerPath` tied to it) gets
added to the server's root path, allowing it to handle requests.

Inside the object, `path.get.handler {}` is called. This lambda simply creates an HTTP get endpoint and expects
an `HttpResponse` object as a return. Inside the lambda, an `HttpResponse` is set with the string `"Hello World!"`,
which is what the server would have responded with if you made a request.

### HttpMethods

This example uses an HTTP `get` handler, but LightningServer provides handlers for HTTP `get`, `post`, `put`,
`patch`, `delete`, and `head` methods as well.

### ServerPathGroups

LightningServer provides an abstract class called `ServerPathGroup`, which we inherited from for our `Server` object.
This class simply appends all of its `ServerPaths` and `HttpEndpoints` to whatever path was passed to it. Looking back
at the server, we have it like so:

<pre><code>object Server : ServerPathGroup(ServerPath.root) {
    // body...
}</code></pre>

Therefore, this setup applies all the `ServerPaths` and `HttpEndpoints` in the body of the `Server` object to the
server's root path.

### HttpRequest

You may have noticed that the get lambda has access to an `HttpRequest` object. You can use this object to access all
the data from the HTTP request that hit the endpoint. Here is a simple setup that demonstrates how to obtain data from
the `HttpRequest` in LightningServer:

<pre><code>object Server : ServerPathGroup(ServerPath.root) {
    private var counter: Int = 0

    val rootGet = path.get.handler { HttpResponse.plainText("$counter") }
    val rootPost = path.post.handler {
        counter = it.body!!.parse&lt;Int&gt;()
        HttpResponse.plainText("$counter")
    }
}

fun main() {
    Server

    loadSettings(File("settings.json"))
    runServer(LocalPubSub, LocalCache)
}</code></pre>

In `Server`, two `HttpEndpoints` are defined: a `get` and a `post`. The `get` simply returns the current value
of `counter`. The `post` has a bit more functionality. First, it gets the body of the `HttpRequest` and makes sure that
it is not null before parsing it into an integer
with `parse()` ([more on why `parse` is necessary](#serialization-and-typed-endpoints)). Then, it sets the value
of `counter` to whatever that parsed value was. After that, it returns the value of `counter`.

Something else you might want to know is that LightningServer automatically handles many things for you in `parse()`.
For example, what if the `HttpRequest` that was received did not contain an `Int`, but a `String`? In a case like this,
the Server will automatically respond with an HTTP 400 (Bad Request) status code, and the `ServerPath` will exit.

### ServerPaths

LightningServer allows you to specify the path a `ServerPath` is on simply by inputting a path parameter into `path`.
Create this `HttpEndpoint` in the `Server` object:

<pre><code>val notRoot = path("this-is-a-path").get.handler { HttpResponse.plainText("This is a separate endpoint.") }</code></pre>

If you run the server and make an HTTP `get` request to `localhost:8080/this-is-a-path`, the server will respond with
the text `This is a seperate endpoint.`

### Typed endpoints

Say that your server has this `HttpEndpoint`:

<pre><code>val rootGet = path.get.handler {
    HttpResponse.plainText("Hello World!")
}</code></pre>

Aside from this, there is another way to write an `HttpEndpoint` that performs the same task:

<pre><code>val rootGet = path.get.typed(
    summary = "Root get",
    errorCases = listOf(),
    implementation = { _user: Unit, _: Unit ->
        "Hello World!"
    }
)</code></pre>

This way may seem unnecessarily verbose, and for the purposes of this endpoint it is. But `typed` endpoints have a lot
more functionality than `handler` endpoints, and they will be used in later examples.

## LightningServer settings

LightningServer has a slew of settings that you can set to get specific functionality from your server. If you want a
specific functionality, all you have to do is declare the setting in code using `setting()`. For example, assume
that you want to store files locally on your server. To do this, you would call `setting()` with the `FilesSettings()`
object at the top of your `Server` object:

<pre><code>val files = setting("files", FilesSettings())</code></pre>

If you run the code now, you should get an error that reads `Settings were incorrect. Suggested updates are inside ...`.
The reason for this error (and the one [previously](#running-a-server)) is that LightningServer keeps the settings file
up to date with the settings declared in code every time `loadSettings()` is called. If they do not
match, `loadSettings()` will generate a file with suggested settings. In many cases, you may need to modify these
settings slightly, although in others it may be fine to use them as is. For our server, the suggested settings are what
we need, so we can just copy the contents of the generated `settings.suggested.json` file into `settings.json`. After
you've done that, you can safely delete the suggested settings file. Running the server again should work without any
errors.

Notice how the return of `setting()` is stored in a constant. This is because `setting()` returns a relevant object that
you can potentially use later on in your server code.

## Serialization and typed endpoints

Your server's usefulness is very limited if it can't send or receive more complicated information. However, while your
server code is written in Kotlin, HTTP requests are not, which means that you need a way of turning information from
those HTTP requests into Kotlin data types that you can use in your server. Serialization makes this possible.

While manually getting information from the `HttpRequest` with `parse()` may work, in many cases (especially those
where you would like to obtain multiple data objects from the `HttpRequest`) it is not efficient nor convenient. Rather,
it would be better if you were able to say what values you wanted from the request, and have LightningServer handle the
parsing of those values.

For the sake of example, rewrite the `Server` code from the `counter` example with `typed` endpoints:

<pre><code>object Server : ServerPathGroup(ServerPath.root) {
    val getCounter = path.get.typed(
        summary = "",
        errorCases = listOf(),
        implementation = { _: Unit, _: Unit ->
            counter
        }
    )
    val setCounter = path.post.typed(
        summary = "",
        errorCases = listOf(),
        implementation = { _: Unit, newValue: Int ->
            counter = newValue
        }
    )
}</code></pre>

Notice how you no longer have to return an `HttpResponse`. With `typed` endpoints, generic kotlin types are
serialized into JSON by default. Additionally, notice how the `post` accepts an `Int`. This value will be automatically
taken from the body of the HTTP Request in the form of a JSON integer literal. Run the server and see this system in
action. A get request will get the value of `counter`, and a post request can set the value of `counter`.

Another thing to take note of is the `summary` field, which isn't being used currently. This field is used for
[documenting your server's endpoints](#documenting-your-servers-endpoints).

### Serialization of data classes

The last example was fairly simple. You might want your server to be able to send and receive more complicated
information like your own data types. Create a new file called `models.kt`. This file will contain all of your data
classes. Additionally, create a new `User` model consisting of a `String` first and last name that the server will use
instead of the `Int` setup from before. Because this data needs to be serialized in our endpoints, you have to add
the `@Serializable` annotation to the class, or LightningServer will not be able to serialize it from JSON.

<pre><code>@Serializable
data class User(
    val firstname: String,
    val lastname: String
)</code></pre>

Additionally, exchange the counter variable in the `Server` object with a nullable `User` called `currentUser`:

<pre><code>object Server : ServerPathGroup(ServerPath.root) {
    var currentUser: User? = null

    val getUser = path.get.typed(
        summary = "",
        errorCases = listOf(),
        implementation = { _: Unit, _: Unit ->
            currentUser
        }
    )
    val setUser = path.post.typed(
        summary = "",
        errorCases = listOf(),
        implementation = { _: Unit, newUser: User ->
            currentUser = newUser
        }
    )
}</code></pre>

Run this and see what happens. Like the previous example, you can get the state of the current user via a `get` request,
and you can change the state of the current user via a `post` request. *(You can use a JSON object literal for
the `User` type in your requests.)*

### Contextual serialization

Sometimes you may want to serialize data from an `HttpRequest` into a data type that LightningServer does not have a
built-in serializer for and that you did not create yourself. In this case, you can use the
`@file:UseContextualSerialization()` file annotation. Here is what that would look like for the `UUID` class, which
will be used in the next section:

<pre><code>@file:UseContextualSerialization(UUID::class)

@Serializable
data class ExampleModel(
    val id: UUID = UUID.randomUUID()
)</code></pre>

Without the `@file:UseContextualSerialization()` annotation, the above code will produce errors.

## Database

LightningServer provides many systems for interacting with a database. To demonstrate this, the previous program will be
extended to implement a database full of `Users`. First, you'll need to specify the `DatabaseSettings` in your `Server`
object, as well as call `prepareModels()` in its `init` function:

<pre><code>object Server : ServerPathGroup(ServerPath.root) {
    val database = setting("database", DatabaseSettings())

    init {
        prepareModels()
    }

    val index = path.get.handler {
        HttpResponse.plainText("Hello, World!")
    }
}</code></pre>

Next, update the `models.kt` file to contain more information for the `User` type and add the `@DatabaseModel`
annotation which you'll need for database integration:

<pre><code>@file:UseContextualSerialization(UUID::class)

@Serializable
@DatabaseModel
data class User(
    override val _id: UUID = UUID.randomUUID()
    val firstname: String,
    val lastname: String
) : HasId&lt;UUID&gt;</code></pre>

Here the `User` model is written to use a `UUID`. This is so that every `User` in the database can be referenced by a
unique id, ensuring that you won't have any issues with multiple `Users` having similar information.
The `User` model is also written to inherit from `HasId`, an interface in LightningServer that provides the `_id` field
so that the model can utilize a few functions on the database.

### FieldCollections

`FieldCollections` are collections that LightningServer uses within the database. Whenever you want to add data to the
database, you'll be adding it to a `FieldCollection` of that type. This keeps similar information together, and
LightningServer also provides many useful functions within the `FieldCollection` to help manage the data within it.

Create a file called `UserEndpoints.kt`. This file will be used solely for managing the `FieldCollection` associated
with the `User` model. Within the file, create this class:

<pre><code>class UserEndpoints(path: ServerPath) : ServerPathGroup(path), ModelInfoWithDefault&lt;User?, User, UUID&gt; {
    private val collection: FieldCollection&lt;User&gt; by lazy {
        Server.database().collection()
    }
    override val serialization: ModelSerializationInfo&lt;User?, User, UUID&gt; = ModelSerializationInfo()
    override fun collection(): FieldCollection&lt;User&gt; = collection
    override suspend fun defaultItem(user: User?): User = User(
        firstname = "",
        lastname = ""
    )

    override suspend fun collection(principal: User?): FieldCollection&lt;User&gt; {
        val everybody = Condition.Always&lt;User&gt;()
        return collection.withPermissions(
            ModelPermissions(
                create = everybody,
                read = everybody,
                update = everybody,
                delete = everybody
            )
        )
    }

    val rest = ModelRestEndpoints(path("rest"), this)
    val restWebsockets = path("rest").restApiWebsocket(Server.database, this)
}</code></pre>

This class has many parts. First off, it inherits from `ModelInfoWithDefault`, an abstract class provided by
LightningServer. This is simply used for all the overridden values and functions, and you don't need to worry the actual
implementation of the class itself. The class also has a value called `collection` which is `by lazy`. This stores the
entire `FieldCollection` associated with the `User` model by grabbing it directly from the database. Aside from this,
there is an overridden `serialization` constant, an overridden `defaultItem()` function, and two
overridden `collection()` functions, one of them a `suspend`.

Taking a step back from all the members, what is the `UserEndpoints` class actually going to do? Although you haven't
written any yet, this class will eventually store all the basic REST endpoints associated with the `User` model.

Run the server and try making requests to it. By making a `get` request to `localhost:8080/users/rest`, you can see all
the `Users` stored in the database. It will be empty at first. By making a `post` request to the same address, you can
insert a `User` into the collection.

### Conditions

The previous example uses a `Condition`. When you want to write conditional statements on data in a FieldCollection,
this is what you will use. There are multiple ways to define a `Condition`. First, look at the one from the previous
example:

<pre><code>Condition.Always&lt;User&gt;()</code></pre>

This creates a `Condition` for the `User` model that calls `Always()`, a shorthand for setting the `Condition` to always
be `true`. Take a look at how this is used:

<pre><code> override suspend fun collection(principal: User?): FieldCollection&lt;User&gt; {
    val everybody = Condition.Always&lt;User&gt;()
    return collection.withPermissions(
        ModelPermissions(
            create = everybody,
            read = everybody,
            update = everybody,
            delete = everybody
        )
    )
}</code></pre>

Here, the `User` model permissions are defined. The `create`, `read`, `update`, and `delete` fields are set to the
`Condition`. When someone tries one of those operations, the `Condition` it is given will be checked to decide whether
they have access to that operation. Since the `Condition` is set to `true` via `Always()`, anyone who tries any of those
operations will be granted access. But what if you changed the permissions to this?:

<pre><code> override suspend fun collection(principal: User?): FieldCollection&lt;User&gt; {
    val nobody = Condition.Never&lt;User&gt;()
    return collection.withPermissions(
        ModelPermissions(
            create = nobody,
            read = nobody,
            update = nobody,
            delete = nobody
        )
    )
}</code></pre>

As you might expect, `Never()` sets the `Condition` to never be `true`, or rather to always be `false`. Run the server
and test the `get` and `post` endpoints again. This code means that anyone who tries any operation will be denied
access. Not very useful if you want anyone to access your data! Instead, you'll probably want a slightly more advanced
`Condition`. For example, you may only want certain `Users` (admins) to be able to make requests. To do this, you'll
need to add a flag to the `User` model for whether they are an admin, and you'll need to make use of the `principal`
parameter, which contains the given `User` attempting to make the request. Using these bits of information together, we
can write a new `Condition`:

<pre><code>data class User(
    override val _id: UUID = UUID.randomUUID()
    val firstname: String,
    val lastname: String,
    val isAdmin: Boolean
) : HasId&lt;UUID&gt;</code></pre>

<pre><code>override suspend fun collection(principal: User?): FieldCollection&lt;User&gt; {
    val admins = if (principal?.isAdmin == true) Condition.Always&lt;User&gt;() else Condition.Never&lt;User&gt;()
    return collection.withPermissions(
        ModelPermissions(
            create = admins,
            read = admins,
            update = admins,
            delete = admins
        )
    )
}</code></pre>

This all works just fine. Only `Users` with the `isAdmin` flag set to true will be able to access these operations. But
what about a user trying to access operations for their own `User`? Rather, what if a `User` wanted to be able to
read and write their own fields, such as their `firstname` and `lastname`? To do this, you'll have to have multiple
`Conditions`, and this example will also show you another way to create one:

<pre><code>override suspend fun collection(principal: User?): FieldCollection&lt;User&gt; {
    val admins = if (principal?.isAdmin == true) Condition.Always&lt;User&gt;() else Condition.Never&lt;User&gt;()
    return collection.withPermissions(
        ModelPermissions(
            create = admins,
            read = if (principal != null) condition { it._id eq principal._id } else admins,
            update = admins,
            delete = admins
        )
    )
}</code></pre>

Now, `Users` can read their own data.

Here is another way to write a condition:

<pre><code>condition { it.firstname eq "Test First Name" }</code></pre>

Like with normal if statements, you can test for multiple `Conditions` at once using the infix operators `and` and
`or`, which work as you would expect. Note that you need to wrap individual conditions in parentheses:

<pre><code>condition {
    (it.firstname eq "Test First Name") and
    (it.lastname eq "Test Last Name")
}</code></pre>

<pre><code>condition {
    (it.firstname eq "Test First Name") or
    (it.lastname eq "Test Last Name")
}</code></pre>

`Conditions` are only used in certain contexts, mainly as a parameter for functions on a `FieldCollection` or other
related items, such as `ModelPermissions()` from before. Here is an example of how you could use this to get a list
of `Users` from the collection that are all admins using the function `find()`:

<pre><code>val users = collection.find(condition = condition {
    it.isAdmin eq true
})</code></pre>

You can find a full list of the operations you can perform inside a `Condition` in `docs-feature-list.md`.

### Modifications

When data is stored into the database, it is no longer directly referenced in your code. If you use
a [`condition`](#conditions) to find that data again and create an instance of that data in your code, modifying that
instance will not affect the data in the database. So how do you change existing data within the database? You use
`Modifications`.

`Modifications` are very similar to `Conditions` syntactically. If you wanted to modify the name of
an existing `User` in the database, you could write this `Modification`:

<pre><code>modification { it.firstname assign "New First Name" }</code></pre>

You can also chain multiple `Modifications` together using the infix operator `then`. Like with `Conditions`, you have
to wrap individual modifications with parentheses:

<pre>modification {
    (it.firstname assign "New First Name") then
    (it.lastname assign "New Last Name")
}</pre>

Like `Conditions`, `Modifications` are only used in certain contexts. They are generally used as parameters to functions
on a `FieldCollection`. For example, here is how you could use a `Modification` to modify the name of an existing `User`
with a given id using the function `updateOneById()`:

<pre><code>val userId = /* obtain user id */
collection.updateOneById(id = userId, modification = modification {
    it.firstname assign "New First Name"
})</code></pre>

You can find a full list of the operations you can perform inside a `Modification` in `docs-feature-list.md`.

## Documenting your server's endpoints

There's a good chance that after you've written your server you'll want to document your endpoints and everything that a
user would need to know to make requests to those endpoints. Because of this, LightingServer provides a shortcut
function `apiHelp()` that automatically documents every `typed` endpoint within your server (printing out given
`summary` text), models you've created that use the `@Serializable` annotation, and other Kotlin types that your server
uses serialization for, as well as how to make write all of those in JSON. Here is how you can use `apiHelp()` within
your `Server` object:

<pre><code>object Server : ServerPathGroup(ServerPath.root) {
    /* typed endpoints and other server code */
    val docs = path("docs").apiHelp()
}</code></pre>

As you can see, this is extremely simple to use within your code. This is also another great reason for why you should
mainly use `typed` endpoints.