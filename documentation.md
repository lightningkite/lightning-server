# LightningServer feature documentation

LightningServer has a lot of nifty features. It minimizes boilerplate code to make it easy to get a server up and
running. Here is a step-by-step example of how to program a server that will run locally on your machine.

## Starting a server

To run a server with LightningServer, you simply need to call `loadSettings()` followed by `runServer()`:

<pre><code>import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.ktor.runServer
import com.lightningkite.lightningserver.pubsub.LocalPubSub
import com.lightningkite.lightningserver.settings.loadSettings
import java.io.File

fun main() {
    loadSettings(File("settings.json"))
    runServer(LocalPubSub, LocalCache)
}
</code></pre>

The first time you try to run this, the server should throw an error
saying `Need a settings file - example generated at ...` and a file called `settings.json` should have been created. You
should be able to run the server a second time and everything will work.

## Routing and endpoints

Aside from starting, however, the server won't actually process anything, and it will only respond to requests with
errors. To fix this, we need to define the server's endpoints. In LightningServer, we do this with a `routing {}`
lambda. Here is a simple setup that creates an HTTP get endpoint at the server's root:

**Make sure to call `routing {}` before `loadSettings()` and `runServer()`, or it will not work properly**.

<pre><code>routing {
    get.handler {
        HttpResponse.plainText("Hello, World!")
    }
}</code></pre>

Inside of `routing {}`, `get.handler {}` is called. This lambda simply creates an HTTP get request and expects an
`HttpResponse` object as a return. Inside the lambda, an `HttpResponse` is set with the string "Hello World!".

Now, whenever the server receives an HTTP get request at its root directory, it will respond with the string "Hello
World!". Since this server is running locally, you can try this by making a request to `localhost:8080`.

LightningServer provides handlers for HTTP Get, Post, Put, Patch, Delete, and Head.

### HttpRequest

You may have noticed that the get lambda has access to an `HttpRequest` object. You can use this object to access all
the data
from the HTTP Request that hit the endpoint.

### Paths

Let's add another get to our routing:

<pre><code>get.handler {
    HttpResponse.plainText("This is a separate endpoint.")
}</code></pre>

Now run the server and send it a get request. Even though we have two get handlers, the server only responded
once! Additionally, this setup makes it impossible to select which endpoint of the two we are actually trying to hit.

Rather than having all of our endpoints in our server's root directory, we can place our endpoints in server paths,
which allows us to be more specific when making our requests. Add a path to the previous get handler like this:

<pre><code>get("test-path").handler {
    HttpResponse.plainText("This is a separate endpoint.")
}</code></pre>

Run the server again, and make a request to the same endpoint as before. Again, we get "Hello World!" as a response.
Additionally, if you now make a get request to `localhost:8080/test-path`, you get the response "This is a separate
endpoint."

Say we wanted to wrap multiple endpoints within a path. You could write them all like this:

<pre><code>get("test-path").handler { /* ... */ }
post("test-path").handler { /* ... */ }
put("test-path").handler { /* ... */ }
/* ... */</code></pre>

But what if we wanted to change that path? We would have to go through all the endpoints and change them manually.
Instead, we can specify a path that wraps around all the endpoints so that we only have to change it in one place:

<pre><code>path("test-path") {
    get.handler { /* ... */ }
    post.handler { /* ... */ }
    put.handler { /* ... */ }
    /* ... */
}</code></pre>

## LightningServer settings

LightningServer has a slew of settings that you can set to get specific functionality from your server. If you want a
specific functionality, all you have to do is declare the setting in code using `setting()`. For example, lets assume
that we want to store files locally on our server. To do this, we would call `setting()` with the `FilesSettings()`
object:

<pre><code>val files = setting("files", FilesSettings())</code></pre>

If you run the code now, you should get an error that reads `Settings were incorrect. Suggested updates are inside ...`.
The reason for this error (and the one [previously](#starting-a-server)) is that LightningServer keeps the settings file
up to date with the settings declared in code every time `loadSettings()` is called. If they do not
match, `loadSettings()` will generate a file with suggested settings. In many cases, you may need to modify these
settings slightly, although in others it may be fine to use them as is. For our server, the suggested settings are what
we need, so we can just copy the contents of the generated `settings.suggested.json` file into `settings.json`. After
you've done that, you can safely delete the suggested settings file. Running the server again should work without any
errors.

Notice how we stored the return of `setting()` in a constant. This is because `setting()` returns a relevant object that
we can use later on in our server code.

## Models and serialization

<pre><code>prepareModels()</code></pre>

## Database

LightningServer provides many systems for interacting with a database. To demonstrate this, lets use this server code as
a base:

<pre><code>fun main() {
    prepareModels()
    routing {

    }

    loadSettings("settings.json")
    runServer(LocalPubSub, LocalCache)
}</code></pre>

To start using a database, you first have to declare the database setting:

<pre><code>val database = setting("database", DatabaseSettings())</code></pre>

## Authentication and authorization

LightningServer provides many automatic systems for adding authentication and authorization to your server.