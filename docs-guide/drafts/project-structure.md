> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

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
Engine  ←──────────── KtorEngine / NettyEngine / JdkServerEngine
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

**`.start(...)`** begins serving requests (or, for `AwsAdapter`, generates Terraform).

Because the definition and the engine are decoupled, the same `ServerBuilder` can be:

- Started under `KtorEngine` with Netty for local development.
- Deployed via `AwsAdapter` to AWS Lambda with zero code changes.
- Run inside a JUnit test using `LocalEngine` (no ports, no infrastructure) through the
  `.test {}` / `.testBlocking {}` helpers.

---

## Framework module catalog

Lightning Server's own Gradle project (`settings.gradle.kts`) contains the following
modules.  This is what you add to your dependency block.

### Core

| Module | Type | What it provides |
|---|---|---|
| `core` | JVM | HTTP handling, serialization, settings, service abstractions (`Database`, `Cache`, `Files`, `Email`, `Sms`), `ServerBuilder`, `ServerDefinition`, interceptors, tasks, schedules |
| `core-shared` | KMP | Base types shared with clients: `LSError`, `HttpMethod`, `MultiplexMessage`; the `PrincipalType` interface |

### Typed endpoints

| Module | Type | What it provides |
|---|---|---|
| `typed` | JVM | `ApiHttpHandler`, OpenAPI/kschema generation, SDK generators, `MetaEndpoints`, `ModelRestEndpoints`, `FunnelEndpoints` |
| `typed-shared` | KMP | `BulkRequest`, `BulkResponse`, model types shared with generated SDKs |

### Authentication

| Module | Type | What it provides |
|---|---|---|
| `auth` | JVM | `AuthEndpoints`, `PrincipalType` resolution, session storage, bearer token issuance |
| `auth-shared` | KMP | `AuthRequirement`, proof types, session token models |

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
| `sessions` | JVM | Session management, proof accumulation, strength thresholds |
| `sessions-shared` | KMP | Proof models, session token types |
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
| `engine-local` | JVM (test scope) | `LocalEngine`, the `.test {}` / `.testBlocking {}` helpers, `MapCache` and other mock implementations |
| `engine-ktor` | JVM | `KtorEngine` — Ktor + Netty/CIO; recommended for local development |
| `engine-netty` | JVM | `NettyEngine` — Netty without Ktor |
| `engine-jdk-server` | JVM | `JdkEngine` — pure JDK HTTP server, no extra runtime |
| `engine-aws-serverless` | JVM | `AwsAdapter` — generates Terraform for API Gateway + Lambda |
| `deploy-aws-ec2` | JVM | EC2-specific deployment helpers |

---

## The paired module pattern

Most features have two Gradle modules: `X` (JVM server logic) and `X-shared`
(Kotlin Multiplatform types).

```
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
my-app/
├── settings.gradle.kts          # includes :shared and :server
├── gradle/
│   └── libs.versions.toml
│
├── shared/                      # Kotlin Multiplatform module
│   └── src/commonMain/kotlin/
│       └── com/example/myapp/
│           ├── models/
│           │   ├── User.kt      # @Serializable data classes
│           │   └── Post.kt
│           └── auth/
│               └── UserAuth.kt  # PrincipalType declaration
│
└── server/                      # JVM module
    └── src/main/kotlin/
        └── com/example/myapp/
            ├── Server.kt        # ServerBuilder object
            ├── Main.kt          # main() + engine startup
            └── endpoints/
                ├── PostEndpoints.kt
                └── AdminEndpoints.kt
```

**`settings.gradle.kts`:**

```kotlin
rootProject.name = "my-app"
include(":shared")
include(":server")
```

**`:shared/build.gradle.kts`:**

```kotlin
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
            implementation("com.lightningkite.lightningserver:auth-shared:$lightningServerVersion")
            implementation("com.lightningkite.lightningserver:typed-shared:$lightningServerVersion")
        }
    }
}
```

**`:server/build.gradle.kts`:**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(project(":shared"))
    ksp("com.lightningkite.lightningserver:processor:$lightningServerVersion")
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

## Why the shared/server split matters for auth

`PrincipalType<SUBJECT, ID>` is the bridge between your user model and the auth system.
It lives in `core-shared` so that generated SDKs and KMP clients can reference it, but
its implementation calls `database()` which requires a `ServerRuntime` context:

```kotlin
// In :shared — no server deps, compiles for any KMP target
object UserAuth : PrincipalType<User, Uuid> {
    override val idSerializer     = Uuid.serializer()
    override val subjectSerializer = User.serializer()
    override val name             = "User"

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): User =
        Server.userInfo.table().get(id) ?: throw NotFoundException()
}
```

If `UserAuth` lived in `:server` it could not be imported by a KMP client that needs to
know the principal type for SDK generation.  Keeping it in `:shared` lets both sides
reference the same declaration.

The same applies to your model classes: `@Serializable data class User(...)` must be in
`:shared` so that the generated TypeScript or Kotlin SDK can import `User` with the
identical field structure.

> **Rule of thumb**: anything that the generated SDK or a KMP client imports must be in
> `:shared`.  Everything that touches `ServerBuilder`, endpoints, engines, or services
> belongs in `:server`.

---

## The `ksp` processor

The `:processor` artifact is a KSP (Kotlin Symbol Processing) annotation processor that
generates the `DataClassPaths` DSL used by `condition {}` and `modification {}`.
It must be applied in any module where you annotate a class with `@GenerateDataClassPaths`:

```kotlin
// build.gradle.kts
dependencies {
    ksp("com.lightningkite.lightningserver:processor:$lightningServerVersion")
}
```

Apply it in `:shared` if your model classes live there (they usually do), and optionally
in `:server` as well if you have server-only models.

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
  sequence and `settings.json` format.  See [Running Your Server](../guide/running.md).
- **AWS Deployment** — `AwsAdapter` generates Terraform from your declared `setting(...)`
  entries.  See [AWS Deployment](../guide/aws-deployment.md).
- **Testing** — `LocalEngine` and the `.test {}` helpers let you exercise the full
  `ServerDefinition` (interceptors, tasks, typed endpoints) in a JUnit test.
  See [Testing Your Server](../guide/testing.md).
