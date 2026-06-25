> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Terraform Deep-Dive

Lightning Server's Terraform integration derives complete infrastructure
configuration from your server definition automatically.  This page explains
how that derivation works, what resources each service creates, how to
customise the generated output, and how the secrets pipeline keeps sensitive
values out of source control.

> **Illustrative snippets.** Deployment objects call `builder.build()`
> internally and may attempt network connections.  The Kotlin deployment object
> patterns and Terraform resource tables are verified against the source files
> in `core/src/main/kotlin/.../terraform/`, `engine-aws-serverless/src/main/kotlin/`,
> and `deploy-aws-ec2/src/main/kotlin/`, but cannot run in a unit test.

---

## The Mental Model

Three things work together:

```
ServerBuilder          ──► engine-specific builder (AwsAdapter / main())
     │
     └──► TerraformBuilder ──► BaseTerraformEmitter.write()
               │
               ▼
          terraform/<project-prefix>/
              main.tf.json
              lambda.tf.json       (serverless) or deployment.tf.json (EC2)
              http.tf.json / ws.tf.json
              schedules.tf.json
              ...
```

1. **`ServerBuilder`** — your server definition: endpoints, settings, service
   declarations.  Engine-agnostic.
2. **Deployment object** — a subclass of `TerraformAwsServerlessBuilder`,
   `TerraformAwsSingleEc2Builder`, or `TerraformAwsScalingEc2Builder`.  Its
   `settings()` override calls service-specific Terraform extension functions
   that emit resources and fulfill settings.
3. **`BaseTerraformEmitter.write()`** — seals the definition, runs `settings()`,
   and writes `.tf.json` files (Terraform's JSON configuration syntax) to
   `terraform/<project-prefix>/`.

Each `.tf.json` file maps to a logical concern:

| File | Contents |
|---|---|
| `main.tf.json` | `terraform {}` block (required_providers, backend), provider configurations |
| `lambda.tf.json` | Lambda function, IAM role, S3 bucket for ZIP, settings encryption |
| `http.tf.json` | HTTP API Gateway, ACM certificate, Route53 record (serverless) |
| `ws.tf.json` | WebSocket API Gateway, Route53 record (serverless) |
| `lambdaAlarms.tf.json` | SNS topic, CloudWatch metric alarms, CloudWatch role |
| `schedules.tf.json` | EventBridge rules + targets (one per `schedule()` in your server) |
| `deployment.tf.json` | S3 artifact bucket, IAM role, settings encryption (EC2) |
| `ec2.tf.json` | EC2 instance, user-data (single) |
| `image.tf.json` | EC2 Image Builder pipeline (scaling fleet) |
| `asg.tf.json` | Launch template, Auto Scaling Group, scaling policies |
| `alb.tf.json` | Application Load Balancer, target group, listeners, ACM (scaling fleet) |
| `cloud.tf.json` | VPC module (when using `terraformManagedVPC()`) |
| `monitoring.tf.json` | CloudWatch log groups, SNS, alarms (EC2) |
| `redeploy.tf.json` | `null_resource` triggers that drive SSM-based redeploy |
| `dns.tf.json` | Route53 data source and A/AAAA records (EC2) |
| `outputs.tf.json` | Terraform outputs (application URL, ALB DNS name) |

---

## `BaseTerraformEmitter`: The Core Contract

All deployment builders extend `BaseTerraformEmitter<S : ServerBuilder>`.  Its
abstract API:

```kotlin
// Illustrative — simplified; source in core/src/main/kotlin/.../terraform/BaseTerraformEmitter.kt
abstract class BaseTerraformEmitter<S : ServerBuilder> : TerraformEmitter {

    // You provide these:
    abstract val builder: S
    abstract val secretsSource: SecretSource
    abstract val terraformRoot: File
    abstract fun S.settings()

    // Called by write() — override to customise resource emission.
    open fun prepareForWrite() { ... }

    // Workflow entry points:
    fun write()     // emit .tf.json files only
    fun deploy()    // init → plan → prompt → apply
    fun editVars()  // interactive secret editor
    fun terraformShell()  // drop into terraform commands interactively
}
```

`write()` calls `prepareForWrite()` → your `settings()` → emits `.tf.json`
files.  No network calls; no AWS credentials needed.  Running `write()` in CI
and committing the diff is the primary correctness gate — diffs in generated
files make infrastructure changes visible in code review.

---

## How `settings()` Drives Infrastructure

The `settings()` override is the heart of the deployment object.  Each call
inside it is a **Terraform extension function** defined by a service
implementation library:

```
settingNeed.serviceFunction(config...)
```

Inside each extension function, three things happen:

### 1. `fulfillSetting(name, jsonElement)`

Stores a JSON value as the runtime setting value for that setting key.  For
cloud services the value is usually a Terraform interpolation string:

```kotlin
// What happens inside, e.g., cache.awsDynamoDb():
fulfillSetting("cache", JsonPrimitive("dynamodb://us-east-1/my-api_cache"))
```

These values are collected into a `settings.json`-shaped map, encrypted with
AES-256-CBC (PBKDF2 key derivation, 100,000 iterations), and bundled into the
Lambda ZIP or uploaded to the EC2 deployment S3 bucket.  The encryption key
comes from a `random_password` Terraform resource; the decryption key is
injected into the Lambda environment or stored in SSM Parameter Store.

### 2. `emit(context) { ... }`

Adds Terraform resource blocks to a named `.tf.json` file.  The DSL uses
string operators to build JSON:

```kotlin
// Illustrative — excerpt from files-s3 implementation.
emit("files") {
    "resource.aws_s3_bucket.files" {
        "bucket_prefix" - "${projectPrefix}-files"
        "force_destroy" - true
    }
    "resource.aws_s3_bucket_policy.files" {
        "bucket" - expression("aws_s3_bucket.files.id")
        "policy" - expression("data.aws_iam_policy_document.files.json")
    }
    // ...IAM policy, CORS, lifecycle, etc.
}
```

`expression("...")` wraps a string in Terraform's `${ }` interpolation syntax
in the emitted JSON.

### 3. `require(TerraformProviderImport.*)`

Registers an external Terraform provider.  Provider imports are deduplicated
and emitted in the `required_providers` block of `main.tf.json`:

```kotlin
// Illustrative.
require(TerraformProviderImport.mongodbatlas)
// Adds to required_providers: mongodbatlas = { source = "mongodb/mongodbatlas", version = "~> 1.0" }
```

---

## Common Service Extension Functions

The following extension functions are defined in the respective service
implementation libraries.  Confirm the exact import from the artifact your
project uses.

### Serverless (Lambda)

| Setting type | Extension | Resources emitted |
|---|---|---|
| `Database.Settings` | `mongodbAtlasFree(orgId, zoneName)` | `mongodbatlas_project`, `mongodbatlas_advanced_cluster`, DB user, `random_password` |
| `Database.Settings` | `mongodbAtlas(orgId, minSize, maxSize)` | As above, with auto-scaling cluster tier |
| `Cache.Settings` | `awsDynamoDb()` | No table (DynamoDbCache auto-creates); fulfills setting with `dynamodb://region/prefix_cache` |
| `PublicFileSystem.Settings` | `awsS3Bucket(signedUrlDuration)` | `aws_s3_bucket`, CORS config, S3 IAM policy statements |
| `TerraformNeed<SecretBasis>` | `secretBasis.generated()` | `random_password` (32 chars) as signing secret |

### EC2

The same service extensions are available on EC2 builders.  Additionally,
EC2-specific services include:

| Setting type | Extension | Notes |
|---|---|---|
| `Cache.Settings` | `awsElasticacheMemcached(...)` | Creates an ElastiCache Memcached cluster inside the VPC |
| `Cache.Settings` | `awsElasticacheRedis(...)` | Creates an ElastiCache Redis replication group |

---

## Terraform Resources: Lambda Deployment

A minimal serverless deployment (`TerraformAwsServerlessBuilder` without a
custom domain) produces:

| Resource | Purpose |
|---|---|
| `aws_s3_bucket.lambda_bucket` | Stores the Lambda ZIP |
| `aws_s3_object.app_storage` | The uploaded ZIP (source hash triggers Lambda update) |
| `data.archive_file.lambda` | Zips the exploded build/dist/lambda directory |
| `aws_lambda_function.main` | Java 17, SnapStart enabled, 1 GiB memory, 30s timeout |
| `aws_lambda_alias.main` (prod) | Stable alias pointing to the published version |
| `aws_iam_role.main_exec` | Execution role (S3, DynamoDB, Lambda invoke, VPC) |
| `aws_iam_policy.servicesAccess` | Least-privilege policy built from `policyStatements` |
| `aws_apigatewayv2_api.http` | HTTP API Gateway (all routes → Lambda) |
| `aws_apigatewayv2_api.ws` | WebSocket API Gateway (connect/disconnect/default → Lambda) |
| `aws_cloudwatch_log_group.main` | Lambda log group (30-day retention) |
| `random_password.settings` | 32-char AES key for `settings.enc` |
| `local_sensitive_file.settings_raw` | Assembled `settings.json` before encryption |
| `aws_sns_topic.emergency` | Alerts topic (email → `emergencyContact`) |
| `aws_cloudwatch_metric_alarm.*` | Spend alarms (compute-seconds per month) |

Adding `TerraformAwsServerlessDomainBuilder` adds ACM certificates, Route53
records, and API Gateway custom domain mappings for both HTTP and WebSocket APIs.

### Schedules

For each `schedule()` declaration in your `ServerBuilder`, the Terraform
generator emits:

```
aws_cloudwatch_event_rule.scheduled_task_<name>
aws_cloudwatch_event_target.scheduled_task_<name>
```

The event target's `input` JSON (`{"scheduled": "<path>"}`) tells the Lambda
which handler to invoke.  `aws_lambda_permission.scheduled_tasks` grants
EventBridge permission to invoke the Lambda alias.

### Settings at Lambda Cold Start

`AwsAdapter.loadSettings()` resolves settings at cold start in this priority
order:

1. `LIGHTNING_SERVER_SETTINGS_SECRET_ID` env var → fetches JSON from AWS
   Secrets Manager.
2. `settings.json` bundled into the ZIP (plaintext; only used for local testing).
3. `settings.enc` bundled into the ZIP — AES-CBC decrypted using
   `LIGHTNING_SERVER_SETTINGS_DECRYPTION` env var (set by Terraform from
   `random_password.settings.result`).
4. `LIGHTNING_SERVER_SETTINGS_BUCKET` + `LIGHTNING_SERVER_SETTINGS_FILE` env
   vars → reads from S3.

In practice Terraform uses option 3: `settings.enc` is bundled; the key is in
the Lambda environment; secrets never appear in plaintext on disk after deploy.

---

## Terraform Resources: EC2 Deployment

The resources emitted for EC2 are described in [Deploying to EC2](deploy-ec2.md).
This section focuses on the shared infrastructure pattern.

### The Encrypted Settings Pipeline

Both EC2 builders use the same settings pipeline:

```
settings map (Kotlin)
     │ fulfillSetting() calls in settings()
     ▼
local_sensitive_file.settings_raw    ← plaintext JSON on your machine during apply
     │ null_resource.encrypt_settings (openssl enc -aes-256-cbc -pbkdf2 -iter 100000)
     ▼
build/settings.enc                   ← AES-256 encrypted
     │ null_resource.upload_settings
     ▼
s3://deployment-bucket/settings.enc  ← never leaves AWS
     │ lightning-server-redeploy (SSM)
     │ SETTINGS_PASS from aws ssm get-parameter
     ▼
/opt/lightning-server/settings.json  ← chmod 600, decrypted on instance
     │ settings.loadFromFile()
     ▼
ServerRuntime                        ← services connected
```

The decryption key (`random_password.settings.result`) is stored as an SSM
SecureString parameter and never written to disk unencrypted after `terraform apply`.

---

## Secret Management

### `SecretSource` Implementations

| Class | Storage | Best for |
|---|---|---|
| `EncryptedFileSecretSource` | `~/.lightningserver/<name>.json.enc` (AES-256, PBKDF2) | Local development, solo CI |
| `EnvironmentSecretSource` | `LS_SECRET_<KEY>` env vars | Shared CI/CD pipelines |
| `AwsSecretSource` | AWS Secrets Manager | Team environments (shared + auditable) |
| `ManySecretSources` | Tries sources in order | Flexible fallback chains |

The concrete builders (`TerraformAwsServerlessBuilder`, `TerraformAwsEc2BuilderBase`)
default to a `ManySecretSources` that tries environment variables first, then an
`EncryptedFileSecretSource` keyed by the state bucket name and project prefix.

### `editVars()`: Interactive Secret Capture

```bash
./gradlew :deploy:run --main=com.example.api.ProductionEditKt
```

Prompts for any undeclared variable (AWS credentials, Atlas API keys, etc.),
stores each value in the `secretsSource`, and exits.  Subsequent runs skip
already-stored secrets.

### `EnvironmentSecretSource` for CI/CD

Set secrets as environment variables in your CI system:

```bash
# Illustrative — set in GitHub Actions secrets or equivalent.
export LS_SECRET_AWS_ACCESS_KEY_ID="AKIA..."
export LS_SECRET_AWS_SECRET_ACCESS_KEY="..."
export LS_SECRET_MONGODB_ATLAS_PUBLIC_KEY="..."
export LS_SECRET_MONGODB_ATLAS_PRIVATE_KEY="..."
```

The `EnvironmentSecretSource` reads them at deploy time.  No interactive prompt
is needed.

---

## The Deploy Workflow

### 1. Generate

```bash
./gradlew :deploy:run --main=com.example.api.ProductionWriteKt
# or kotlin: Production.write()
```

Writes (or updates) `terraform/<project-prefix>/*.tf.json`.  Commit the diff
to version control: infrastructure changes are now visible in pull requests.

### 2. Inspect the Diff

```bash
cd terraform/<project-prefix>
terraform plan -out=plan.tfplan
```

Or let `deploy()` do it:

```bash
./gradlew :deploy:run --main=com.example.api.ProductionDeployKt
# Runs init → plan → waits for Enter → applies
```

Pass `autoApprove = true` to `deploy()` for non-interactive CI runs.

### 3. Apply

`deploy()` calls, in order:

1. `terraform init -upgrade` — downloads providers (cached after first run).
2. `terraform plan -out=plan.tfplan` — shows what will change.
3. Optionally waits for Enter (the human review gate).
4. `terraform apply plan.tfplan` — creates/updates resources.

On subsequent deploys, only the Lambda ZIP or EC2 distribution changes if no
infrastructure was added or removed.

### 4. Destroy

```bash
cd terraform/<project-prefix>
terraform destroy
```

There is no `destroy()` convenience method — Terraform destruction is
intentionally a manual step.

---

## Customising Generated Resources

### Adding IAM Permissions

Each deployment builder exposes `policyStatements: MutableCollection<AwsPolicyStatement>`.
Append to it in `settings()` or `init {}`:

```kotlin
// Illustrative.
init {
    policyStatements += AwsPolicyStatement(
        action   = listOf("ses:SendEmail"),
        resource = listOf("arn:aws:ses:us-east-1:*:identity/*"),
    )
}
```

These statements are folded into the `aws_iam_policy.servicesAccess` resource.

### Adding Lambda Layers

```kotlin
// Illustrative (serverless builder).
init {
    lambdaLayers += "arn:aws:lambda:us-east-1:580247275435:layer:LambdaInsightsExtension:38"
}
```

### Adding Lambda Environment Variables

```kotlin
// Illustrative.
init {
    lambdaEnvironment["MY_FEATURE_FLAG"] = "enabled"
}
```

### Adding Files to the Lambda ZIP

```kotlin
// Illustrative — e.g., an OpenTelemetry collector config.
init {
    lambdaFiles["collector.yaml"] = """
        receivers:
          otlp:
            protocols:
              grpc:
                endpoint: 0.0.0.0:4317
        ...
    """.trimIndent()
}
```

### Adding Files to EC2 Instances

```kotlin
// Illustrative.
init {
    instanceFilesRaw[Pair("my-config.json", FileType.Config)] = """
        { "featureFlag": true }
    """.trimIndent()
    // Placed at /etc/lightning-server/my-config.json
}
```

### Forcing AMI Rebuild (Scaling Fleet)

To pick up OS security patches without waiting for a code change, bump
`baseImageSalt`:

```kotlin
override val baseImageSalt: String get() = "2026-06"
// Change monthly (or on any schedule) to re-bake the golden AMI from the latest Ubuntu base.
```

### VPC Options

```kotlin
// Terraform-managed VPC (recommended for new deployments):
override val applicationVpc = terraformManagedVPC(
    ipPrefix          = "10.0",
    availabilityZones = listOf("us-east-1a", "us-east-1b", "us-east-1c"),
    natGateway        = AwsVpc.NatGateway.Single,   // cheapest; PerAvailabilityZone for HA
)

// Default VPC (single instance only; simplest to start):
override val applicationVpc = AwsVpc.Default

// Pre-existing VPC (bring-your-own):
override val applicationVpc = AwsVpc.VpcInfo(
    id             = "vpc-0abc123",
    securityGroup  = "sg-0def456",
    privateSubnets = "[\"subnet-a\", \"subnet-b\"]",
    publicSubnets  = "[\"subnet-c\", \"subnet-d\"]",
    // ...
)
```

### Using OpenTofu

```kotlin
override val useTofu: Boolean get() = true
```

`deploy()` calls `tofu` instead of `terraform`.

---

## Limitations and Caveats

**Terraform generation is not unit-assertable.** `write()` calls
`builder.build()` internally, which may attempt network connections for
providers that eagerly validate.  Treat `write()` + committed diff as the
correctness gate.

**`AwsAdapter` cannot be instantiated in a unit test.** Its `init {}` reads
environment variables and may call AWS APIs.  Use `LocalEngine` for all
functional testing.

**Provider version pinning.** The `required_version = "~> 1.0"` and provider
version constraints are emitted by `BaseTerraformEmitter`.  If you need
specific provider versions, the currently generated constraints may not match.
Review `main.tf.json` after `write()`.

**Terraform state in S3.** The S3 backend for state requires the bucket to
exist *before* the first `terraform init`.  Create it manually (or with a
separate bootstrap Terraform config) before running `deploy()`.

**Windows support.** The EC2 builder's `null_resource.lambda_jar_source`
provisioners detect Windows via a `local.is_windows` Terraform local and use
PowerShell when true.  The EC2 redeploy scripts are bash and run remotely, so
the local OS does not matter for EC2 deployments.

---

## What's Next

- **[Deploying to AWS Lambda](aws-deployment.md)** — end-to-end Lambda deployment
  guide including the `AwsAdapter` entry point and the deployment object.
- **[Deploying to EC2](deploy-ec2.md)** — EC2-specific configuration and the
  rolling redeploy mechanism.
- **[Deploying to a VM/Docker](deploy-vm.md)** — manual fat-JAR and Dockerfile
  deployment without Terraform.
