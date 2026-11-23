[![Latest core](https://img.shields.io/maven-metadata/v?label=core&metadataUrl=https://lightningkite-maven.s3.us-west-2.amazonaws.com/com/lightningkite/lightningserver/core/maven-metadata.xml)](https://lightningkite-maven.s3.us-west-2.amazonaws.com/com/lightningkite/lightningserver/core/maven-metadata.xml)

# Lightning Server

A Kotlin framework for HTTP and WebSocket backends.  You declare the whole server in plain Kotlin: endpoints with
typed inputs, outputs and auth; the services it needs as settings; tasks and schedules.  From that one definition
the framework serves requests, generates OpenAPI and typed client SDKs, runs your tests in memory, and writes the
Terraform to deploy it on AWS.

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#echo-server -->
```kotlin
object EchoServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    // POST /echo
    val echo = path.path("echo").post bind ApiHttpHandler(
        summary = "Echo a message",
        description = "Returns the message back with its character count.",
        auth = noAuth,
        implementation = { input: EchoRequest ->
            EchoResponse(
                echo = input.message,
                length = input.message.length
            )
        }
    )
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#echo-server-test -->
```kotlin
fun echoServerTest() = EchoServer.testBlocking(settings = {}) {
    val result = EchoServer.echo.test(null, EchoRequest("ping"))
    check(result.echo == "ping")
    check(result.length == 4)
}
```

## What you get

- **Typed endpoints end to end.**  Input, output, auth and error cases are Kotlin types; OpenAPI and Kotlin and
  TypeScript SDKs are generated from them.
- **Swappable services.**  Database, cache, files, email, SMS and pub/sub are settings.  Switch MongoDB for an
  in-memory store, or Redis for a RAM map, by changing a URL in `settings.json`.
- **In-memory testing.**  `Server.testBlocking { }` runs the real server with no port and no external services;
  `endpoint.test(auth, input)` returns the typed result.
- **Infrastructure from the definition.**  A deployment object reads your settings and writes Terraform for
  Lambda or EC2, with the database, cache, buckets and alarms they need.
- **Batteries.**  Auto-CRUD with row and field permissions, live queries, login by PIN, password, TOTP, passkey or
  OAuth, uploads and image previews, notifications, rate limiting, telemetry and an audit log.

## Documentation

The guide lives in [`docs-guide/guide`](docs-guide/guide/index.md) and is published with MkDocs.  Start with:

- [Overview](docs-guide/guide/overview.md): the mental model
- [Setup](docs-guide/guide/setup.md): Gradle configuration
- [Your first endpoint](docs-guide/guide/first-endpoint.md)
- [Condensed guide](docs-guide/guide/condensed-guide.md): every feature as one short, tested example
- [Project structure](docs-guide/guide/project-structure.md): which module does what

Kotlin samples in the guide (and in this README) are compiled, and `DriftCheckTest` fails the build when a code
block and its sample source differ.

## Installing

Artifacts are in the Lightning Kite Maven repository.  Import the stack catalog in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
        mavenCentral()
    }
    versionCatalogs {
        create("lk") { from("com.lightningkite:stack-catalog:<version>") }
    }
}
```

and depend on modules through it (`implementation(lk.lightningserver.core.asProvider())`).  Each module is versioned
on its own, so take versions from the catalog.  [Setup](docs-guide/guide/setup.md) has the full build file and
where to find the latest catalog version.

## Working on this repository

```bash
./gradlew build                     # build and test every module
./gradlew :core:test                # one module's tests
./gradlew :docs-guide:test          # compile the guide's samples and check the docs match them
./gradlew :demo:run --args="serve"  # run the demo server (Ktor); serveNetty and serveJdk also work
./gradlew :demo:run --args="sdk"    # regenerate the demo's client SDK
```

Releases are made by the `lk` tool, which owns every module's version; don't publish by hand.  Notable changes go
in [CHANGELOG.md](CHANGELOG.md).

## License

Apache 2.0.  See [LICENSE.txt](LICENSE.txt).
