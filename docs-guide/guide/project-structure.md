# Project structure

How to lay out an application, and which Lightning Server modules to depend on.

## Application layout

An app whose clients share its models has two modules: a multiplatform `shared` module for models, and a JVM
`server` module for everything else.

```
my-app/
├── settings.gradle.kts         # repositories, the lk catalog, include(":shared", ":server")
├── gradle/libs.versions.toml   # Kotlin and KSP plugins
├── shared/                     # Kotlin Multiplatform
│   └── src/commonMain/kotlin/com/example/models/
│       ├── User.kt             # @Serializable, @Queryable data classes
│       └── Post.kt
└── server/                     # JVM
    └── src/
        ├── main/kotlin/com/example/
        │   ├── Server.kt       # object Server : ServerBuilder()
        │   ├── UserAuth.kt     # PrincipalType<User, Uuid>
        │   ├── Main.kt         # main(): serve / predeploy / dev
        │   └── PostEndpoints.kt
        └── test/kotlin/com/example/
```

| Goes in `shared` | Goes in `server` |
|---|---|
| Models (`@Serializable data class User`) | `ServerBuilder` objects and endpoints |
| Enums and value types clients use | `PrincipalType` implementations (`auth` is JVM-only) |
| `@Queryable` models, so clients get the query DSL too | Engines, services, settings, `main` |

A server with no shared clients can keep its models in `server` and skip the `shared` module.

## Shared module

`shared/build.gradle.kts`, with JVM and browser targets (add `kotlin-multiplatform` to `libs.versions.toml` next
to `kotlin-jvm`):

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    jvm()
    js { browser() }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project.dependencies.platform(lk.stack.bom))
                api(lk.lightningserver.core.shared)
                api(lk.lightningserver.typed.shared)
            }
            kotlin { srcDir(file("build/generated/ksp/common/commonMain/kotlin")) }
        }
    }
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

dependencies {
    configurations.filter { it.name.startsWith("ksp") && it.name != "ksp" }.forEach {
        add(it.name, lk.services.database.processor)
    }
}

// commonMain compiles the processor's common output as plain source, so every other KSP run and compile waits for it.
tasks.matching { (it.name.startsWith("ksp") || it.name.startsWith("compile")) && it.name != "kspCommonMainKotlinMetadata" }
    .configureEach { dependsOn("kspCommonMainKotlinMetadata") }
```

The server module then adds `implementation(project(":shared"))` to the dependencies from [Setup](setup.md).
Add other `*-shared` modules (`sessions-shared`, `files-shared`, ...) to `commonMain` when clients need their types.

## Module catalog

Most features come in pairs: `X-shared` (Kotlin Multiplatform types that clients and generated SDKs also use) and
`X` (the JVM server code).  Catalog aliases are `lk.lightningserver.<module>`, with dashes becoming dots
(`engine-ktor` is `lk.lightningserver.engine.ktor`).

### Foundation

| Module | Provides |
|---|---|
| `core` | `ServerBuilder`, HTTP and WebSocket handlers, settings, serialization, tasks, schedules, interceptors, `test {}` / `testBlocking {}` |
| `core-shared` | `LSError`, `HttpMethod`, `MultiplexMessage` |
| `typed` | `ApiHttpHandler`, `ApiWebsocketHandler`, model REST endpoints, OpenAPI, SDK generation, meta endpoints |
| `typed-shared` | `BulkRequest` / `BulkResponse`, client endpoint interfaces used by generated SDKs |
| `auth` | `PrincipalType`, `AuthRequirement`, `noAuth`, `require()` |
| `auth-shared` | Scope types (`GrantedScope`, `RequiredScope`, `Subscope`) |

### Sessions and login

| Module | Provides |
|---|---|
| `sessions` | `AuthEndpoints`: sessions built from proofs, plus password, backup code, and known-device proofs |
| `sessions-shared` | Proof and session request/response types, client auth endpoint interfaces |
| `sessions-email` | Email PIN proofs |
| `sessions-sms` | SMS PIN proofs |
| `sessions-totp` | Authenticator-app (TOTP) proofs |
| `sessions-webauthn` | Passkey (WebAuthn) proofs |
| `sessions-oauth` | Login through OAuth providers |
| `sessions-oauth-shared` | OAuth request/response types |

### Features

| Module | Provides |
|---|---|
| `files` / `files-shared` | Upload endpoints and signed upload targets over a Service Abstractions file system |
| `media` / `media-shared` | Image processing (Scrimage) and `ServerFileWithMetadata` |
| `notifications` / `notifications-shared` | Event-driven notifications over email, SMS, push, and in-app |
| `audit` / `audit-shared` | Audit log of request origins, disclosed fields, queries, mutations, and auth events |
| `ratelimit` | `RateLimitInterceptor` |
| `fixtures` | Deterministic, scalable seed data for databases |

### Engines and deployment

| Module | Provides |
|---|---|
| `engine-local` | `LocalEngine`, the base of the three standalone engines (you don't depend on it directly) |
| `engine-ktor` | `KtorEngine` |
| `engine-netty` | `NettyEngine` |
| `engine-jdk-server` | `JdkEngine` (no WebSockets) |
| `engine-aws-serverless` | `AwsAdapter` for Lambda, and Terraform generation |
| `deploy-aws-ec2` | Terraform for EC2, single instance or auto scaling |
| `secret-source-aws` | AWS Secrets Manager as a secret source for deployments |

Service backends (MongoDB, Postgres, Redis, S3, SES, Twilio, ...) are Service Abstractions modules,
`lk.services.<module>`.  See [Services](services.md).

## Module dependencies

Depending on a module brings in what it depends on (all `api` dependencies), so you only list the top ones you use.

| Module | Brings in |
|---|---|
| `core` | `core-shared` |
| `auth` | `core`, `auth-shared` |
| `typed` | `core`, `auth`, `typed-shared` |
| `sessions` | `typed`, `auth`, `sessions-shared` |
| `sessions-email`, `-sms`, `-totp`, `-webauthn` | `sessions` |
| `sessions-oauth` | `sessions`, `sessions-oauth-shared` |
| `files` | `typed`, `files-shared` |
| `media` | `files`, `media-shared` |
| `notifications` | `typed`, `notifications-shared` |
| `audit` | `typed`, `sessions`, `audit-shared` |
| `fixtures` | `typed` |
| `ratelimit`, `secret-source-aws`, `engine-aws-serverless` | `core` |
| `engine-ktor`, `engine-netty`, `engine-jdk-server`, `deploy-aws-ec2` | `engine-local` → `core` |
| `typed-shared` | `core-shared`, `auth-shared` |
| `sessions-shared` | `core-shared`, `auth-shared`, `typed-shared` |
| `files-shared` | `typed-shared` |
| `media-shared` | `files-shared` |
| `notifications-shared` | `core-shared`, `typed-shared` |
| `audit-shared` | `auth-shared` |
| `auth-shared` | `core-shared` |

## Next

- [Setup](setup.md): repositories, catalogs, and versions
- [Running](running.md)
- [Testing](testing.md)
