package com.lightningkite.lightningserver.files

import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class S3FileSystem(
    val region: Region,
    val credentialProvider: AwsCredentialsProvider,
    val bucket: String,
    val signedUrlExpirationSeconds: Int? = null
) : FileSystem {
    override val rootUrls: List<String> = listOf(
        "https://${bucket}.s3.${region.id()}.amazonaws.com/",
        "https://s3-${region.id()}.amazonaws.com/${bucket}/",
    )

    val s3: S3Client by lazy {
        S3Client.builder()
            .region(region)
            .credentialsProvider(credentialProvider)
            .build()
    }
    val s3Async: S3AsyncClient by lazy {
        S3AsyncClient.builder()
            .region(region)
            .credentialsProvider(credentialProvider)
            .build()
    }
    val signer by lazy {
        S3Presigner.builder()
            .region(region)
            .credentialsProvider(credentialProvider)
            .build()
    }

    companion object {
        init {
            FilesSettings.register("s3") {
                Regex("""s3://([^:]+)([^@]+)@([^.]+)\.([^.]+)\.amazonaws.com/""").matchEntire(it.storageUrl)?.let { match ->
                    val user = match.groupValues[1]
                    val password = match.groupValues[2]
                    S3FileSystem(
                        Region.of(match.groupValues[4]),
                        if (user.isNotBlank() && password.isNotBlank()) {
                            StaticCredentialsProvider.create(object : AwsCredentials {
                                override fun accessKeyId(): String = user
                                override fun secretAccessKey(): String = password
                            })
                        } else DefaultCredentialsProvider.create(),
                        match.groupValues[3],
                        it.signedUrlExpiration?.toSeconds()?.toInt()
                    )
                }
                    ?: throw IllegalStateException("Invalid S3 storageUrl. The URL should match the pattern: s3://[user]:[password]@[bucket].[region].amazonaws.com/")
            }
        }
    }

    override val root: FileObject = S3File(this, File(""))

    init {
        FileSystem.register(this)
    }
}