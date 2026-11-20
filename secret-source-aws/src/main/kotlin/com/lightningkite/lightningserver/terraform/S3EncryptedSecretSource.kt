package com.lightningkite.lightningserver.terraform

import com.lightningkite.services.terraform.TerraformNeed
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.io.File

/**
 * An [EncryptedFileSecretSource] whose encrypted file lives at `s3://{bucket}/{key}` instead of on local disk.
 *
 * The bucket must already exist. Writes replace the whole object, so two people storing secrets at the
 * same moment can overwrite each other; turn on bucket versioning to be able to recover.
 */
public class S3EncryptedSecretSource(
    public val profile: String,
    private val bucket: String,
    private val key: String,
    region: Region,
    private val passwordFetcher: PasswordFetcher = PasswordFetcher(),
) : PopulatableSecretSource {

    private companion object {
        init {
            quietAwsSdkLogging()
        }
    }

    private val client = S3Client.builder()
        .credentialsProvider(ProfileCredentialsProvider.create(profile))
        .region(region)
        .build()

    override val name: String = "s3://$bucket/$key"

    private val file = File.createTempFile("secrets", ".json.enc").apply { deleteOnExit() }

    private val local by lazy {
        try {
            file.writeBytes(client.getObjectAsBytes { it.bucket(bucket).key(key) }.asByteArray())
        } catch (e: NoSuchKeyException) {
            file.delete()
        }
        EncryptedFileSecretSource(file, name, passwordFetcher)
    }

    override fun <T> getBoxed(need: TerraformNeed<T>): Stored<T>? = local.getBoxed(need)

    override fun <T> set(need: TerraformNeed<T>, value: T) {
        local.set(need, value)
        client.putObject({ it.bucket(bucket).key(key).contentType("application/json") }, RequestBody.fromFile(file))
    }
}
