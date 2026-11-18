# Terraform

A deployment object (`TerraformAwsServerlessBuilder`, `TerraformAwsSingleEc2Builder`, `TerraformAwsScalingEc2Builder`)
is a Terraform emitter.  Its `settings()` says how each server setting is satisfied in that environment: a function
that creates an AWS resource and points the setting at it, a fixed value, or a Terraform variable.  `write()` turns
the result into `.tf.json` files, and `deploy()` applies them.

This chapter covers that mapping, writing your own resource functions, customizing the output, and secrets.  The
walkthroughs are [Deploying to AWS Lambda](aws-deployment.md) and [Deploying to EC2](deploy-ec2.md).

## Complete example

<!-- sample: com/lightningkite/lightningserver/guide/samples/TerraformSamples.kt#imports -->
```kotlin
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.awsserverless.AwsAdapter
import com.lightningkite.lightningserver.terraform.AwsSecretSource
import com.lightningkite.lightningserver.terraform.EnvironmentSecretSource
import com.lightningkite.lightningserver.terraform.ManySecretSources
import com.lightningkite.lightningserver.terraform.SecretSource
import com.lightningkite.lightningserver.terraform.awsserverless.TerraformAwsServerlessDomainBuilder
import com.lightningkite.lightningserver.terraform.generated
import com.lightningkite.services.LoggingSettings
import com.lightningkite.services.data.toEmailAddress
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.mongodb.MongoDatabase
import com.lightningkite.services.database.mongodb.mongodbAtlasFree
import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.terraform.AwsPolicyStatement
import com.lightningkite.services.terraform.AwsVpc
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformJsonObject.Companion.expression
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.byVariable
import com.lightningkite.services.terraform.direct
import kotlinx.serialization.json.JsonPrimitive
import software.amazon.awssdk.regions.Region
import kotlin.reflect.KClass
```

The server declares four settings:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TerraformSamples.kt#server -->
```kotlin
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val jobQueueUrl = setting("jobQueueUrl", "")
    val stripeSecretKey = setting("stripeSecretKey", "")
    val supportEmail = setting("supportEmail", "")

    init {
        MongoDatabase
    }
}

class AwsHandler : AwsAdapter(Server.build())
```

The deployment fulfills each one a different way, and customizes the generated Lambda:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TerraformSamples.kt#deployment -->
```kotlin
object Production : TerraformAwsServerlessDomainBuilder<Server>(Server) {
    override val handler: KClass<out AwsAdapter> = AwsHandler::class
    override val displayName = "Shop API"
    override val region: Region = Region.US_WEST_2
    override val storageBucket = "my-company-terraform-state"
    override val domainZone = "example.com"
    override val domain = "api.example.com"
    override val debug = false
    override val emergencyContact = "ops@example.com".toEmailAddress()
    override val applicationVpc: AwsVpc = AwsVpc.None

    // CI sets LS_SECRET_* variables; people at a terminal share secrets through Secrets Manager.
    override val secretsSource: SecretSource by lazy {
        ManySecretSources(
            EnvironmentSecretSource,
            AwsSecretSource(profile = "my-company", idPrefix = "shop-api", region = region),
        )
    }

    override fun Server.settings() {
        database.mongodbAtlasFree(orgId = "your-atlas-org-id")
        jobQueueUrl.awsSqsQueue()
        stripeSecretKey.byVariable()
        supportEmail.direct("support@example.com")
        secretBasis.generated()
        loggingSettings.direct(LoggingSettings())
        telemetrySettings.direct(TelemetryBackend.Settings())

        policyStatements += AwsPolicyStatement(
            action = listOf("ses:SendEmail", "ses:SendRawEmail"),
            resource = listOf("*"),
        )
        lambdaEnvironment["JAVA_TOOL_OPTIONS"] = "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
    }

    override fun prepareForWrite() {
        super.prepareForWrite()
        emit("lambda") {
            "resource.aws_lambda_function.main" {
                "reserved_concurrent_executions" - 50
            }
        }
    }
}
```

`awsSqsQueue()` is not a library function; it is written in [Writing a resource function](#writing-a-resource-function).

## How settings become resources

`write()` runs `settings()`, collects one JSON value per setting, and fails with `Missing settings for deployment
<prefix>: [...]` if any non-optional setting is left over.  Besides the server's own settings, the builders require
`secretBasis`, `loggingSettings`, and `telemetrySettings`; the EC2 builders also require `engineCache` and
`enginePubSub`.

There are four ways to fulfill a setting:

| Call | Effect |
|---|---|
| `files.awsS3Bucket()` and other service functions | Emits the resource and sets the value to a Terraform expression for it (`s3://${aws_s3_bucket.files.bucket}...`) |
| `supportEmail.direct("support@example.com")` | A fixed value, serialized with the setting's type |
| `stripeSecretKey.byVariable()` | Declares a Terraform variable; its value comes from the secret source at deploy time |
| `secretBasis.generated()` | A `random_password` resource, created once and kept in state |

At apply time Terraform resolves the expressions and variables, encrypts the finished settings JSON, and ships it
with the code (bundled into the Lambda package, or uploaded for EC2 instances).  The plaintext settings never
reach disk, but generated secrets such as `random_password` values live in the Terraform state, so protect the state
bucket.

### Service functions

Each lives in the service implementation's library, next to the implementation.  Referencing the implementation
object in the server (`MongoDatabase` above) is required; each function checks for its URL scheme.

| Setting | Function | Library | Notes |
|---|---|---|---|
| `Database` | `mongodbAtlasFree(orgId)`, `mongodbAtlasFlex(...)`, `mongodbAtlas(...)` | `database-mongodb` | Needs MongoDB Atlas API keys |
| `Database` | `awsDocumentDb(kmsKey, instanceClass)` | `database-mongodb` | VPC required |
| `Database` | `awsAuroraServerlessV2(...)` | `database-postgres` | VPC required |
| `Database` | `awsKeyspaces(...)` | `database-cassandra` | |
| `Cache` | `awsDynamoDb()` | `cache-dynamodb` | No resource; the cache creates its table on first use |
| `Cache` | `awsElasticacheRedis()`, `awsElasticacheValkey(...)`, serverless variants | `cache-redis` | VPC required |
| `Cache` | `awsElasticacheMemcached(...)` | `cache-memcached` | VPC required |
| `PubSub` | `dynamoDb()` | `pubsub-aws` | |
| `PubSub` | `redis(...)` | `pubsub-redis` | Shares a Redis/Valkey cluster |
| `ExternalFileSystem` | `awsS3Bucket(signedUrlDuration)` | `files-s3` | Bucket, CORS, IAM access |
| `EmailService` | `awsSesSmtp(awsSesDomainConfiguration("email", reportingEmail))` | `email-javasmtp` | Domain builders only; sets up the SES identity and DNS records |
| `EmailInboundService` | `awsSesInbound(sesConfig, webhookUrl)` | `email-inbound-ses` | |
| `SecretBasis` | `generated()` | core (`com.lightningkite.lightningserver.terraform`) | |

`awsElasticacheValkey("name")` without a receiver creates the cluster and returns a reusable handle, so one cluster
can serve several settings: `cache.redis(valkey)`, `engineCache.redis(valkey)`, `enginePubSub.redis(valkey)`.  See
[Deploying to EC2](deploy-ec2.md#3b-an-auto-scaled-fleet).

Telemetry functions are per target: [Lambda](aws-deployment.md#observability), [EC2](deploy-ec2.md#telemetry).

## Writing a resource function

A resource function is an extension on the setting's `TerraformNeed`, with the emitter as a context parameter.  It
emits resources, grants the server access, and fulfills the setting:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TerraformSamples.kt#custom-function -->
```kotlin
context(emitter: TerraformEmitterAws)
fun TerraformNeed<String>.awsSqsQueue() {
    emitter.emit(name) {
        "resource.aws_sqs_queue.$name" {
            "name" - "${emitter.projectPrefix}-$name"
            "message_retention_seconds" - 86400
        }
    }
    emitter.policyStatements += AwsPolicyStatement(
        action = listOf("sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage"),
        resource = listOf(expression("aws_sqs_queue.$name.arn")),
    )
    emitter.fulfillSetting(name, JsonPrimitive(expression("aws_sqs_queue.$name.url")))
}
```

This writes `jobQueueUrl.tf.json` with the queue, adds the statement to the execution role's policy, and sets the
`jobQueueUrl` setting to `${aws_sqs_queue.jobQueueUrl.url}`.

The emitter API (`TerraformEmitter`, from Service Abstractions):

| Member | Use |
|---|---|
| `emit(context) { ... }` | Adds blocks to `<context>.tf.json`; calls with the same context merge |
| `fulfillSetting(name, json)` | The setting's value; may contain `${...}` expressions |
| `variable(need)` | Declares that the deploy must supply `need` (passed as `TF_VAR_<name>`) |
| `require(TerraformProviderImport.x)` / `require(TerraformProvider(...))` | Adds to `required_providers` / a provider block |
| `emitExtra(fileName, content)` | Writes a non-JSON file (scripts, templates) next to the Terraform |
| `projectPrefix`, `deploymentTag` | Naming |
| `applicationRegion`, `applicationVpc`, `policyStatements`, `encryptionKey` | On `TerraformEmitterAws` |
| `domain`, `domainZoneId` | On `TerraformEmitterAwsDomain` |

Inside `emit`, a dotted string is a path: `"resource.aws_sqs_queue.jobs" { ... }` opens (or reopens) that object,
`"key" - value` sets a value, and `expression("a.b")` produces `${a.b}`.  Outside `emit`, use
`TerraformJsonObject.expression`.

!!! warning
    Setting the same key twice doesn't overwrite it: the values accumulate into a JSON array, which Terraform will
    reject or misread.

## Customizing the output

**IAM permissions.**  Add to `policyStatements` in `settings()`, as in the complete example.  Every statement ends up
in one `servicesAccess` policy attached to the Lambda or instance role.

**Lambda.**  `lambdaEnvironment` (environment variables), `lambdaLayers` (layer ARNs), `lambdaFiles` (extra files in
the package, by name), and `lambdaTracingMode`.

**EC2 instances.**  Override `systemdEnvironment`, `additionalPackages`, `jvmArgs`, or `customInstallScriptsRaw`;
add files with `instanceFilesRaw[name to FileType.Config] = content` (placed in `/etc/<prefix>/`) or
`FileType.Script` (placed in `/usr/local/bin/`).  The full list is in [Deploying to EC2](deploy-ec2.md#configuration).

**Attributes the builder doesn't expose.**  Override `prepareForWrite()`, call `super`, then `emit` into the same
file and resource, as the complete example does with `reserved_concurrent_executions`.  This adds attributes; it
can't replace ones the builder already set (see the warning above).

**Hand-written Terraform.**  `write()` deletes and rewrites every `*.tf.json` in `terraformRoot`, but leaves other
files alone.  Put hand-written additions in `.tf` files in the same directory.

## Secrets

Secrets the deploy needs (AWS credentials, MongoDB Atlas keys, `byVariable()` values, the state encryption key) come
from the deployment's `secretsSource`.  Missing ones are asked for at the terminal and stored.  Run `editVars()` to
review and edit them all.

| Source | Storage | Good for |
|---|---|---|
| `EnvironmentSecretSource` | `LS_SECRET_<name>` environment variables, read-only | CI |
| `EncryptedFileSecretSource(name)` | `~/.lightningserver/<name>.json.enc`, AES-256 with a password you type (PBKDF2, 100,000 iterations) | One person deploying |
| `AwsSecretSource(profile, idPrefix, region)` | AWS Secrets Manager, as `<idPrefix>/<name>`; module `secret-source-aws` | Teams |
| `ManySecretSources(a, b, ...)` | Reads from the first source that has the value; asks which writable source to store new ones in | Combining the above |

The default is `ManySecretSources(EnvironmentSecretSource, EncryptedFileSecretSource("<bucket>_<prefix>"),
EncryptedFileSecretSource("<bucket>"))`, so values shared by every deployment using one state bucket can live in the
second file.

Names are case-sensitive: `byVariable()` on `stripeSecretKey` reads `LS_SECRET_stripeSecretKey`.  Strings and numbers
are given raw; structured values as JSON.

```bash
export LS_SECRET_AWS_ACCESS_KEY_ID=AKIA...
export LS_SECRET_AWS_SECRET_ACCESS_KEY=...
export LS_SECRET_AWS_SSE_CUSTOMER_KEY=...       # when storageEncryptionEnabled (the default)
export LS_SECRET_MONGODB_ATLAS_PUBLIC_KEY=...   # when using MongoDB Atlas
export LS_SECRET_MONGODB_ATLAS_PRIVATE_KEY=...
export LS_SECRET_stripeSecretKey=sk_live_...
```

AWS credentials are taken from the environment first: `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`, or `AWS_PROFILE`
(which must match the profile stored in the secret source, as a guard against deploying to the wrong account).

!!! warning
    With `storageEncryptionEnabled` (the default) the state is encrypted with a customer key generated on first use
    and kept only in the secret source.  Back it up: without it the state can't be read.

## Commands

| Call | Does |
|---|---|
| `write()` | Writes the `.tf.json` files.  No credentials or network. |
| `deploy(autoApprove = false)` | `write()`, `terraform init -upgrade`, `terraform apply` (asks for `yes` unless `autoApprove`).  The plan is never saved to disk. |
| `destroy()` | Tears everything down after you type `destroy <prefix>` |
| `editVars()` | Terminal editor for the secrets |
| `terraform(vararg args)` | Any Terraform command, in `terraformRoot`, with the deployment's credentials and variables |

Override `useTofu = true` to run OpenTofu.  `TERRAFORM_BINARY` or `TOFU_BINARY` point at a specific binary.

## Gotchas

- The state bucket must exist before the first `deploy()`.
- `projectPrefix` (from `displayName` by default) may contain only letters, digits, `-` and `_`, and names every
  resource.  Changing it means a new deployment, not a rename.
- The generated files reference the build output relative to `terraformRoot` (`../../build/...`), so keep
  `terraformRoot` two directories below the module.
- `write()` doesn't validate against provider schemas.  For that, `terraform init -backend=false && terraform validate`
  in `terraformRoot`.

## Next

- [Deploying to AWS Lambda](aws-deployment.md)
- [Deploying to EC2](deploy-ec2.md)
- [Settings](settings.md): declaring settings and their types.
- [Services](services.md): the service abstractions behind each setting.
