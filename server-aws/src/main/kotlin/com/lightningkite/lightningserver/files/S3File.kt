package com.lightningkite.lightningserver.files

import com.lightningkite.atZone
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.now
import io.ktor.http.*
import io.ktor.utils.io.charsets.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.*
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

data class S3File(val system: S3FileSystem, val path: File) : FileObject {
    override fun resolve(path: String): FileObject = S3File(system, this.path.resolve(path))
    override val name: String
        get() = path.name

    override val parent: FileObject?
        get() = path.parentFile?.let { S3File(system, path) } ?: if (path.unixPath.isNotEmpty()) system.root else null

    override suspend fun copyTo(other: FileObject) {
        if(other is S3File) {
            system.s3Async.copyObject {
                it.sourceBucket(system.bucket)
                it.destinationBucket(other.system.bucket)
                it.sourceKey(path.unixPath)
                it.destinationKey(other.path.unixPath)
            }.await()
        } else super.copyTo(other)
    }

    override suspend fun list(): List<FileObject>? = withContext(Dispatchers.IO) {
        try {
            val results = ArrayList<S3File>()
            var token: String? = null
            while (true) {
                val r = system.s3Async.listObjectsV2 {
                    it.bucket(system.bucket)
                    it.prefix(path.unixPath)
                    it.delimiter("/")
                    token?.let { t -> it.continuationToken(t) }
                }.await()
                results += r.contents().filter { !it.key().substringAfter(path.unixPath).contains('/') }
                    .map { S3File(system, File(it.key())) }
                if (r.isTruncated) token = r.nextContinuationToken()
                else break
            }
            results
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun head(): FileInfo? = withContext(Dispatchers.IO) {
        try {
            system.s3Async.headObject {
                it.bucket(system.bucket)
                it.key(path.unixPath)
            }.await().let {
                FileInfo(
                    type = ContentType(it.contentType()),
                    size = it.contentLength(),
                    lastModified = it.lastModified().toKotlinInstant()
                )
            }
        } catch (e: NoSuchKeyException) {
            null
        }
    }

    @Deprecated("Use get instead", replaceWith = ReplaceWith("get().stream()"))
    override suspend fun read(): InputStream = withContext(Dispatchers.IO) {
        system.s3.getObject(
            GetObjectRequest.builder().also {
                it.bucket(system.bucket)
                it.key(path.unixPath)
            }.build()
        )
    }

    override suspend fun put(content: HttpContent) {
        withContext(Dispatchers.IO) {
            system.s3.putObject(PutObjectRequest.builder().also {
                it.bucket(system.bucket)
                it.key(path.unixPath)
            }.build(), content.length?.let {
                RequestBody.fromContentProvider(
                    { runBlocking { content.stream() } }, it, content.type.toString()
                )
            } ?: RequestBody.fromContentProvider({ runBlocking { content.stream() } }, content.type.toString())
            )
        }
    }

    override suspend fun get(): HttpContent? {
        val s = system.s3.getObject(
            GetObjectRequest.builder().also {
                it.bucket(system.bucket)
                it.key(path.unixPath)
            }.build()
        )
        return HttpContent.Stream(
            getStream = { s },
            length = s.response().contentLength(),
            type = s.response().contentType()?.let(::ContentType) ?: ContentType.Application.OctetStream
        )
    }

    override suspend fun delete() {
        withContext(Dispatchers.IO) {
            system.s3Async.deleteObject {
                it.bucket(system.bucket)
                it.key(path.unixPath)
            }.await()
        }
    }

    override fun checkSignature(queryParams: String): Boolean {
        if (system.signedUrlDuration != null) {
            try {
                val headers = queryParams.split('&').associate {
                    URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(
                        it.substringAfter(
                            '=', ""
                        ), Charsets.UTF_8
                    )
                }
                val accessKey = system.credentialProvider.resolveCredentials().accessKeyId()
                val secretKey = system.credentialProvider.resolveCredentials().secretAccessKey()
                val objectPath = path.unixPath
                val date = headers["X-Amz-Date"] ?: return false
                val algorithm = headers["X-Amz-Algorithm"] ?: return false
                val expires = headers["X-Amz-Expires"] ?: return false
                val credential = headers["X-Amz-Credential"] ?: return false
                val scope = credential.substringAfter("/")

                val canonicalRequest = """
                GET
                ${"/" + objectPath.removePrefix("/")}
                ${queryParams.substringBefore("&X-Amz-Signature=").split('&').sorted().joinToString("&")}
                host:${system.bucket}.s3.${system.region.id()}.amazonaws.com
                
                host
                UNSIGNED-PAYLOAD
                """.trimIndent()

                val toSignString = """
                $algorithm
                $date
                $scope
                ${canonicalRequest.sha256()}
                """.trimIndent()

                val signingKey = "AWS4$secretKey".toByteArray().let { date.substringBefore('T').toByteArray().mac(it) }
                    .let { system.region.id().toByteArray().mac(it) }.let { "s3".toByteArray().mac(it) }
                    .let { "aws4_request".toByteArray().mac(it) }

                val regeneratedSig = toSignString.toByteArray().mac(signingKey).toHex()

                if (regeneratedSig == headers["X-Amz-Signature"]!!) return true
            } catch (e: Exception) {
                /* squish */
            }
            return super.checkSignature(queryParams)
        } else return true
    }

    private fun String.encodeURLPathSafe(): String = URLEncoder.encode(this, Charsets.UTF_8).replace("%2F", "/").replace("+", "%20")


    override val url: String
        get() = "https://${system.bucket}.s3.${system.region.id()}.amazonaws.com/${path.unixPath.encodeURLPathSafe()}"

    override val signedUrl: String
        get() = system.signedUrlDuration?.let { e ->
            val creds = system.creds()
            val accessKey = creds.access
            val tokenPreEncoded = creds.tokenPreEncoded
            var dateOnly = ""
            val date = now().atZone(TimeZone.UTC).run {
                buildString {
                    this.append(date.year.toString().padStart(4, '0'))
                    this.append(date.monthNumber.toString().padStart(2, '0'))
                    this.append(date.dayOfMonth.toString().padStart(2, '0'))
                    dateOnly = toString()
                    append("T")
                    this.append(time.hour.toString().padStart(2, '0'))
                    this.append(time.minute.toString().padStart(2, '0'))
                    this.append(time.second.toString().padStart(2, '0'))
                    append("Z")
                }
            }
            val objectPath = path.unixPath
            val preHeaders = tokenPreEncoded?.let {
                "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=${accessKey}%2F$dateOnly%2Fus-west-2%2Fs3%2Faws4_request&X-Amz-Date=$date&X-Amz-Expires=${e.inWholeSeconds}&X-Amz-Security-Token=${it}&X-Amz-SignedHeaders=host"
            } ?: run {
                "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=${accessKey}%2F$dateOnly%2Fus-west-2%2Fs3%2Faws4_request&X-Amz-Date=$date&X-Amz-Expires=${e.inWholeSeconds}&X-Amz-SignedHeaders=host"
            }
            val hashHolder = ByteArray(32)
            val canonicalRequestHasher = MessageDigest.getInstance("SHA-256")
            canonicalRequestHasher.update(constantBytesA)
            canonicalRequestHasher.update(objectPath.removePrefix("/").encodeURLPathSafe().toByteArray())
            canonicalRequestHasher.update(constantByteNewline)
            canonicalRequestHasher.update(preHeaders.toByteArray())
            canonicalRequestHasher.update(constantBytesC)
            canonicalRequestHasher.update(system.bucket.toByteArray())
            canonicalRequestHasher.update(constantBytesD)
            canonicalRequestHasher.update(system.region.id().toByteArray())
            canonicalRequestHasher.update(constantBytesE)
            canonicalRequestHasher.digest(hashHolder, 0, 32)
            val canonicalRequestHash = hashHolder.toHex()
            val finalHasher = Mac.getInstance("HmacSHA256")
            finalHasher.init(system.signingKey(dateOnly))
            finalHasher.update(constantBytesF)
            finalHasher.update(date.toByteArray())
            finalHasher.update(constantByteNewline)
            finalHasher.update(dateOnly.toByteArray())
            finalHasher.update(constantByteSlash)
            finalHasher.update(system.region.id().toByteArray())
            finalHasher.update(constantBytesH)
            finalHasher.update(canonicalRequestHash.toByteArray())
            finalHasher.doFinal(hashHolder, 0)
            val regeneratedSig = hashHolder.toHex()
            val result = "${url}?$preHeaders&X-Amz-Signature=$regeneratedSig"
            result
        } ?: url

    val officialSignedUrl: String
        get() = system.signedUrlDuration?.let { e ->
            system.signer.presignGetObject {
                it.signatureDuration(system.signedUrlDuration.toJavaDuration())
                it.getObjectRequest {
                    it.bucket(system.bucket)
                    it.key(path.unixPath)
                }
            }.url().toString()
        } ?: url

    companion object {
        private val constantBytesA = "GET\n/".toByteArray()
        private val constantBytesC = "\nhost:".toByteArray()
        private val constantBytesD = ".s3.".toByteArray()
        private val constantBytesE = (".amazonaws.com\n\nhost\nUNSIGNED-PAYLOAD").toByteArray()
        private val constantBytesF = "AWS4-HMAC-SHA256\n".toByteArray()
        private val constantByteNewline = '\n'.code.toByte()
        private val constantByteSlash = '/'.code.toByte()
        private val constantBytesH = "/s3/aws4_request\n".toByteArray()

        private val okChars = setOf('/', '.', '_', '-')
        private val HEX_ALPHABET = (('a'..'f') + ('A'..'F') + ('0'..'9')).toSet()
    }

    override fun uploadUrl(timeout: Duration): String = system.signer.presignPutObject {
        it.signatureDuration(timeout.toJavaDuration())
        it.putObjectRequest {
            it.bucket(system.bucket)
            it.key(path.unixPath)
        }
    }.url().toString()

    override fun toString(): String = url
}

//internal fun ByteArray.toHex(): String = BigInteger(1, this@toHex).toString(16).padStart(64, '0')
internal fun ByteArray.toHex(): String = buildString {
    for(item in this@toHex) {
        append(item.toUByte().toString(16).padStart(2, '0'))
    }
}
internal fun ByteArray.mac(key: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply {
    init(SecretKeySpec(key, "HmacSHA256"))
}.doFinal(this)

internal fun String.sha256(): String = MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHex()
