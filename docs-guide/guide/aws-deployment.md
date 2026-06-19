# Deploying to AWS — Terraform from Your Server Definition

Lightning Server's flagship deployment target is AWS Lambda.  The central idea:
your server definition describes what services it needs (a database, a cache, a
file store), and the framework derives the complete Terraform infrastructure from
that description automatically.  Add a service setting; get the matching AWS
resource for free.

> **How these examples work.**  Every _Kotlin_ code block in this chapter is a
> named region from a compiled, tested source file; `./gradlew :docs-guide:test`
> asserts byte-equality so it can never silently drift.
>
> The Lambda handler, the deployment object, the Terraform configuration, and
> the CLI commands are all **illustrative** — they write files or require AWS
> credentials at runtime and cannot be unit-asserted in-process.  They are
> described accurately and marked clearly.  See [W5/W12](#warts) for the
> underlying constraint.

---

## The Mental Model

Three things work together:

```
Server Definition  ──────►  AwsAdapter (Lambda entrypoint)
       │
       └──►  TerraformAwsServerlessBuilder  ──►  terraform/*.tf.json
                   (deployment object)
```

1. **Server definition** — the `ServerBuilder` object you already know from
   earlier chapters.  It declares service settings; it does not know or care
   where those services live.
2. **`AwsAdapter`** — a one-line class that wraps your server definition and
   implements `RequestStreamHandler`, the AWS Lambda entry point.  It loads
   settings at cold-start from S3/Secrets Manager and routes each Lambda
   invocation to the right handler (HTTP, WebSocket, scheduled task, or
   background task).
3. **Deployment object** — a `TerraformAwsServerlessBuilder` subclass you write
   once per deployment environment (staging, production, etc.).  Its `settings()`
   override calls service-specific Terraform extension functions.  Calling
   `.deploy()` on it runs `terraform init/plan/apply` and uploads your JAR.

---

## The Server Definition

The server definition is **engine-agnostic**.  The same object runs under Ktor
locally and under Lambda in production.  The only difference is which engine
wraps it and how settings are loaded.

Add the imports and declare your server:

<!-- sample: com/lightningkite/lightningserver/guide/samples/AwsDeploymentSamples.kt#aws-server-imports -->
```kotlin
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.plainText
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Database
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/AwsDeploymentSamples.kt#aws-server-definition -->
```kotlin
// The server definition is engine-agnostic: the same object is used for local
// Ktor/Netty development and for the Lambda runtime via AwsAdapter.
object ApiServer : ServerBuilder() {

    // Each setting declares a service the server requires.
    // The deployment's settings() override resolves each one to a concrete
    // cloud resource — a DynamoDB table, an S3 bucket, a MongoDB cluster, etc.
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    val root = path.get bind HttpHandler {
        HttpResponse.plainText("OK")
    }
}
```

`database` and `cache` are `ServerSetting` declarations.  Their default values
(`Database.Settings()`, `Cache.Settings()`) are used locally.  In the deployment
object, `settings()` replaces them with cloud-backed values expressed as
Terraform interpolations (`$${aws_dynamodb_table.foo.arn}`, connection strings,
etc.).  The server code itself never changes.

---

## The Lambda Entrypoint (Illustrative)

> **Illustrative — not drift-checked.**  This file requires `engine-aws-serverless`
> on the classpath and AWS credentials at instantiation time.

Add `engine-aws-serverless` to your module's dependencies:

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.lightningkite.lightningserver:engine-aws-serverless:version-5-SNAPSHOT")
    // ... your other dependencies
}
```

Then write one class — the Lambda handler:

```kotlin
// src/main/kotlin/com/example/api/AwsHandler.kt
package com.example.api

import com.lightningkite.lightningserver.engine.awsserverless.AwsAdapter

class AwsHandler : AwsAdapter(ApiServer.build())
```

That is the entire file.  `AwsAdapter` implements `RequestStreamHandler` and
handles the rest:

- **Cold start**: reads `settings.json` (or `settings.enc`) from the Lambda
  package, or fetches it from AWS Secrets Manager via
  `LIGHTNING_SERVER_SETTINGS_SECRET_ID`.
- **Dispatch**: inspects the incoming JSON payload and routes to the HTTP
  handler, WebSocket handler, background task runner, or scheduler as
  appropriate.
- **SnapStart**: implements the CRaC `Resource` interface so Lambda SnapStart
  pre-warms connections before the snapshot.

The `handler` property in your deployment object is set to `AwsHandler::class`,
which is the fully-qualified class name Lambda needs to invoke.

---

## The Deployment Object (Illustrative)

> **Illustrative — not drift-checked.**  This object requires
> `engine-aws-serverless` and external terraform provider credentials.

Create a deployment object in a `deploy.kt` (or similar) file.  It extends
`TerraformAwsServerlessBuilder` (or `TerraformAwsServerlessDomainBuilder` if you
have a custom domain):

```kotlin
// src/main/kotlin/com/example/api/deploy.kt
package com.example.api

import com.lightningkite.lightningserver.engine.awsserverless.AwsAdapter
import com.lightningkite.lightningserver.terraform.*
import com.lightningkite.lightningserver.terraform.awsserverless.*
import com.lightningkite.services.cache.dynamodb.awsDynamoDb
import com.lightningkite.services.data.EmailAddress
import com.lightningkite.services.data.toEmailAddress
import com.lightningkite.services.database.mongodb.mongodbAtlasFree
import com.lightningkite.services.files.s3.awsS3Bucket
import software.amazon.awssdk.regions.Region
import java.io.File
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.days

object Production : TerraformAwsServerlessDomainBuilder<ApiServer>(ApiServer) {

    // Which class to use as the Lambda handler — must extend AwsAdapter.
    override val handler: KClass<out AwsAdapter> = AwsHandler::class

    // Human-readable name; also becomes the Terraform resource prefix.
    override val displayName: String = "My API"

    // AWS region for all resources.
    override val region: Region = Region.US_EAST_1

    // S3 bucket that stores the Terraform state file.
    override val storageBucket: String = "my-company-terraform-state"

    // Route 53 hosted zone and API domain name.
    override val domainZone: String = "example.com"
    override val domain: String = "api.example.com"

    // SNS alerts go here if Lambda errors spike.
    override val emergencyContact: EmailAddress = "ops@example.com".toEmailAddress()

    override val debug: Boolean = false

    // How credentials and secrets are retrieved/stored during deployment.
    // AwsSecretSource reads from AWS Secrets Manager and caches locally.
    override val secretsSource: SecretSource =
        AwsSecretSource(profile = "my-aws-profile", idPrefix = "my-api", region = region)

    // settings() is the heart of the deployment object.
    // Each call here corresponds to one service setting on ApiServer
    // and generates the matching Terraform resources.
    override fun ApiServer.settings() {
        // database.mongodbAtlasFree() emits:
        //   - mongodbatlas_project, mongodbatlas_advanced_cluster, mongodbatlas_database_user
        //   - random_password for the DB user
        //   - fulfills "database" setting with the connection string interpolation
        database.mongodbAtlasFree(orgId = "your-org-id-here", zoneName = "Zone 1")

        // cache.awsDynamoDb() emits no Terraform table resource — DynamoDbCache
        // auto-creates the table on first use.  It does fulfill the "cache" setting
        // with "dynamodb://region/prefix_cache".
        cache.awsDynamoDb()

        // secretBasis.generated() emits a random_password resource and fulfills
        // the secret basis setting used for signing tokens.
        secretBasis.generated()
    }
}
```

### How `settings()` Drives Infrastructure

Each call in `settings()` is a **Terraform extension function** defined by the
service implementation library.  The pattern is always:

```
settingNeed.serviceFunction(config...)
```

What happens inside each extension function:
1. **`fulfillSetting(name, jsonElement)`** — stores a JSON value (often a
   Terraform interpolation like `$${resource.arn}`) as the runtime setting value.
   These are collected into `settings.json`, AES-encrypted, and bundled into
   the Lambda ZIP.
2. **`emit(context) { ... }`** — adds Terraform resource blocks to a named
   `.tf.json` file.  For S3 that means `aws_s3_bucket`, its CORS configuration,
   and IAM policy statements granting Lambda `s3:*` access.  For MongoDB Atlas
   that means `mongodbatlas_project`, `mongodbatlas_advanced_cluster`, a database
   user, and a `random_password`.
3. **`require(TerraformProviderImport.*)`** — registers external providers (e.g.
   `mongodbatlas`, `aws`, `random`) so the `terraform {}` block is generated
   with the right `required_providers`.

Adding a service setting to `ApiServer` and calling its extension in `settings()`
is the only step required to provision that infrastructure.

### Common Service Extension Functions

| Service setting type | Extension function | Resources created |
|---|---|---|
| `Database.Settings` | `mongodbAtlasFree(orgId)` | Atlas free cluster, DB user, password |
| `Database.Settings` | `mongodbAtlas(orgId, minSize, maxSize)` | Atlas auto-scaling cluster |
| `Cache.Settings` | `awsDynamoDb()` | No table (auto-created by DynamoDbCache) |
| `PublicFileSystem.Settings` | `awsS3Bucket(signedUrlDuration)` | S3 bucket, CORS, IAM policy |
| `TerraformNeed<SecretBasis>` | `secretBasis.generated()` | `random_password` (32 chars) |

Always confirm the extension function is imported from the correct service
library — they live in separate artifacts (e.g. `files-s3`, `cache-dynamodb`,
`database-mongodb`).

---

## The Deploy Workflow (Illustrative)

> **Illustrative — not drift-checked.**  Requires AWS credentials, terraform/tofu
> CLI, and a built Lambda JAR.

### 1. Build the Lambda JAR

The Lambda package is an exploded JAR — all classes and `lib/` dependencies laid
flat — produced by a Gradle task:

```bash
./gradlew :your-module:lambda
# Output: build/dist/lambda/   (classes + lib/*.jar)
```

The `lambda` Sync task unzips your `jar` output and copies
`configurations.runtimeClasspath` into `lib/`.  The `TerraformAwsServerlessBuilder`
then zips this directory and uploads it to S3 as part of `terraform apply`.

### 2. Generate and Review Terraform

Run the `write()` function to emit the `.tf.json` files without deploying:

```kotlin
// Standalone main or Gradle exec task:
object ProductionWrite {
    @JvmStatic fun main(vararg args: String) = Production.write()
}
```

Or add a Gradle task:

```kotlin
// build.gradle.kts
tasks.create("writeTerraform", JavaExec::class.java) {
    group = "deploy"
    classpath(sourceSets.main.get().runtimeClasspath)
    mainClass.set("com.example.api.ProductionWriteKt")
    workingDir(project.rootDir)
}
```

The generated files land in `terraform/my-api/` (the value of `terraformRoot`).
Review them — they are plain JSON Terraform configuration.

### 3. Edit Secrets and Variables

Before the first deploy, set up credentials:

```bash
./gradlew :your-module:run --main=com.example.api.ProductionEditKt
# or: object ProductionEdit { @JvmStatic fun main(vararg args: String) = Production.editVars() }
```

`editVars()` opens an interactive terminal editor prompting for AWS credentials
(access key or profile), MongoDB Atlas API keys, and any other variables declared
with `byVariable()`.  Values are stored in the `EncryptedFileSecretSource` (or
`AwsSecretSource`) so subsequent runs don't re-prompt.

### 4. Deploy

```bash
./gradlew :your-module:lambda            # build the exploded JAR
./gradlew :your-module:run --main=com.example.api.ProductionDeployKt
```

```kotlin
// deploy.kt
object ProductionDeploy {
    @JvmStatic fun main(vararg args: String) {
        ProcessBuilder("./gradlew", "your-module:lambda").inheritIO().start().waitFor()
        Production.deploy()              // init → plan → prompt → apply
    }
}
```

`deploy()` calls, in order:
1. `terraform init -upgrade` — downloads providers.
2. `terraform plan -out=plan.tfplan` — shows what will change.
3. Waits for you to press Enter (pass `autoApprove = true` to skip).
4. `terraform apply plan.tfplan` — applies.

On subsequent deploys, only the Lambda ZIP changes if no infrastructure changed.

---

## What Gets Created

A minimal deployment (one Lambda, no custom domain) produces:

| Resource | Purpose |
|---|---|
| `aws_lambda_function.main` | The Lambda function (Java 17, SnapStart enabled) |
| `aws_lambda_alias.main` (prod) | Stable alias pointing to the published version |
| `aws_s3_bucket.lambda_bucket` | Bucket holding the Lambda ZIP |
| `aws_apigatewayv2_api.http` | HTTP API Gateway (proxies all routes to Lambda) |
| `aws_apigatewayv2_api.ws` | WebSocket API Gateway |
| `aws_iam_role.main_exec` | Execution role with CloudWatch Logs + DynamoDB permissions |
| `aws_cloudwatch_log_group.main` | Lambda log group (30-day retention) |
| `aws_sns_topic.emergency` | Alerts topic (email subscription to `emergencyContact`) |
| `aws_cloudwatch_metric_alarm.*` | Cost and error alarms |
| `random_password.settings` | 32-char password used to AES-encrypt `settings.json` |
| `local_sensitive_file.settings_raw` | The assembled `settings.json` before encryption |

Adding `TerraformAwsServerlessDomainBuilder` adds ACM certificates, Route 53
records, and API Gateway custom domain mappings.

---

## Settings at Runtime

At Lambda cold start, `AwsAdapter.loadSettings()` resolves settings in this
order:

1. **`LIGHTNING_SERVER_SETTINGS_SECRET_ID` env var** — fetches JSON from AWS
   Secrets Manager.
2. **`settings.json` in the Lambda package** — bundled by `terraform apply`.
3. **`settings.enc` in the Lambda package** — AES-CBC-encrypted version;
   decrypted using `LIGHTNING_SERVER_SETTINGS_DECRYPTION` env var (set by
   Terraform from `random_password.settings.result`).
4. **S3 fallback** — reads from the bucket/key in
   `LIGHTNING_SERVER_SETTINGS_BUCKET` / `LIGHTNING_SERVER_SETTINGS_FILE`.

In practice Terraform uses option 3: `settings.enc` is bundled into the ZIP,
the decryption key is injected as an environment variable, and secrets never
appear in plaintext on disk after deploy.

---

## Warts {#warts}

**W5 (extends) — Terraform generation is not unit-assertable.**  The deployment
object calls `builder.build()` internally, which requires all service libraries
on the classpath and may attempt network connections.  The emitted `.tf.json`
files must be reviewed manually.  Treat `write()` + a committed diff in CI as
the correctness gate, not a unit test.

**W12 — `AwsAdapter` instantiates at Lambda init, not at class-load time.**
The `loadSettings()` call in `AwsAdapter.init {}` reads environment variables and
may call AWS APIs.  You cannot instantiate `AwsAdapter` in a unit test without
mocking the Lambda environment.  Use `LocalEngine` for all functional testing
(see Ch7) and reserve `AwsAdapter` for the production entry point.

**W13 — `engine-aws-serverless` carries significant transitive weight.**  The
artifact pulls in the full AWS SDK v2 (`dynamodb`, `s3`, `apigateway`,
`secretsmanager`, `lambda`) and CRaC.  Keep it in a separate `:deploy` or
`:lambda` module if compile times in your main module matter.

---

## What's Next

- **Per-repo READMEs** — practical quick-start for each Lightning Kite repo.
- **KiteUI integration** — wiring a KiteUI frontend to a typed Lightning Server
  backend with the combo package.
- **API contract testing** — `apiBaselineWrite` / `apiCheck` commands that diff
  your API schema and fail CI on breaking changes.
