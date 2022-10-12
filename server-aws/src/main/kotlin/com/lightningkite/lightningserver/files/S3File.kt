package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.*
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class S3File(val system: S3FileSystem, val path: File) : FileObject {
    override fun resolve(path: String): FileObject = S3File(system, this.path.resolve(path))

    override val parent: FileObject?
        get() = path.parentFile?.let { S3File(system, path) } ?: if (path.path.isNotEmpty()) system.root else null

    override suspend fun list(): List<FileObject>? = withContext(Dispatchers.IO) {
        try {
            val results = ArrayList<S3File>()
            var token: String? = null
            while (true) {
                val r = system.s3Async.listObjectsV2 {
                    it.bucket(system.bucket)
                    it.prefix(path.path)
                    token?.let { t -> it.continuationToken(t) }
                }.await()
                results += r.contents().filter { !it.key().substringAfter(path.toString()).contains('/') }
                    .map { S3File(system, File(it.key())) }
                if (r.isTruncated) token = r.nextContinuationToken()
                else break
            }
            results
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun info(): FileInfo? = withContext(Dispatchers.IO) {
        try {
            system.s3Async.headObject {
                it.bucket(system.bucket)
                it.key(path.toString())
            }.await().let {
                FileInfo(
                    type = ContentType(it.contentType()), size = it.contentLength(), lastModified = it.lastModified()
                )
            }
        } catch (e: NoSuchKeyException) {
            null
        }
    }

    override suspend fun write(content: HttpContent) {
        withContext(Dispatchers.IO) {
            system.s3.putObject(PutObjectRequest.builder().also {
                it.bucket(system.bucket)
                it.key(path.path)
            }.build(), content.length?.let {
                RequestBody.fromContentProvider(
                    { content.stream() }, it, content.type.toString()
                )
            } ?: RequestBody.fromContentProvider({ content.stream() }, content.type.toString())
            )
        }
    }

    override suspend fun read(): InputStream = withContext(Dispatchers.IO) {
        system.s3.getObject(
            GetObjectRequest.builder().also {
                it.bucket(system.bucket)
                it.key(path.path)
            }.build()
        )
    }

    override suspend fun delete() {
        withContext(Dispatchers.IO) {
            system.s3Async.deleteObject {
                it.bucket(system.bucket)
                it.key(path.path)
            }.await()
        }
    }

    override fun checkSignature(queryParams: String): Boolean {
        if (system.signedUrlExpirationSeconds != null) {
            val headers = queryParams.split('&').associate {
                URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(
                    it.substringAfter(
                        '=', ""
                    ), Charsets.UTF_8
                )
            }
            val accessKey = system.credentialProvider.resolveCredentials().accessKeyId()
            val secretKey = system.credentialProvider.resolveCredentials().secretAccessKey()
            val objectPath = path.path
            val date = headers["X-Amz-Date"]!!
            val algorithm = headers["X-Amz-Algorithm"]!!
            val expires = headers["X-Amz-Expires"]!!
            val credential = headers["X-Amz-Credential"]!!
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
            return super.checkSignature(queryParams)
        } else return true
    }

    override val url: String
        get() = "https://${system.bucket}.s3.${system.region.id()}.amazonaws.com/${path.path}"

    override val signedUrl: String
        get() = system.signer.presignGetObject {
            system.signedUrlExpirationSeconds?.let { e -> it.signatureDuration(Duration.ofSeconds(e.toLong())) }
            it.getObjectRequest {
                it.bucket(system.bucket)
                it.key(path.path)
            }
        }.url().toString()

    override fun uploadUrl(timeout: Duration): String = system.signer.presignPutObject {
        it.signatureDuration(timeout)
        it.putObjectRequest {
            it.bucket(system.bucket)
            it.key(path.path)
        }
    }.url().toString()

    override fun toString(): String = url

    override suspend fun startMultipart(): FileObject.FileObjectMultipartUpload {
        TODO("Not yet implemented")
    }

    override suspend fun uploadPartUrl(multipartKey: String, multipartId: String, partNumber: Int): String {
        TODO("Not yet implemented")
    }

    override suspend fun finishMultipart(multipartKey: String, multipartId: String) {
        TODO("Not yet implemented")
    }
}

private fun ByteArray.toHex(): String = BigInteger(1, this@toHex).toString(16)
private fun ByteArray.mac(key: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply {
    init(SecretKeySpec(key, "HmacSHA256"))
}.doFinal(this)

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHex()