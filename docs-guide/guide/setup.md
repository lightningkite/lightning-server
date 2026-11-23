# Setup

Add Lightning Server to a Gradle (Kotlin DSL) project.  You need a JVM module for the server; a multiplatform
module for models shared with clients is optional and covered in [Project structure](project-structure.md).

## Minimal setup

`settings.gradle.kts` adds the Lightning Kite repository and imports the stack catalog as `lk`:

```kotlin
rootProject.name = "my-app"

dependencyResolutionManagement {
    repositories {
        maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
        mavenCentral()
    }
    versionCatalogs {
        create("lk") {
            from("com.lightningkite:stack-catalog:<version>")
        }
    }
}

include(":server")
```

`gradle/libs.versions.toml` holds the Kotlin and KSP plugins.  Use the versions Lightning Server is built with,
found in its
[`gradle/libs.versions.toml`](https://github.com/lightningkite/lightning-server/blob/master/gradle/libs.versions.toml):

```toml
[versions]
kotlin = "2.4.21"
ksp = "2.3.13"

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

`server/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    application
}

dependencies {
    implementation(platform(lk.stack.bom))
    implementation(lk.lightningserver.core.asProvider())
    implementation(lk.lightningserver.typed.asProvider())
    implementation(lk.lightningserver.engine.ktor)
    ksp(lk.services.database.processor)
    testImplementation(kotlin("test"))
}

kotlin {
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

application {
    mainClass.set("com.example.MainKt")
}
```

The KSP processor generates the query DSL for `@Queryable` models (see [Database](database.md)); drop it and the
`srcDir` line if you have none.  Tests need no extra module: `test {}` and `testBlocking {}` are in `core`.

## Check the setup

Put a server in `server/src/main/kotlin`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#hello-server -->
```kotlin
object HelloServer : ServerBuilder() {

    // GET / — responds with a plain-text greeting
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello, Lightning Server!")
    }
}
```

and a test in `server/src/test/kotlin`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#hello-server-test -->
```kotlin
fun helloServerTest() = HelloServer.testBlocking(settings = {}) {
    val response = HelloServer.root.test()
    check(response.body?.text() == "Hello, Lightning Server!")
}
```

Annotate it with `@Test` and run `./gradlew :server:test`.  The imports are listed in
[Your first endpoint](first-endpoint.md#imports); serving it on a port is covered in [Running](running.md).

## Versions

Every published module has its own version, in epoch semver: `(epoch × 1000 + breaking changes).minor.patch`.
`1003.2.0` means design era 1, its third breaking change, and the second addition since that break.  Modules move
independently, so `core` and `sessions` rarely share a number.  Don't write module versions by hand; take them from
a catalog.

| Artifact | Contents | Accessor examples |
|---|---|---|
| `com.lightningkite:stack-catalog` | Every module of Lightning Server, Service Abstractions, KiteUI, Reactive, and Lightning Server KiteUI, plus the BOM | `lk.lightningserver.core`, `lk.services.database.processor`, `lk.stack.bom` |
| `com.lightningkite:stack-bom` | Platform that aligns transitive modules to the same stack release | `platform(lk.stack.bom)` |
| `com.lightningkite.stack:lightning-server-catalog` | Lightning Server modules only, named by artifact | `ls.core`, `ls.engine.ktor` |

The stack catalog is the usual choice: one version to change.  Its latest version is in
[`maven-metadata.xml`](https://lightningkite-maven.s3.us-west-2.amazonaws.com/com/lightningkite/stack-catalog/maven-metadata.xml).

To use the per-repo catalogs instead, import each one you need under its own name:

```kotlin
versionCatalogs {
    create("ls") { from("com.lightningkite.stack:lightning-server-catalog:<version>") }
    create("sa") { from("com.lightningkite.stack:service-abstractions-catalog:<version>") }
}
```

Their latest versions are in `com/lightningkite/stack/<repo>-catalog/maven-metadata.xml` in the same bucket.

## Gotchas

- **`.asProvider()`**: when a catalog alias is also the prefix of another (`lightningserver-core` and
  `lightningserver-core-shared`), Gradle makes `lk.lightningserver.core` a group.  Call `.asProvider()` to get the
  library itself.  Aliases with no longer sibling (`lk.lightningserver.engine.ktor`) don't need it.
- **Kotlin version**: use the Kotlin and KSP versions Lightning Server is built with.  The API relies on context
  parameters, and the KSP processor is tied to the compiler version.
- **Service backends are separate artifacts**: the service interfaces (database, cache, ...) come in
  transitively, with in-memory (`ram`) defaults.  To use MongoDB, Redis, S3, and so on, add the backend (`lk.services.database.mongodb`, ...) and configure it in
  settings.  See [Services](services.md).
- **`publishToMavenLocal`** of a Lightning Server checkout publishes unreleased code under the last released
  version, which then shadows the real release if `mavenLocal()` is in your repositories.

## Next

- [Your first endpoint](first-endpoint.md)
- [Project structure](project-structure.md): module catalog, shared models, multiplatform setup
- [Running](running.md)
