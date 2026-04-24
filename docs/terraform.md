# Terraform Integration

Last updated January 2025 (`version-5`)

Lightning Server provides a comprehensive Terraform integration layer for Infrastructure-as-Code deployments. This
allows you to define your infrastructure programmatically and manage it through Terraform.

## Overview

The terraform package (`com.lightningkite.lightningserver.terraform`) provides:

- **`BaseTerraformEmitter`**: Abstract base class for generating Terraform configurations
- **`SecretSource`**: Interface and implementations for managing deployment secrets
- **`EncryptedFileSecretSource`**: Secure local storage for secrets
- **`EnvironmentSecretSource`**: Read secrets from environment variables
- **`ManySecretSources`**: Chain multiple secret sources with fallback

## Core Concepts

### Terraform Emitters

A Terraform emitter generates `.tf.json` files (Terraform's JSON format) from your server configuration. Service
implementations contribute their own Terraform resources by implementing the `TerraformEmitter` interface.

### Secret Management

Secrets (API keys, credentials, etc.) are managed separately from your code through `SecretSource` implementations:

- **Environment variables** (`EnvironmentSecretSource`): For CI/CD pipelines
- **Encrypted files** (`EncryptedFileSecretSource`): For local development
- **Multiple sources** (`ManySecretSources`): Fallback chain for flexible deployment

## Basic Usage

### 1. Extend BaseTerraformEmitter

```kotlin
import com.lightningkite.lightningserver.terraform.BaseTerraformEmitter
import com.lightningkite.lightningserver.terraform.EncryptedFileSecretSource
import java.io.File

object MyDeployment : BaseTerraformEmitter<MyServer>() {
    override val builder = MyServer
    override val terraformRoot = File("terraform/production")
    override val secretsSource = EncryptedFileSecretSource("production")

    override fun MyServer.settings() {
        // Configure your services here
        database.direct(DatabaseSettings(...))
        cache.direct(CacheSettings(...))
        // ... other settings
    }
}
```

### 2. Generate Terraform Configuration

```kotlin
fun main() {
    MyDeployment.write()
    // This generates .tf.json files in terraform/production/
}
```

### 3. Deploy with Terraform

```kotlin
fun main() {
    MyDeployment.deploy()
    // This runs: terraform init, plan (with confirmation), and apply
}
```

Or manually:

```bash
cd terraform/production
terraform init
terraform plan
terraform apply
```

## Managing Secrets

### Interactive Secret Editor

```kotlin
fun main() {
    MyDeployment.editVars()
}
```

This opens an interactive terminal menu to view and edit deployment secrets.

### Secret Source Implementations

#### EncryptedFileSecretSource

Stores secrets in an encrypted JSON file protected by a password:

```kotlin
val secrets = EncryptedFileSecretSource("production")
// Creates ~/.lightningserver/production.json.enc
```

**Security Features**:

- AES-256 encryption
- PBKDF2 key derivation with 100,000 iterations
- Random 32-byte salt per file
- Automatic migration from legacy format (if upgrading from older versions)
- File is created on first use

This implementation provides strong encryption suitable for local development and testing. For production deployments,
consider using a dedicated secret manager (AWS Secrets Manager, HashiCorp Vault, etc.).

#### EnvironmentSecretSource

Reads secrets from environment variables with the `LS_SECRET_` prefix:

```kotlin
val secrets = EnvironmentSecretSource
// Looks for LS_SECRET_AWS_ACCESS_KEY_ID, LS_SECRET_AWS_SECRET_ACCESS_KEY, etc.
```

This is ideal for CI/CD pipelines where secrets are injected as environment variables.

#### ManySecretSources

Chains multiple sources with fallback:

```kotlin
val secrets = ManySecretSources(
    EnvironmentSecretSource,              // Try environment first
    EncryptedFileSecretSource("production"), // Fall back to encrypted file
)
```

When prompting for a new secret, you'll be asked where to store it if multiple writable sources are available.

## Advanced Features

### Custom Terraform Emission

Service implementations can emit custom Terraform resources:

```kotlin
context(emitter: TerraformEmitter)
fun MyService.Settings.configureInfrastructure() {
    emitter.emit("myservice") {
        "resource.aws_s3_bucket.my_bucket" {
            "bucket" - "my-unique-bucket-name"
            "acl" - "private"
        }
    }
}
```

### Terraform Shell

For debugging and manual operations:

```kotlin
fun main() {
    MyDeployment.terraformShell()
    // Enter terraform commands interactively (without "terraform" prefix)
}
```

### Pre-defined Variables

`BaseTerraformEmitter` provides common Terraform variables:

- `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`: AWS credentials
- `AWS_PROFILE`: AWS CLI profile name (alternative to access key)
- `AWS_SSE_CUSTOMER_KEY`: Customer-provided encryption key for S3 (auto-generated)
- `MONGODB_ATLAS_PUBLIC_KEY` / `MONGODB_ATLAS_PRIVATE_KEY`: MongoDB Atlas API keys

## Deployment Workflow

A typical deployment follows these steps:

1. **Initial Setup**: Create your deployment configuration extending `BaseTerraformEmitter`
2. **Configure Secrets**: Run `editVars()` to set up credentials
3. **Generate Config**: Call `write()` to generate Terraform files
4. **Review Plan**: Run `terraform plan` to see what will be created
5. **Apply**: Run `deploy()` or `terraform apply` to create infrastructure
6. **Updates**: Modify your code, regenerate config, and apply changes

## Security Considerations

### Local Development

`EncryptedFileSecretSource` provides strong security for local development:

- ✅ Encrypted at rest with AES-256
- ✅ Password protected with PBKDF2 (100,000 iterations)
- ✅ Random salt per file (prevents rainbow table attacks)
- ✅ Suitable for development and testing environments
- ℹ️ For production, use dedicated secret managers for additional features (audit logs, rotation, etc.)

### Production Deployments

For production, consider:

- Using `EnvironmentSecretSource` with secrets from a proper secret manager
- Integrating with AWS Secrets Manager, HashiCorp Vault, or similar
- Never committing secrets to version control
- Using separate encrypted files for different environments

### CI/CD Integration

For CI/CD pipelines:

```kotlin
val secrets = ManySecretSources(
    EnvironmentSecretSource, // Primary source for CI/CD
    EncryptedFileSecretSource("ci"), // Fallback for local testing
)
```

Set environment variables in your CI system:

```bash
export LS_SECRET_AWS_ACCESS_KEY_ID="..."
export LS_SECRET_AWS_SECRET_ACCESS_KEY="..."
```

## Troubleshooting

### "Missing secret" Errors

If you see errors about missing secrets:

1. Run `editVars()` to configure them interactively
2. Check environment variables are set correctly
3. Verify your `SecretSource` implementation can access the secrets

### Terraform Command Failures

If Terraform commands fail:

1. Ensure Terraform is installed and on your PATH
2. Check that AWS credentials are configured correctly
3. Review Terraform logs for specific errors
4. Run `terraformShell()` to debug with manual commands

### File Not Found

If the terraform root directory doesn't exist:

- It will be created automatically by `write()`
- Ensure you have write permissions to the parent directory

## Examples

See also:

- [Deploy to AWS](deploy-aws.md) - AWS Lambda deployment with Terraform
- Demo module - Example server with complete deployment configuration

## API Reference

- **`BaseTerraformEmitter<S>`**: Base class for Terraform generation
    - `write()`: Generate Terraform JSON files
    - `deploy()`: Full deployment workflow (init/plan/apply)
    - `editVars()`: Interactive secret editor
    - `terraformShell()`: Interactive Terraform command shell

- **`SecretSource`**: Interface for secret retrieval
    - `get(need)`: Get required secret or throw
    - `getOrNull(need)`: Get optional secret

- **`PopulatableSecretSource`**: Secret source that can store secrets
    - `set(need, value)`: Store a secret
    - `prompt(need)`: Interactively prompt for a secret

## Next Steps

- [AWS Deployment Guide](deploy-aws.md) - Deploy to AWS Lambda
- [Server Configuration](server-configuration.md) - Configure your server settings
- [Service Abstractions](services.md) - Available service implementations
