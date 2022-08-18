# LightningServer documentation

LightningServer has a lot of nifty features. It minimizes boilerplate code to make it easy to get a server up and
running. Here is a step-by-step example of how to program a server that will run locally on your machine.

## Running a server

To run a server with LightningServer, you simply need to call `loadSettings()` followed by `runServer()`:

<pre><code>fun main() {
    loadSettings(File("settings.json"))
    runServer(LocalPubSub, LocalCache)
}
</code></pre>

The first time you try to run this, the server should throw an error saying `Need a settings file - example generated
at ...` and a file called `settings.json` should have been created. You should be able to run the server a second time
and everything will work.

## ServerPaths

If you want your server to actually do anything, you need to implement `ServerPaths`. In LightningServer, a
`ServerPath` is the combination of a URL and an HTTP method (such as `get`, `post`, `put`, etc.). We will define our
`ServerPaths` in a user-defined object called `Server`, which will inherit from ServerPathGroup and implement a
`ServerPath` at the server's root:

<pre><code>object Server : ServerPathGroup(ServerPath.root) {
    val root = path.get.handler { HttpResponse.plainText("Hello World!") }
}

fun main() {
    Server

    loadSettings(File("settings.json"))
    runServer(LocalPubSub, LocalCache)
}</code></pre>

If you make an HTTP get request to `localhost::8080`, you'll get the response `"Hello World!"`, as specified in our
server code. How does it work? Our `Server` object inherits from `ServerPathGroup`, which is passed `ServerPath.root`.
This sets up our object so that any `ServerPath` we add to it gets added to our server's root path, allowing us to
handle requests.

Inside the object, `path.get.handler {}` is called. This lambda simply creates an HTTP get request and expects
an `HttpResponse` object as a return. Inside the lambda, an `HttpResponse` is set with the string `"Hello World!"`,
which is what the server responded with when we made our request.

This example uses an HTTP `get` handler, but LightningServer provides handlers for HTTP `get`, `post`, `put`, `patch`,
`delete`, and `head`.

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

In `Server`, we define two `ServerPaths`: a `get` and a `post`. The get simply returns the current value of `counter`.
The `post` has a bit more functionality. First, we get the body of the `HttpRequest` and make sure that it is not null
before parsing it into an integer with `parse&lt;Int&gt;()`. We then set the value of `counter` to whatever that parsed
value was. After that, we return the value of `counter`.

Something else you might want to know is that LightningServer automatically handles many things for you in `parse()`.
For example, what if the `HttpRequest` that was received did not contain an `Int`, but a `String`? In a case like this,
the Server will automatically respond with an HTTP 400 (Bad Request) status code, and the `ServerPath` will exit.

### Paths

LightningServer allows us to specify our `ServerPath's` paths simply by inputting a path parameter into `path`:

<pre><code>val notRoot = path("this-is-a-path").get.handler { HttpResponse.plainText("This is a separate endpoint.") }</code></pre>

If you run the server and make an HTTP `get` request to `localhost:8080/this-is-a-path`, the server will respond
with the text `This is a seperate endpoint.`

### Typed paths

Let's say that we have this endpoint:

<pre><code>path.get.handler {
    HttpResponse.plainText("Hello World!")
}</code></pre>

Aside from this, there is another way to write an endpoint that performs the same task:

<pre><code>path.get.typed(
    summary = "Root get",
    errorCases = listOf(),
    implementation = { _user: Unit, _: Unit ->
        "Hello World!"
    }
)</code></pre>

This way may seem unnecessarily verbose, and for the purposes of this endpoint it is. But `typed` endpoints have a lot
more functionality than `handler` endpoints, and we will be using them in later examples.

## LightningServer settings

LightningServer has a slew of settings that you can set to get specific functionality from your server. If you want a
specific functionality, all you have to do is declare the setting in code using `setting()`. For example, lets assume
that we want to store files locally on our server. To do this, we would call `setting()` with the `FilesSettings()`
object at the top of our `Server` object:

<pre><code>val files = setting("files", FilesSettings())</code></pre>

If you run the code now, you should get an error that reads `Settings were incorrect. Suggested updates are inside ...`.
The reason for this error (and the one [previously](#running-a-server)) is that LightningServer keeps the settings file
up to date with the settings declared in code every time `loadSettings()` is called. If they do not
match, `loadSettings()` will generate a file with suggested settings. In many cases, you may need to modify these
settings slightly, although in others it may be fine to use them as is. For our server, the suggested settings are what
we need, so we can just copy the contents of the generated `settings.suggested.json` file into `settings.json`. After
you've done that, you can safely delete the suggested settings file. Running the server again should work without any
errors.

Notice how we stored the return of `setting()` in a constant. This is because `setting()` returns a relevant object that
we can use later on in our server code.

## Serialization and typed paths

Our server's usefulness is very limited if it can't send or receive more complicated information. However, while our
server code is written
in Kotlin, HTTP requests are not, which means that we need a way of turning information from those HTTP requests into
Kotlin data types that we can use in our server. Serialization makes this possible.

While manually getting information from the `HttpRequest` manually may work, in many cases (especially those where we
would like to obtain multiple data objects from the `HttpRequest`) it is not efficient nor convenient. Rather, we would
like a way to be able to say what values we want from the request, and have LightningServer handle parsing those values
for us.

Pretend we wanted to return the value of `counter` within the `get` handler, and we wanted the `post` to be able to set
the value of `counter`. As it is right not, we don't have a simple way of retrieving or sending the required information
without too much additional work. This is where [`typed`](#typed-paths) endpoints come in. Rewrite `Server`
with `typed` endpoints:

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

Notice how we no longer have to return an `HttpResponse`. With `typed` endpoints, generic kotlin types are automatically
serialized into JSON by default. Additionally, notice how the `post` accepts an `Int`. This value will be automatically
taken from the body of the HTTP Request in the form of a JSON integer literal. Run the server and see this system in
action. A get request will get the value of `counter`, and a post request can set the value of `counter`.

### Serialization of data classes

The last example was fairly simple. Often we want our server to be able to send and receive more complicated information
like our own data types. Let's create a new file called `models.kt`. This file will store all of our data classes. We'll
create a new `User` model consisting of a `String` first and last name that our server will use instead of the `Int`
setup from before. Because this data needs to be serialized in our endpoints, we have to add the `@Serializable`
annotation to the class, or LightningServer will not be able to serialize it from JSON.

<pre><code>@Serializable
data class User(
    val firstname: String,
    val lastname: String
)</code></pre>

Additionally, let's exchange the counter variable in the `Server` object with a nullable `User` called `currentUser`:

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

Run this and see what happens. Like the previous example, we can get the state of the current user via a `get` request,
and we can change the state of the current user via a `post` request. *(You can use a JSON object literal in place of
the `User` type in your requests.)*

### Contextual serialization

Sometimes we may want to serialize data into a data type that LightningServer does not have a built-in serializer for
and that we have not created ourselves. In this case, we can use the `@file:UseContextualSerialization()` file
annotation. Here is what that would look like for the `UUID` data class, which we will conveniently be using in the next
section:

<pre><code>@file:UseContextualSerialization(UUID::class)

@Serializable
data class ExampleModel(
    val id: UUID = UUID.randomUUID()
)</code></pre>

## Database

LightningServer provides many systems for interacting with a database. To demonstrate this, let's extend the previous
program to implement a database full of `Users`. First let's update the `models.kt` file to contain more information for
the `User` type and add the `@DatabaseModel` annotation which we'll need for database integration:

<pre><code>@file:UseContextualSerialization(UUID::class)

@Serializable
@DatabaseModel
data class User(
    override val _id: UUID = UUID.randomUUID()
    val firstname: String,
    val lastname: String
) : HasId&lt;UUID&gt;</code></pre>

Here we've rewritten our `User` model to use `UUIDs`. This is so that every `User` in our database will have unique id
that we can reference, ensuring that we won't have any issues with multiple `Users` having similar information. We've
also written the `User` model to inherit from `HasId`, an interface in LightningServer that provides the `_id` field
so that we can use a few functions on the database.