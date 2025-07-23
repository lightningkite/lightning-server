# Set Up Lightning Server

**OUT OF DATE**  Needs version pinning

## Add Gradle Plugins

You'll need [KSP](https://kotlinlang.org/docs/ksp-quickstart.html) and Kotlin added.

## Import from Maven

Right now, the libraries are hosted on our own public S3 bucket.

```kotlin
// build.gradle.kts
repositories {
    maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
}
val lightningServerVersion = "<current tag>"
dependencies {
    // An annotation processor.  Gives a nice DSL for forming queries.  You'll always want this.
    ksp("com.lightningkite.lightningserver:processor:$lightningServerVersion")

    // The core server dependencies.  You'll always want this for the server itself.
    api("com.lightningkite.lightningserver:server-core:$lightningServerVersion")

    // Implementations of interfaces for AWS and a terraform generator for deploying to Lambda/API Gateway
    api("com.lightningkite.lightningserver:server-aws:$lightningServerVersion")

    // Implementations of interfaces for Microsoft Azure (experimental)
    api("com.lightningkite.lightningserver:server-azure:$lightningServerVersion")

    // Run your server via Ktor
    api("com.lightningkite.lightningserver:server-ktor:$lightningServerVersion")

    // AWS DynamoDB Cache implementation
    api("com.lightningkite.lightningserver:server-dynamodb:$lightningServerVersion")

    // Firebase Cloud Messaging Notification implementation
    api("com.lightningkite.lightningserver:server-firebase:$lightningServerVersion")

    // Memcached Cache implementation
    api("com.lightningkite.lightningserver:server-memcached:$lightningServerVersion")

    // MongoDB Database implementation
    api("com.lightningkite.lightningserver:server-mongo:$lightningServerVersion")

    // PostgreSQL Database implementation (experimental)
    api("com.lightningkite.lightningserver:server-postgresql:$lightningServerVersion")

    // Redis Cache implementation
    api("com.lightningkite.lightningserver:server-redis:$lightningServerVersion")

    // Sentry exception reporting
    api("com.lightningkite.lightningserver:server-sentry:$lightningServerVersion")

    // SFTP File System Implementation (warning: does not work as public file store)
    api("com.lightningkite.lightningserver:server-sftp:$lightningServerVersion")

    //...
}
```

## Insert a Server Definition

```kotlin
// src/main/kotlin/Server.kt
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.handler

object Server : ServerPathGroup(ServerPath.root) {
    val root = path.get.handler {
        HttpResponse.plainText("Hello world!")
    }
}
```

## Create a main function for local running

```kotlin
// src/main/kotlin/Main.kt
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.ktor.runServer
import com.lightningkite.lightningserver.pubsub.LocalPubSub
import com.lightningkite.lightningserver.settings.loadSettings
import java.io.File

fun main(args: Array<String>) {
    // Load our server declaration
    Server

    // Load the settings file
    loadSettings(File("settings.json"))

    // Run the server using Ktor
    runServer(LocalPubSub, LocalCache)
}
```

## Try it out!

Run the program twice - the first time it will create a default `settings.json` file for you, and the second time it will run the server normally.

*It is considered an important Lightning Server principal to ensure your application works out of the box with the generated `settings.json`.*

Go to [http://localhost:8080](http://localhost:8080) to see your "Hello world!"

## Create a unit test

```kotlin
// src/test/kotlin/ServerTest.kt
import com.lightningkite.lightningserver.engine.UnitTestEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.http.test
import com.lightningkite.lightningserver.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

object TestSettings {
    init {
        // Load the full server definition.
        Server

        // Set up our settings for the test environment
        Settings.populateDefaults(mapOf())

        // Use the UnitTestEngine for testing - makes async tasks run on the spot for easy testing.
        engine = UnitTestEngine
    }
}

class ServerTest {
    // Make sure this gets loaded in.
    init { TestSettings }

    @Test
    fun test(): Unit = runBlocking {
        val response = Server.root.test()
        assertEquals("Hello world!", response.body!!.text())
    }
}
```

NEXT: [Settings](settings.md)
