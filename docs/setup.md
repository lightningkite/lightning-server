# Set Up Lightning Server

## Create a new Kotlin/Gradle KTS project.

IntelliJ has a quick option for this under "New Project". Make sure you select Kotlin, Gradle, and Kotlin as your DSL
language.

## Add Gradle Plugins

Use the version catalog pattern in `gradle/libs.versions.toml` or configure plugins directly in your build files.

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("com.google.devtools.ksp") version "2.3.12"
}

repositories {
    maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
    mavenCentral()
}
```

Use the Kotlin and KSP versions in this repository's `gradle/libs.versions.toml`, since Lightning Server is built
against them.

## Add the Lightning Server dependencies you need

```kotlin
// build.gradle.kts
val lightningServerVersion = "<version>"            // See the badge at the top of the README
val serviceAbstractionsVersion = "<version>"        // The serviceAbstractions version in libs.versions.toml

dependencies {
    // Generates the query DSL for @Queryable models
    ksp("com.lightningkite.services:database-processor:$serviceAbstractionsVersion")

    // Core modules - always needed
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
    implementation("com.lightningkite.lightningserver:sessions-totp:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:sessions-webauthn:$lightningServerVersion")

    // Optional: File handling
    implementation("com.lightningkite.lightningserver:files:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:files-shared:$lightningServerVersion")

    // Optional: Audit logging
    implementation("com.lightningkite.lightningserver:audit:$lightningServerVersion")

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
import com.lightningkite.services.kfile.KFile
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

Run the program twice - the first time it will create a default `settings.json` file for you, and the second time it
will run the server normally.

*It is considered an important Lightning Server principal to ensure your application works out of the box with the
generated `settings.json`.*

Go to [http://localhost:8080](http://localhost:8080) to see your "Hello world!"

## Create a unit test

Unit tests in Lightning Server use mock services to avoid external dependencies:

```kotlin
// src/test/kotlin/ServerTest.kt
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.runtime.test.testBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerTest {
    @Test
    fun testRoot() = Server.testBlocking(settings = {}) {
        val response = Server.root.test()
        assertEquals("Hello world!", response.body!!.text())
    }
}
```

NEXT: [Settings](settings.md)
