# Project Structure & Modules

This chapter explains how Lightning Server itself is organized into modules and how
you should organize your own application in a Gradle multi-project build.  Understanding
the paired module pattern and the build pipeline makes it clear why the framework feels
the same whether you run under Ktor, Netty, or inside a JUnit test.

---

## The build-and-run pipeline

Every Lightning Server application follows the same four-step lifecycle:

```
ServerBuilder (Kotlin object)
        │
        │  .build()
        ▼
ServerDefinition  ←──── all endpoints, tasks, schedules,
        │               settings, and interceptors collected
        │  Engine(definition)
        ▼
Engine  ←──────────── KtorEngine / NettyEngine / JdkEngine
        │              AwsAdapter / LocalEngine (tests)
        │  .settings.loadFromFile(...)
        │  .start(...)
        ▼
Running server
```

**`ServerBuilder`** is a plain Kotlin `object` subclass.  Property declarations and
`init {}` blocks register endpoints, tasks, schedules, interceptors, and settings using
DSL operators (`bind`, `include`, `module`, `install`).  Nothing executes at declaration
time — the builder is a blueprint.

**`.build()`** traverses the entire `ServerBuilder` graph (including included sub-builders)
and produces a `ServerDefinition` — a plain data structure holding every registered
endpoint, task, schedule, interceptor, and setting.  This step is fast and allocation-only.

**Engine** receives the `ServerDefinition` and knows how to execute it in its target
environment.

**`.settings.loadFromFile(...)`** reads `settings.json` and resolves each `setting(...)`
declaration to a concrete service instance (`Database`, `Cache`, `Files`, etc.).  On first
run the engine writes a default `settings.json` if none exists.

**`.start(...)`** begins serving requests.

Because the definition and the engine are decoupled, the same `ServerBuilder` can be:

- Started under `KtorEngine` with Netty for local development.
- Deployed via `AwsAdapter` to AWS Lambda with zero code changes (Terraform is generated
  separately via `TerraformAwsServerlessBuilder` in the same module).
- Run inside a JUnit test using `LocalEngine` (no ports, no infrastructure) through the
  `.test {}` / `.testBlocking {}` helpers in `core`.

---

## Framework module catalog

Lightning Server's own Gradle project (`settings.gradle.kts`) contains the following
modules.  These are what you reference in your dependency blocks.

### Core

| Module | Type | What it provides |
|---|---|---|
| `core` | JVM | HTTP handling, serialization, settings, service abstractions (`Database`, `Cache`, `Files`, `Email`, `Sms`), `ServerBuilder`, `ServerDefinition`, interceptors, tasks, schedules, `TestRunner`, `test {}` / `testBlocking {}` helpers |
| `core-shared` | KMP | Base types shared with clients: `LSError`, `HttpMethod` |

### Typed endpoints

| Module | Type | What it provides |
|---|---|---|
| `typed` | JVM | `ApiHttpHandler`, `ApiWebsocketHandler`, OpenAPI/kschema generation, SDK generators, `MetaEndpoints`, `ModelRestEndpoints`, `FunnelEndpoints` |
| `typed-shared` | KMP | `BulkRequest`, `BulkResponse`, client-side model rest endpoint interfaces shared with generated SDKs |

### Authentication

| Module | Type | What it provides |
|---|---|---|
| `auth` | JVM | `PrincipalType`, `Authentication`, `AuthRequirement`, bearer-token resolution, `testAuth` helper |
| `auth-shared` | KMP | `Scope` — the OAuth/permission scope type |

### Files & Media

| Module | Type | What it provides |
|---|---|---|
| `files` | JVM | `UploadEarlyEndpoint`, signed-URL generation, multi-backend file storage |
| `files-shared` | KMP | `ServerFile`, `ServerFileWithMetadata` types |
| `media` | JVM | Image processing (resize, crop, convert) via Scrimage |
| `media-shared` | KMP | Media operation types |

### Sessions & proofs

| Module | Type | What it provides |
|---|---|---|
| `sessions` | JVM | `AuthEndpoints`, session management, proof accumulation, strength thresholds |
| `sessions-shared` | KMP | Proof models, session token types, client-side auth endpoint interfaces |
| `sessions-email` | JVM | Email magic-link / PIN proof endpoint |
| `sessions-sms` | JVM | SMS PIN proof endpoint |
| `sessions-oauth` | JVM | OAuth 2.0 proof endpoint (GitHub, Google, etc.) |
| `sessions-oauth-shared` | KMP | OAuth token types |

### Notifications

| Module | Type | What it provides |
|---|---|---|
| `notifications` | JVM | Push notification dispatch |
| `notifications-shared` | KMP | Notification payload types |

### Add-ons

| Module | Type | What it provides |
|---|---|---|
| `ratelimit` | JVM | `RateLimitInterceptor` and `RateLimitSettings` |
| `secret-source-aws` | JVM | AWS Secrets Manager integration for `settings.json` values |

### Engines

| Module | Target | Use case |
|---|---|---|
| `engine-local` | JVM (test scope) | `LocalEngine` — the in-process engine backing the test helpers in `core` |
| `engine-ktor` | JVM | `KtorEngine` — Ktor + Netty/CIO; recommended for local development |
| `engine-netty` | JVM | `NettyEngine` — Netty without Ktor |
| `engine-jdk-server` | JVM | `JdkEngine` — pure JDK HTTP server, no extra runtime |
| `engine-aws-serverless` | JVM | `AwsAdapter` (Lambda request handler) + `TerraformAwsServerlessBuilder` (Terraform generation) |
| `deploy-aws-ec2` | JVM | EC2-specific deployment helpers |

---

## The paired module pattern

Most features have two Gradle modules: `X` (JVM server logic) and `X-shared`
(Kotlin Multiplatform types).

```
Illustrative — not a drift-checked sample.

typed               typed-shared
  │   ←─ depends on ──   │
  │                       │
  JVM handler logic       KMP types (BulkRequest, models, …)
  (ApiHttpHandler,        usable on JVM, JS, iOS, Android
   MetaEndpoints, …)
```

**Why two modules?**

Your generated Kotlin SDK (or any KMP client library) needs to share the same model types
with the server so that serialization stays in sync.  If you put `BulkRequest` in the JVM
module, a Kotlin/JS client cannot import it.  By keeping types in `X-shared` and handlers
in `X`, both the server and the clients can depend on `X-shared` without pulling in
JVM-only libraries.

**For your own application**, the same reasoning applies: put shared model classes in a
KMP `*-shared` module, and put your `ServerBuilder` and endpoint logic in a JVM module.

---

## Recommended application layout

A typical Lightning Server application with a generated Kotlin SDK or KMP client looks like:

```
Illustrative directory tree.

my-app/
├── settings.gradle.kts          # includes :shared and :server
├── gradle/
│   └── libs.versions.toml
│
├── shared/                      # Kotlin Multiplatform module
│   └── src/commonMain/kotlin/
│       └── com/example/myapp/
│           └── models/
│               ├── User.kt      # @Serializable data classes
│               └── Post.kt
│
└── server/                      # JVM module
    └── src/main/kotlin/
        └── com/example/myapp/
            ├── Server.kt        # ServerBuilder object
            ├── UserAuth.kt      # PrincipalType implementation
            ├── Main.kt          # main() + engine startup
            └── endpoints/
                ├── PostEndpoints.kt
                └── AdminEndpoints.kt
```

**`settings.gradle.kts`:**

```kotlin
// Illustrative — not a drift-checked sample.
rootProject.name = "my-app"
include(":shared")
include(":server")
```

**`:shared/build.gradle.kts`:**

```kotlin
// Illustrative — not a drift-checked sample.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    jvm()
    // js(IR) { browser() }  // add targets as needed

    sourceSets {
        commonMain.dependencies {
            implementation("com.lightningkite.lightningserver:core-shared:$lightningServerVersion")
            implementation("com.lightningkite.lightningserver:typed-shared:$lightningServerVersion")
            // sessions-shared if you need auth proof types on the client side
        }
    }
}
```

**`:server/build.gradle.kts`:**

```kotlin
// Illustrative — not a drift-checked sample.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(project(":shared"))
    // KSP processor is from service-abstractions, not lightning-server itself
    ksp("com.lightningkite.services:database-processor:$serviceAbstractionsVersion")
    implementation("com.lightningkite.lightningserver:core:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:typed:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:auth:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:sessions:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:sessions-email:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:engine-ktor:$lightningServerVersion")

    testImplementation("com.lightningkite.lightningserver:engine-local:$lightningServerVersion")
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
```

---

## The shared/server split for models and auth

Your **model classes** (`@Serializable data class User(...)`) live in `:shared` because:

- The generated TypeScript or Kotlin SDK needs `User` with the exact same field structure.
- The model itself has no server dependencies — it is a plain data class.

Your **`PrincipalType` implementation** (e.g., `UserAuth`) lives in `:server`, not
`:shared`, because `PrincipalType` is defined in the JVM-only `auth` module:

```kotlin
// Illustrative.
// UserAuth lives in :server because PrincipalType is a JVM-only type from :auth.
// The model (User) can still live in :shared — UserAuth just references it.
object UserAuth : PrincipalType<User, Uuid> {
    override val idSerializer     = Uuid.serializer()
    override val subjectSerializer = User.serializer()

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): User =
        Server.users.table().get(id) ?: throw NotFoundException()
}
```

> **Rule of thumb**: anything the generated SDK or a KMP client needs to import
> (model classes, shared types) must be in `:shared`.  Everything that touches
> `ServerBuilder`, `PrincipalType`, endpoints, engines, or services belongs in `:server`.

---

## The `ksp` processor

`@GenerateDataClassPaths` triggers a KSP (Kotlin Symbol Processing) annotation processor
that generates the `DataClassPaths` DSL used by `condition {}` and `modification {}`.
The processor artifact ships with the **Service Abstractions** library (not Lightning Server
itself):

```kotlin
// Illustrative — not a drift-checked sample.
// In any module where you annotate classes with @GenerateDataClassPaths:
dependencies {
    ksp("com.lightningkite.services:database-processor:$serviceAbstractionsVersion")
}
```

Apply it in `:shared` if your model classes live there (they usually do), and in `:server`
if you have server-only models.

---

## First-run double-start pattern

When you run your application for the first time:

1. **First run** — no `settings.json` exists.  The engine writes a default file and exits
   (or serves with in-memory defaults depending on the engine).
2. **Second run** — `settings.json` is loaded; each `setting(...)` declaration resolves to
   the concrete service it names.

This is a Lightning Server convention: the generated `settings.json` should always be valid
enough for the application to start.  The default `Database.Settings()` resolves to a JSON
file database; the default `Cache.Settings()` resolves to an in-memory map.  Production
deployments override these by editing `settings.json` or by using `secret-source-aws` to
pull values from AWS Secrets Manager.

---

## Gradle tasks reference

```bash
# Build all modules
./gradlew build

# Build a single module
./gradlew :server:build

# Run all tests
./gradlew test

# Run tests for one module
./gradlew :server:test

# Run the server (Ktor engine)
./gradlew :server:run --args="serve"

# Generate the Kotlin / TypeScript SDK
./gradlew :server:run --args="sdk"

# Publish to local Maven (useful when testing against other local projects)
./gradlew publishToMavenLocal
```

---

## What's Next

- **Running Your Server** — the `KtorEngine`, `NettyEngine`, and `JdkEngine` startup
  sequence and `settings.json` format.  See [Running Your Server](running.md).
- **AWS Deployment** — `AwsAdapter` and `TerraformAwsServerlessBuilder` handle Lambda
  deployment and Terraform generation.  See [AWS Deployment](aws-deployment.md).
- **Testing** — `LocalEngine` and the `.test {}` helpers from `core` let you exercise the
  full `ServerDefinition` (interceptors, tasks, typed endpoints) in a JUnit test.
  See [Testing Your Server](testing.md).
