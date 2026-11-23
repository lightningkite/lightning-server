# Deploying to AWS Lambda

`engine-aws-serverless` runs your server on AWS Lambda behind API Gateway and generates the Terraform for the whole
deployment from your server definition.  You write a deployment object that says which AWS service backs each
setting; `deploy()` writes the Terraform, uploads your code, and applies it.

Lambda suits spiky or low traffic: you pay per request and manage no machines.  For always-on instances see
[Deploying to EC2](deploy-ec2.md).  How settings turn into resources, and how to customize the output, is in
[Terraform](terraform.md).

## Before you begin

On the machine that deploys:

- Terraform 1.x or OpenTofu, on your `PATH`.
- AWS CLI v2, plus `openssl` and a shell.  Terraform's provisioners call them to encrypt the settings and to run
  pre-deploy tasks.
- AWS credentials: an AWS CLI profile or an access key.  The first deploy asks for them.
- An S3 bucket for Terraform state.  Create it once by hand; Terraform needs it before `init`.
- A Route 53 hosted zone, if the API gets its own domain.

The Lambda runtime is Java 17, so compile the server module for JVM 17 or lower.

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.lightningkite.lightningserver:engine-aws-serverless:$lightningServerVersion")
    // One implementation per backend your deployment uses:
    implementation("com.lightningkite.services:database-mongodb:$serviceAbstractionsVersion")
    implementation("com.lightningkite.services:files-s3:$serviceAbstractionsVersion")
    // cache-dynamodb comes with engine-aws-serverless.
}
```

Each service implementation library also contains the Terraform function for that service: `mongodbAtlasFree()` lives
in `database-mongodb`, `awsS3Bucket()` in `files-s3`, and so on.

## Complete example

The imports for everything below:

<!-- sample: com/lightningkite/lightningserver/guide/samples/AwsDeploymentSamples.kt#imports -->
```kotlin
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.awsserverless.AwsAdapter
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.terraform.awsserverless.TerraformAwsServerlessDomainBuilder
import com.lightningkite.lightningserver.terraform.awsserverless.otelXRay
import com.lightningkite.lightningserver.terraform.generated
import com.lightningkite.services.LoggingSettings
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.dynamodb.DynamoDbCache
import com.lightningkite.services.cache.dynamodb.awsDynamoDb
import com.lightningkite.services.data.DataSize.Companion.gibibytes
import com.lightningkite.services.data.toEmailAddress
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.mongodb.MongoDatabase
import com.lightningkite.services.database.mongodb.mongodbAtlasFree
import com.lightningkite.services.files.ExternalFileSystem
import com.lightningkite.services.files.s3.S3ExternalFileSystem
import com.lightningkite.services.files.s3.awsS3Bucket
import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.terraform.AwsVpc
import com.lightningkite.services.terraform.direct
import software.amazon.awssdk.regions.Region
import java.io.File
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
```

### 1. The server

An ordinary server definition.  Nothing in it is Lambda-specific, so the same object runs locally under Ktor.

<!-- sample: com/lightningkite/lightningserver/guide/samples/AwsDeploymentSamples.kt#server -->
```kotlin
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val files = setting("files", ExternalFileSystem.Settings())

    init {
        // Referencing a backend registers its URL scheme; the Terraform functions below check for it.
        MongoDatabase
        DynamoDbCache
        S3ExternalFileSystem
    }

    val root = path.get bind HttpHandler { HttpResponse.plainText("OK") }
}
```

### 2. The Lambda handler

<!-- sample: com/lightningkite/lightningserver/guide/samples/AwsDeploymentSamples.kt#handler -->
```kotlin
class AwsHandler : AwsAdapter(Server.build())
```

`AwsAdapter` is the Lambda entry point (`RequestStreamHandler`).  It loads the settings at cold start and routes
each invocation: HTTP requests, WebSocket events, scheduled tasks, background tasks, and the pre-deploy check.  It
also implements CRaC, so SnapStart snapshots it with connections warmed.

### 3. The deployment object

One object per environment.  `settings()` must fulfill every non-optional setting of the server, plus `secretBasis`,
`loggingSettings`, and `telemetrySettings`; `write()` fails with `Missing settings for deployment ...` otherwise.

<!-- sample: com/lightningkite/lightningserver/guide/samples/AwsDeploymentSamples.kt#deployment -->
```kotlin
object Production : TerraformAwsServerlessDomainBuilder<Server>(Server) {
    override val handler: KClass<out AwsAdapter> = AwsHandler::class
    override val displayName = "My API"
    override val region: Region = Region.US_WEST_2
    override val storageBucket = "my-company-terraform-state"
    override val domainZone = "example.com"
    override val domain = "api.example.com"
    override val debug = false
    override val emergencyContact = "ops@example.com".toEmailAddress()
    override val applicationVpc: AwsVpc = AwsVpc.None

    override fun Server.settings() {
        database.mongodbAtlasFree(orgId = "your-atlas-org-id", zoneName = "Zone 1")
        cache.awsDynamoDb()
        files.awsS3Bucket(signedUrlDuration = 1.days)
        secretBasis.generated()
        loggingSettings.direct(LoggingSettings())
        telemetrySettings.direct(TelemetryBackend.Settings())
    }
}
```

- `displayName` also names everything: the project prefix is its lowercase, dash-separated form (`my-api`), used
  for resource names, the state key, and the default `terraformRoot` (`terraform/my-api`).
- `TerraformAwsServerlessDomainBuilder` serves the API at `domain` and WebSockets at `ws.<domain>`, with ACM
  certificates and Route 53 records.  Use `TerraformAwsServerlessBuilder` (no `domain`/`domainZone`) to use the
  generated API Gateway URLs instead.
- `emergencyContact` receives the alarm emails.  Confirm the SNS subscription email after the first deploy.

### 4. Entry points

<!-- sample: com/lightningkite/lightningserver/guide/samples/AwsDeploymentSamples.kt#entry-points -->
```kotlin
object ProductionWrite {
    @JvmStatic
    fun main(vararg args: String) = Production.write()
}

object ProductionEditVars {
    @JvmStatic
    fun main(vararg args: String) = Production.editVars()
}

object ProductionDeploy {
    @JvmStatic
    fun main(vararg args: String) = Production.deploy()
}
```

### 5. Gradle tasks

The Lambda package is your jar unzipped, with the runtime classpath in `lib/`:

```kotlin
// build.gradle.kts
tasks.register<Sync>("lambda") {
    group = "deploy"
    val jar = tasks.jar.get()
    dependsOn(jar)
    destinationDir = layout.buildDirectory.dir("dist/lambda").get().asFile
    from(zipTree(jar.archiveFile))
    into("lib") { from(configurations.runtimeClasspath) }
}

tasks.register<JavaExec>("deployProduction") {
    group = "deploy"
    dependsOn("lambda")
    classpath(sourceSets.main.get().runtimeClasspath)
    mainClass.set("com.example.ProductionDeploy")
    workingDir(projectDir)
    standardInput = System.`in`
}
```

`standardInput` matters: `deploy()` and `editVars()` prompt in the terminal.

!!! warning "Where the package is read from"
    The generated Terraform reads the package from `<terraformRoot>/../../build/dist/lambda`.  With the default
    `terraformRoot` (`terraform/<prefix>`), run the deploy with the module directory as the working directory, as
    above.  If you run it from elsewhere, set `terraformRoot` to `<module>/terraform/<name>` relative to that
    directory (see [Tuning](#tuning)).

## Deploying

```bash
./gradlew :server:deployProduction
```

Add `JavaExec` tasks for `ProductionEditVars` and `ProductionWrite` the same way, or run them from your IDE.  The
steps, in order:

1. **`editVars()`** (optional, first time): asks for the AWS credentials and anything else the deployment needs
   (MongoDB Atlas API keys for `mongodbAtlasFree`, values for `byVariable()` settings) and stores them in the
   deployment's secret source.  Anything missing is also asked for during `deploy()`.
2. **`write()`**: writes the `.tf.json` files into `terraformRoot`, replacing older ones.  No credentials or network
   needed.  Commit them if you want infrastructure changes in code review.
3. **`deploy()`**: runs `write()`, `terraform init -upgrade`, then `terraform apply`, which shows the plan and waits
   for you to type `yes`.  `deploy(autoApprove = true)` skips the prompt for CI.  The plan is never saved to a file,
   because a saved plan holds the secrets in plain text.

During the apply, Terraform encrypts the settings into the package, uploads it, publishes a new Lambda version,
invokes that version with a pre-deploy event (running your pre-deploy tasks), and only then points the `prod` alias
at it.  If the pre-deploy tasks fail, the apply fails and traffic stays on the old version.

`destroy()` tears the deployment down after you type `destroy <prefix>`.  `terraform("state", "list")` runs any
Terraform command with the deployment's credentials.

## What gets created

| Resource | Purpose |
|---|---|
| `aws_lambda_function.main` | The function: Java 17, 1 GiB, 30 s timeout, SnapStart on published versions |
| `aws_lambda_alias.main` | The `prod` alias every trigger invokes |
| `null_resource.predeploy` | Runs pre-deploy tasks on the new version before the alias moves |
| `aws_s3_bucket.lambda_bucket` | Holds the uploaded package |
| `aws_iam_role.main_exec`, `aws_iam_policy.servicesAccess` | Execution role; the policy collects every service's permissions |
| `aws_apigatewayv2_api.http` | HTTP API, all routes to the Lambda |
| `aws_apigatewayv2_api.ws` | WebSocket API |
| `aws_cloudwatch_event_rule.scheduled_task_*` | One EventBridge rule per schedule in the server |
| `aws_cloudwatch_log_group.main` | Function logs, 30 days |
| `aws_sns_topic.emergency`, `aws_cloudwatch_metric_alarm.*` | Spend alarms, emailed to `emergencyContact` |
| `random_password.settings` | Key that encrypts the bundled settings |

The domain builder adds ACM certificates, Route 53 records, and API Gateway custom domains for both APIs.  Each
service function adds its own resources (an Atlas cluster, an S3 bucket, ...).

## Tuning

Override properties on the deployment object.  This one also moves `terraformRoot`, for a project whose server
module is `server/` and whose deploy runs from the repository root:

<!-- sample: com/lightningkite/lightningserver/guide/samples/AwsDeploymentSamples.kt#tuning -->
```kotlin
object Staging : TerraformAwsServerlessDomainBuilder<Server>(Server) {
    override val handler: KClass<out AwsAdapter> = AwsHandler::class
    override val displayName = "My API Staging"
    override val region: Region = Region.US_WEST_2
    override val storageBucket = "my-company-terraform-state"
    override val domainZone = "example.com"
    override val domain = "staging-api.example.com"
    override val debug = true
    override val emergencyContact = "ops@example.com".toEmailAddress()
    override val applicationVpc: AwsVpc = AwsVpc.None

    // The server module is server/, but this deploy runs from the repository root.
    override val terraformRoot = File("server/terraform/staging")
    override val timeout = 60.seconds
    override val memory = 2.gibibytes
    override val monthlyBudgetUsd = 25.0

    override fun Server.settings() {
        database.mongodbAtlasFree(orgId = "your-atlas-org-id", zoneName = "Zone 1")
        cache.awsDynamoDb()
        files.awsS3Bucket(signedUrlDuration = 1.days)
        secretBasis.generated()
        loggingSettings.direct(LoggingSettings())
        telemetrySettings.otelXRay()
    }
}
```

| Property | Default | Notes |
|---|---|---|
| `timeout` | 30 s | Lambda allows up to 15 minutes.  API Gateway still cuts HTTP requests off at 30 s. |
| `memory` | 1 GiB | CPU scales with memory. |
| `snapStart` | `true` | With it off, the alias tracks `$LATEST`, so pre-deploy tasks no longer gate the cutover. |
| `monthlyBudgetUsd` | 10.0 | Sets the spend alarms' thresholds. |
| `alarms` | spend alarms | A `Map<String, LambdaAlarm>`; build entries with `LambdaInvocationAlarm(...)` / `LambdaDurationAlarm(...)`. |
| `applicationVpc` | (required) | `AwsVpc.None`, or `terraformManagedVPC(...)` / `existingVPC(...)` to run inside a VPC (needed for VPC-only services such as ElastiCache). |
| `useCloudFrontForWebSocket` | `false` | See [How the server runs on Lambda](#how-the-server-runs-on-lambda). |
| `enableIPv6` | `true` | Dual-stack API domains and VPC. |
| `projectPrefix` | from `displayName` | Letters, digits, `-` and `_` only. |
| `terraformRoot` | `terraform/<prefix>` | Must sit two levels below the module directory. |
| `storageBucketPath` | `<prefix>` | State key inside `storageBucket`. |
| `storageEncryptionEnabled` | `true` | Encrypts the state with a customer key (`AWS_SSE_CUSTOMER_KEY`), generated on first use and kept in the secret source.  Losing it loses access to the state. |
| `useStorageLockFile` | `false` | S3-native state locking (`use_lockfile`). |
| `secretsSource` | env vars, then encrypted files | See [Terraform](terraform.md#secrets). |

`lambdaLayers`, `lambdaEnvironment`, `lambdaFiles`, and `policyStatements` are mutable collections for adding to the
function; see [Terraform](terraform.md#customizing-the-output).

## Observability

Lambda logs go to the `<prefix>-main-log` CloudWatch log group:

```bash
aws logs tail my-api-main-log --follow
```

For traces and metrics, fulfill `telemetrySettings` with a collector function from
`com.lightningkite.lightningserver.terraform.awsserverless` instead of `direct(...)`.  Each adds the AWS
OpenTelemetry collector layer and points the server at it:

| Function | Sends to | Needs |
|---|---|---|
| `otelXRay()` | AWS X-Ray | Nothing; attaches the X-Ray policy and enables Lambda tracing |
| `otelGrafanaCloud(instanceId = "...", zone = "prod-us-east-0")` | Grafana Cloud | `grafana_cloud_api_key` variable |
| `otelHoneycomb()` | Honeycomb | `honeycomb_api_key` variable |
| `otelCollector(otlpEndpoint = "...", otlpHeaders = ...)` | Any OTLP endpoint | |

The variables are asked for by `editVars()`.  With Grafana Cloud, `grafanaAlerts(grafanaCloudStackSlug, alerts)` in
`settings()` also creates alert rules, for example
`GrafanaAlert.httpErrors(deploymentTag, errorThreshold = 5) + GrafanaAlert.httpLiveness(deploymentTag)`.

## How the server runs on Lambda

**Settings.**  `deploy()` gathers every fulfilled setting, encrypts the JSON with `random_password.settings`, and
bundles it into the package as `settings.enc`.  The function gets the key in `LIGHTNING_SERVER_SETTINGS_DECRYPTION`,
so plaintext settings never touch disk.  (`AwsAdapter` also reads a plain `settings.json` from the package, or the
S3 object named by `LIGHTNING_SERVER_SETTINGS_BUCKET` and `LIGHTNING_SERVER_SETTINGS_FILE`, if you package settings
yourself.)

**Background tasks** run as asynchronous invocations of the same function.

**Schedules** become EventBridge rules that invoke the `prod` alias.  `Schedule.Daily` times are converted to UTC
cron expressions.

**WebSockets.**  API Gateway WebSocket APIs can't route by path, so clients connect to `wss://ws.<domain>?path=/your/path`
(`generalSettings().wsUrl` already ends in `?path=`).  Set `useCloudFrontForWebSocket = true` to put CloudFront in front
and accept ordinary path URLs, which clients like Twilio need.  Connection state is kept in DynamoDB.

## Gotchas

- **Unregistered backend.**  `cache.awsDynamoDb()` throws `You need to reference 'DynamoDbCache' in your server
  definition` unless the implementation object was referenced first.  Reference them in the server's `init {}`.
- **MongoDB Atlas access.**  Outside a VPC a Lambda has no fixed outbound IP, so `mongodbAtlasFree` opens the Atlas IP
  access list to `0.0.0.0/0` and the generated database password is the only protection.  Inside a VPC it allows
  the NAT gateway IPs instead.
- **Unit tests.**  `AwsAdapter` loads settings in its constructor and expects the Lambda environment.  Test the server
  with the in-memory engine ([Testing](testing.md)).  `write()` is safe to call in a test: it only generates JSON.

## Next

- [Terraform](terraform.md): how settings map to resources, secrets, and customizing the output.
- [Deploying to EC2](deploy-ec2.md): always-on instances, single or auto-scaled.
- [Engines](engines.md): the engines behind each deployment target.
- [Observability](observability.md): telemetry inside the server.
