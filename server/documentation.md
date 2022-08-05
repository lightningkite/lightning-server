# LightningServer feature documentation

LightningServer has a lot of nifty features. Here is a list of things to get you started, or just to be used as a
reference.

## Starting a server

LightningServer minimizes boilerplate code to make it easy to get a server up and running. Here is a step-by-step example of how to program a server that will run locally on your machine.

Here is how you initialize a server with LightningServer. You simply need to call `loadSettings()` followed by `runServer()`.

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

The first time you try to run this, the server should stop with an error saying `Need a settings file - example generated at ...` and a file called `settings.json` should have been created. You should be able to run the server a second time and everything will work.

## Routing

Aside from starting, however, the server won't actually process anything, and it will only respond to requests with errors. To fix this, we need to define the server's endpoints. In LightningServer, we do this with a `routing {}` lambda. Here is a simple setup that creates an HTTP get endpoint at the server's root. **Make sure to call `routing {}` before `loadSettings()`**.

<pre><code>routing {
    get.handler {
        HttpResponse.plainText("Hello, World!")
    }
}</code></pre>

In case you missed them, you will also need these imports:

<pre><code>import com.lightningkite.lightningserver.core.routing
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.handler</code></pre>

Inside of `routing {}`, `get.handler {}` is called. This lambda simply creates an HTTP get request at the server's root. Inside the lambda, an `HttpResponse` is set with the string "Hello World!".

Now, whenever the server receives an HTTP get request at its root directory, it will respond with the string "Hello World!". Since this server is running locally, you can try this by making a request to `localhost:8080`.
