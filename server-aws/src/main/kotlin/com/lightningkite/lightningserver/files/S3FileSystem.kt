package com.lightningkite.lightningserver.files

import com.lightningkite.atZone
import com.lightningkite.now
import io.ktor.http.*
import kotlinx.datetime.TimeZone
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration
import kotlin.time.toJavaDuration

class S3FileSystem(
    val region: Region,
    val credentialProvider: AwsCredentialsProvider,
    val bucket: String,
    val signedUrlDuration: Duration? = null
) : FileSystem {
    val signedUrlDurationJava: java.time.Duration? = signedUrlDuration?.toJavaDuration()
    override val rootUrls: List<String> = listOf(
        "https://${bucket}.s3.${region.id()}.amazonaws.com/",
        "https://s3-${region.id()}.amazonaws.com/${bucket}/",
    )
    private var credsOnHand: AwsCredentials? = null
    private var credsOnHandMs: Long = 0
    private var credsDirect: DirectAwsCredentials? = null
    data class DirectAwsCredentials(
        val access: String,
        val secret: String,
        val token: String? = null
    ) {
        val tokenPreEncoded = token?.encodeURLParameter()
    }
    fun creds(): DirectAwsCredentials {
        val onHand = credsDirect
        return if(onHand == null || System.currentTimeMillis() > credsOnHandMs) {
            val x = credentialProvider.resolveCredentials()
            credsOnHand = x
            val y = DirectAwsCredentials(
                access = x.accessKeyId(),
                secret = x.secretAccessKey(),
                token = (x as? AwsSessionCredentials)?.sessionToken(),
            )
            credsDirect = y
            credsOnHandMs = x.expirationTime().getOrNull()?.toEpochMilli() ?: (System.currentTimeMillis() + 24L*60*60*1000)
            y
        } else onHand
    }
    private var lastSigningKey: SecretKeySpec? = null
    private var lastSigningKeyDate: String = ""
    fun signingKey(date: String): SecretKeySpec {
        val lastSigningKey = lastSigningKey
        if(lastSigningKey == null || lastSigningKeyDate != date) {
            val secretKey = creds().secret
            val newKey = "AWS4$secretKey".toByteArray()
                .let { date.toByteArray().mac(it) }
                .let { region.id().toByteArray().mac(it) }
                .let { "s3".toByteArray().mac(it) }
                .let { "aws4_request".toByteArray().mac(it) }
                .let { SecretKeySpec(it, "HmacSHA256") }
            this.lastSigningKey = newKey
            lastSigningKeyDate = date
            return newKey
        } else return lastSigningKey
    }

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
                Regex("""s3://(?:(?<user>[^:]+):(?<password>[^@]+)@)?(?<bucket>[^.]+)\.(?:s3-)?(?<region>[^.]+)\.amazonaws.com/?""").matchEntire(it.url)?.let { match ->
                    val user = match.groups["user"]?.value ?: ""
                    val password = match.groups["password"]?.value ?: ""
                    S3FileSystem(
                        Region.of(match.groups["region"]!!.value),
                        if (user.isNotBlank() && password.isNotBlank()) {
                            StaticCredentialsProvider.create(object : AwsCredentials {
                                override fun accessKeyId(): String = user
                                override fun secretAccessKey(): String = password
                            })
                        } else DefaultCredentialsProvider.create(),
                        match.groups["bucket"]!!.value,
                        it.signedUrlExpiration
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