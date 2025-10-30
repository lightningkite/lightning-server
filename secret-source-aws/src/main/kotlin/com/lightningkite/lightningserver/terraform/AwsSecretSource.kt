package com.lightningkite.lightningserver.terraform

import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.json.Json
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException
import software.amazon.awssdk.services.secretsmanager.model.UpdateSecretRequest

public class AwsSecretException(message: String?, cause: Throwable?): Exception(message, cause)

public class AwsSecretSource(private val idPrefix: String, region: Region): PopulatableSecretSource {

    private val json = Json
    private val client = SecretsManagerClient.builder()
        .region(region)
        .build()

    override val name: String = "AWS Secrets"

    private fun getId(name: String) = "$idPrefix/$name"

    override fun <T> getOrNull(need: TerraformNeed<T>): T? {
        return try{
            val response = client.getSecretValue(GetSecretValueRequest.builder()
                .secretId(getId(need.name))
                .build())
            json.decodeFromString(need.serializer, response.secretString())
        } catch (e: ResourceNotFoundException){
            null
        } catch (e: SecretsManagerException){
            throw AwsSecretException("Attempt to retrieve secret failed", e)
        }
    }

    override fun <T> set(need: TerraformNeed<T>, value: T) {
        try{
            client.getSecretValue(GetSecretValueRequest.builder()
                .secretId(getId(need.name))
                .build())

            client.updateSecret(UpdateSecretRequest.builder()
                .secretId(getId(need.name))
                .secretString(json.encodeToString(need.serializer, value))
                .build()
            )
        } catch (e: ResourceNotFoundException){
            client.createSecret(CreateSecretRequest.builder()
                .name(getId(need.name))
                .secretString(json.encodeToString(need.serializer, value))
                .build()
            )
        } catch (e: SecretsManagerException){
            throw AwsSecretException("Attempt to save secret failed", e)
        }
    }
}