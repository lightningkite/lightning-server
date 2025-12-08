# Set Up Lightning Server

Last updated January 2025 (`version-5`)

## Create a new Kotlin/Gradle KTS project.

IntelliJ has a quick option for this under "New Project".  Make sure you select Kotlin, Gradle, and Kotlin as your DSL language.

## Add Gradle Plugins

Use the version catalog pattern in `gradle/libs.versions.toml` or configure plugins directly in your build files.

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()
        google()
        gradlePluginPortal()
        mavenCentral()
        maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
        maven(url = "https://s01.oss.sonatype.org/content/repositories/releases/")
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.1.0" // or your preferred Kotlin version
    kotlin("plugin.serialization") version "2.1.0"
    id("com.google.devtools.ksp") version "2.1.0-1.0.29"
}

repositories {
    mavenLocal()
    maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
    maven(url = "https://s01.oss.sonatype.org/content/repositories/releases/")
    mavenCentral()
}
```

## Add the Lightning Server dependencies you need

```kotlin
// build.gradle.kts
val lightningServerVersion = "version-5-SNAPSHOT"

dependencies {
    // Core modules - always needed
    ksp("com.lightningkite.lightningserver:processor:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:core:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:core-shared:$lightningServerVersion")

    // Typed endpoints - recommended for API development
    implementation("com.lightningkite.lightningserver:typed:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:typed-shared:$lightningServerVersion")

    // Choose an engine for local development:
    implementation("com.lightningkite.lightningserver:engine-ktor:$lightningServerVersion")
    // OR
    implementation("com.lightningkite.lightningserver:engine-netty:$lightningServerVersion")
    // OR
    implementation("com.lightningkite.lightningserver:engine-jdk-server:$lightningServerVersion")

    // Optional: AWS serverless deployment
    implementation("com.lightningkite.lightningserver:engine-aws-serverless:$lightningServerVersion")

    // Optional: Authentication
    implementation("com.lightningkite.lightningserver:auth:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:auth-shared:$lightningServerVersion")

    // Optional: Session management
    implementation("com.lightningkite.lightningserver:sessions:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:sessions-shared:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:sessions-email:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:sessions-sms:$lightningServerVersion")

    // Optional: File handling
    implementation("com.lightningkite.lightningserver:files:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:files-shared:$lightningServerVersion")

    // Add service implementations as needed (see individual documentation)
}
```

## Insert a Server Definition

```kotlin
// src/main/kotlin/Server.kt
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse

object Server : ServerBuilder() {
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello world!")
    }
}
```

## Create a main function for local running

```kotlin
// src/main/kotlin/Main.kt
import com.lightningkite.lightningserver.engine.ktor.KtorEngine
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.KFile
import io.ktor.server.netty.Netty

fun main(args: Array<String>) {
    val built = Server.build()
    KtorEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start(Netty)
    }
}
```

## Try it out!

Run the program twice - the first time it will create a default `settings.json` file for you, and the second time it will run the server normally.

*It is considered an important Lightning Server principal to ensure your application works out of the box with the generated `settings.json`.*

Go to [http://localhost:8080](http://localhost:8080) to see your "Hello world!"

## Create a unit test

Unit tests in Lightning Server use mock services to avoid external dependencies:

```kotlin
// src/test/kotlin/ServerTest.kt
import com.lightningkite.lightningserver.engine.local.LocalEngine
import com.lightningkite.lightningserver.http.test
import com.lightningkite.services.database.jsonfile.JsonFileDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ServerTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            // Ensure service implementations are loaded
            JsonFileDatabase
        }
    }

    @Test
    fun testRoot(): Unit = runBlocking {
        val engine = LocalEngine(Server.build())
        val response = Server.root.test(engine)
        assertEquals("Hello world!", response.body!!.text())
    }
}
```

NEXT: [Settings](settings.md)
