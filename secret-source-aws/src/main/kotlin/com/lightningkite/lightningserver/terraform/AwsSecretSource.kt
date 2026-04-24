package com.lightningkite.lightningserver.terraform

import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.json.Json
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.*

/**
 * Exception thrown when AWS Secrets Manager operations fail.
 *
 * @param message Description of the failure
 * @param cause The underlying AWS SDK exception
 */
public class AwsSecretException(message: String?, cause: Throwable?) : Exception(message, cause)

/**
 * A secret source that reads and writes secrets to AWS Secrets Manager.
 *
 * This implementation provides integration with AWS Secrets Manager for storing sensitive
 * configuration values like API keys, database credentials, and other secrets. Secrets are
 * stored as JSON-serialized values and are namespaced with a configurable ID prefix.
 *
 * **Usage:**
 * ```kotlin
 * val secretSource = AwsSecretSource("myapp", Region.US_EAST_1)
 * val dbPassword = secretSource.getOrNull(TerraformNeed("db-password", String.serializer()))
 * ```
 *
 * **Secret naming:** Secrets are stored with the pattern `{idPrefix}/{name}`, for example:
 * - idPrefix: "prod-api"
 * - name: "database-password"
 * - Actual AWS secret ID: "prod-api/database-password"
 *
 * **Operations:**
 * - [getOrNull]: Retrieves a secret, returns null if not found
 * - [set]: Creates or updates a secret (automatically detects if secret exists)
 *
 * **Error handling:**
 * - Returns null when secret doesn't exist ([ResourceNotFoundException])
 * - Throws [AwsSecretException] for other AWS Secrets Manager failures
 *
 * @param idPrefix The prefix to prepend to all secret names (typically environment or application name)
 * @param region The AWS region where secrets are stored
 *
 * @see PopulatableSecretSource
 * @see TerraformNeed
 */
public class AwsSecretSource(public val profile: String, private val idPrefix: String, region: Region) :
    PopulatableSecretSource {

    private val json = Json
    private val client = SecretsManagerClient.builder()
        .credentialsProvider(ProfileCredentialsProvider.create(profile))
        .region(region)
        .build()

    override val name: String = "AWS Secrets"

    /**
     * Constructs the full AWS secret ID by combining the prefix with the secret name.
     *
     * @param name The base name of the secret
     * @return The full secret ID in the format "{idPrefix}/{name}"
     */
    private fun getId(name: String) = "$idPrefix/$name"

    override fun <T> getOrNull(need: TerraformNeed<T>): T? {
        return try {
            val response = client.getSecretValue(
                GetSecretValueRequest.builder()
                    .secretId(getId(need.name))
                    .build()
            )
            json.decodeFromString(need.serializer, response.secretString())
        } catch (e: ResourceNotFoundException) {
            null
        } catch (e: SecretsManagerException) {
            throw AwsSecretException("Attempt to retrieve secret failed", e)
        }
    }

    override fun <T> set(need: TerraformNeed<T>, value: T) {
        try {
            client.getSecretValue(
                GetSecretValueRequest.builder()
                    .secretId(getId(need.name))
                    .build()
            )

            client.updateSecret(
                UpdateSecretRequest.builder()
                    .secretId(getId(need.name))
                    .secretString(json.encodeToString(need.serializer, value))
                    .build()
            )
        } catch (e: ResourceNotFoundException) {
            client.createSecret(
                CreateSecretRequest.builder()
                    .name(getId(need.name))
                    .secretString(json.encodeToString(need.serializer, value))
                    .build()
            )
        } catch (e: SecretsManagerException) {
            throw AwsSecretException("Attempt to save secret failed", e)
        }
    }
}

/*
 * TODO: API Recommendations
 *
 * 1. Consider implementing resource cleanup with a close() method to shut down the SecretsManagerClient
 *
 * 2. The set() method checks if secret exists by calling getSecretValue, then updates or creates.
 *    This is two API calls. Consider using DescribeSecret instead (cheaper/faster) or handling
 *    the ResourceNotFoundException from updateSecret directly.
 *
 * 3. Consider adding retry logic with exponential backoff for transient AWS failures
 *
 * 4. The client is created eagerly in the constructor. Consider lazy initialization to avoid
 *    unnecessary AWS API calls when secrets aren't needed.
 *
 * 5. Consider adding support for secret versioning/rotation via AWS Secrets Manager's built-in features
 *
 * 6. Consider documenting IAM permissions required (secretsmanager:GetSecretValue,
 *    secretsmanager:CreateSecret, secretsmanager:UpdateSecret)
 *
 * 7. The Json instance uses default settings. Consider making it configurable or documenting
 *    that secrets must be compatible with default kotlinx.serialization JSON settings.
 */