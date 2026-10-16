[![Nightly](https://img.shields.io/maven-metadata/v?strategy=latestProperty&label=lightningkite-maven-nightly&metadataUrl=https://lightningkite-maven.s3.us-west-2.amazonaws.com/com/lightningkite/lightningserver/core/maven-metadata.xml)](https://lightningkite-maven.s3.us-west-2.amazonaws.com/com/lightningkite/lightningserver/core/maven-metadata.xml)

# Lightning Server

Lightning Server is a Kotlin server framework for building production backends
faster.  You define typed endpoints, service settings, and authentication rules
in plain Kotlin; the framework handles HTTP routing, SDK generation, unit
testing (no running server required), and — the standout feature — derives your
complete AWS Terraform infrastructure from the same server definition.  Add a
service setting; get the matching cloud resource automatically.

**Status:** pre-release (version 5).  The API is settling and breaking changes
are still possible.  The nightly badge above tracks the latest published build.

---

## Headline Differentiators

- **Typed endpoints end-to-end** — input, output, auth user, and error cases
  are all Kotlin types; the framework auto-generates an OpenAPI spec and
  type-safe client SDKs from them.
- **Swappable service abstractions** — Database, Cache, Files, Email, SMS, and
  PubSub are declared as settings.  Swap implementations (MongoDB ↔ in-process
  JSON, Redis ↔ RAM map) by changing one URL string; no application code
  changes.
- **Infrastructure from your server definition** — a
  `TerraformAwsServerlessBuilder` subclass reads your service settings and
  emits `.tf.json` files for Lambda, API Gateway, S3, DynamoDB, MongoDB Atlas,
  CloudWatch alarms, and more.  The Terraform configuration is derived, not
  hand-written.
- **First-class unit testing** — `SERVER.test {}` starts an ephemeral
  in-process engine; `endpoint.test(auth, input)` returns typed output
  directly.  No Docker, no ports, no external services needed.
- **Traceable executions** — every request, WebSocket phase, task, and schedule
  tick runs as an execution that records what caused it, even across task
  queues, so logs, telemetry, and the optional audit log can trace work back to
  the request that started it.

---

## Quick Start

Add these imports at the top of your file:

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.typed.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
```

The minimal server is a `ServerBuilder` object:

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#hello-server -->
```kotlin
object HelloServer : ServerBuilder() {

    // GET / — responds with a plain-text greeting
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello, Lightning Server!")
    }
}
```

Test it without starting a server:

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#hello-server-test -->
```kotlin
fun helloServerTest() = runBlocking {
    HelloServer.test(settings = {}) {
        val response = HelloServer.root.test()
        check(response.body?.text() == "Hello, Lightning Server!")
    }
}
```

For the full walkthrough — imports, typed endpoints, auth, database, testing,
and AWS deployment — start with the guide:
**[Your First Endpoint](docs-guide/guide/first-endpoint.md)**

---

## Documentation (Guide)

The `docs-guide/` module contains compiled-sample chapters: every code block is
drift-checked against the compiled source on every CI run.

| Chapter | What it covers |
|---|---|
| [Your First Endpoint](docs-guide/guide/first-endpoint.md) | `ServerBuilder`, `HttpHandler`, `HttpResponse`, running your first test |
| [Routing & Path Parameters](docs-guide/guide/routing.md) | Nested paths, all HTTP methods, typed path args, sub-builders, query params |
| [Typed Endpoints, Errors & SDK Generation](docs-guide/guide/typed-endpoints.md) | `ApiHttpHandler`, `LSError`, `errorCases`, success codes, SDK generation |
| [Services & Settings](docs-guide/guide/services.md) | Declaring and swapping service settings; in-process RAM mocks for tests |
| [Database & the Query DSL](docs-guide/guide/database.md) | Type-safe `condition {}` / `modification {}` DSL, `@Queryable`, CRUD |
| [Authentication & Sessions](docs-guide/guide/auth.md) | `PrincipalType`, `User.require()`, reading `auth.id` / `auth.fetch()` in handlers |
| [Testing Your Server](docs-guide/guide/testing.md) | `SERVER.test {}`, `HttpHandler.test()`, `ApiHttpHandler.test(auth, input)`, `@Test` |
| [Deploying to AWS](docs-guide/guide/aws-deployment.md) | `AwsAdapter`, `TerraformAwsServerlessBuilder`, service → Terraform, deploy workflow |

> The `docs/` directory contains reference documentation by topic.  Its code
> samples are not compiled, so where it and the guide differ, trust the guide.

---

## Build & Test Commands

```bash
# Build all modules
./gradlew build

# Run checks (tests + linting)
./gradlew check

# Assemble without running tests
./gradlew assemble

# Run just the docs-guide tests (verifies all guide code blocks)
./gradlew :docs-guide:test

# Run tests for a specific module
./gradlew :core:test

# Run the demo server (Ktor — recommended for development)
./gradlew :demo:run --args="serve"

# Run the demo server (Netty)
./gradlew :demo:run --args="serveNetty"

# Run the demo server (JDK)
./gradlew :demo:run --args="serveJdk"

# Generate client SDKs from the demo
./gradlew :demo:run --args="sdk"

# Publish to local Maven
./gradlew publishToMavenLocal
```

---

## Module Overview

The project follows a paired pattern: most features have a `*-shared`
multiplatform module (KMP; used by client SDKs) and a JVM module.

| Module | Purpose |
|---|---|
| `core` / `core-shared` | Base HTTP types, routing, settings, serialization |
| `typed` / `typed-shared` | Typed `ApiHttpHandler`, OpenAPI spec, SDK generation |
| `auth` / `auth-shared` | `PrincipalType`, `AuthRequirement`, token handling |
| `audit` / `audit-shared` | Audit logging: request origins, which fields of which records reached clients, queries, changes, and auth events |
| `sessions*` | Pre-built session flows: password, email and SMS PINs, TOTP, WebAuthn, backup codes, known devices, OAuth |
| `files` / `files-shared` | File upload/download with S3, Azure, and local backends |
| `media` / `media-shared` | Image processing |
| `notifications` / `notifications-shared` | Event-driven notifications over email, SMS, push, and in-app |
| `ratelimit` | Request rate limiting |
| `engine-local` | Base engine for single-process deployments (unit tests use `TestRunner` from `core`) |
| `engine-ktor` | Ktor-based HTTP server (Netty or CIO) |
| `engine-netty` | Standalone Netty HTTP server |
| `engine-jdk-server` | Pure JDK HTTP server |
| `engine-aws-serverless` | AWS Lambda handler (`AwsAdapter`) + Terraform generation |
| `deploy-aws-ec2` | Terraform generation for EC2 deployments, single-instance or auto-scaling |
| `secret-source-aws` | AWS Secrets Manager integration for deployment secrets |
| `demo` | Reference implementation and integration test bed |
| `docs-guide` | Compiled-sample documentation harness |

Service implementations (MongoDB, Redis, S3, SES, etc.) live in the separate
[service-abstractions](https://github.com/lightningkite/service-abstractions)
repository and are brought in as dependencies.

---

## Adding to Your Project

Artifacts are published to the Lightning Kite Maven repository.  Add the
repository and dependency to your `build.gradle.kts`:

```kotlin
repositories {
    maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
    mavenCentral()
}

dependencies {
    // Core + typed endpoints
    implementation("com.lightningkite.lightningserver:core:<version>")
    implementation("com.lightningkite.lightningserver:typed:<version>")

    // Choose an engine for local development
    implementation("com.lightningkite.lightningserver:engine-ktor:<version>")

    // In-process engine for unit tests
    testImplementation("com.lightningkite.lightningserver:engine-local:<version>")
}
```

Replace `<version>` with the version shown in the nightly badge at the top of
this file.  The group ID is `com.lightningkite.lightningserver`; artifact IDs
match the module directory names above.

---

## License

See [LICENSE.txt](LICENSE.txt).
