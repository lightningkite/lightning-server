package com.lightningkite.ktorbatteries.files

import com.dalet.vfs2.provider.azure.AzFileObject
import com.github.vfss3.S3FileObject
import com.lightningkite.ktorbatteries.client
import com.lightningkite.ktordb.ServerFile
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.plugins.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.net.URLDecoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ExternalServerFileSerializer: KSerializer<ServerFile> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ServerFile", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ServerFile) {
        val root = FilesSettings.instance.root
        val rootUrl = root.publicUrlUnsigned
        if(!value.location.startsWith(rootUrl)) {
            LoggerFactory.getLogger("com.lightningkite.ktorbatteries.files").warn("The given url (${value.location}) does not start with the files root ($rootUrl).")
            encoder.encodeString(value.location)
        } else {
            val newFile = root.resolveFile(value.location.removePrefix(rootUrl).substringBefore('?'))
            encoder.encodeString(newFile.publicUrl)
        }
    }

    override fun deserialize(decoder: Decoder): ServerFile {
        val root = FilesSettings.instance.root
        val rootUrl = root.publicUrlUnsigned
        val raw = decoder.decodeString()
        if(!raw.startsWith(rootUrl)) throw BadRequestException("The given url ($raw) does not start with the files root ($rootUrl).")
        val newFile = root.resolveFile(raw.removePrefix(rootUrl))
        when(newFile) {
            is AzFileObject -> {
                if(FilesSettings.instance.signedUrlExpirationSeconds != null) {
                    // TODO: A local check like we do for AWS would be more performant
                    runBlocking {
                        if(!client.get(raw) { header("Range", "bytes=0-0") }.status.isSuccess()) throw BadRequestException("URL does not appear to be signed properly")
                    }
                }
            }
            is S3FileObject -> {
                if(FilesSettings.instance.signedUrlExpirationSeconds != null) {
                    val headers = raw.substringAfter('?').split('&').associate {
                        URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
                    }
                    val accessKey = FilesSettings.instance.storageUrl.substringAfter("://").substringBefore('@', "").substringBefore(':')
                    val secretKey = FilesSettings.instance.storageUrl.substringAfter("://").substringBefore('@', "").substringAfter(':').substringBefore(':')
                    val region = raw.substringAfter("://s3-").substringBefore(".amazonaws.com")
                    val bucket = raw.substringAfter("amazonaws.com/").substringBefore('/')
                    val objectPath = "/" + raw.substringAfter("amazonaws.com/").substringBefore("?")
                    val date = headers["X-Amz-Date"]!!
                    val algorithm = headers["X-Amz-Algorithm"]!!
                    val expires = headers["X-Amz-Expires"]!!
                    val credential = headers["X-Amz-Credential"]!!
                    val scope = credential.substringAfter("/")

                    val canonicalRequest = """
                    GET
                    $objectPath
                    ${raw.substringAfter('?').substringBefore("&X-Amz-Signature=").split('&').sorted().joinToString("&")}
                    host:s3-$region.amazonaws.com

                    host
                    UNSIGNED-PAYLOAD
                """.trimIndent()

                    val toSignString = """
                    $algorithm
                    $date
                    $scope
                    ${canonicalRequest.sha256()}
                """.trimIndent()

                    val signingKey = "AWS4$secretKey".toByteArray()
                        .let { date.substringBefore('T').toByteArray().mac(it) }
                        .let { region.toByteArray().mac(it) }
                        .let { "s3".toByteArray().mac(it) }
                        .let { "aws4_request".toByteArray().mac(it) }


                    val signature = toSignString.toByteArray().mac(signingKey).toHex()

                    if(signature != headers["X-Amz-Signature"]!!) {
                        runBlocking {
                            if(!client.get(raw) { header("Range", "bytes=0-0") }.status.isSuccess()) throw BadRequestException("URL does not appear to be signed properly")
                        }
                    }
                }
            }
        }
        return ServerFile(raw)
    }
}

private fun ByteArray.toHex(): String = BigInteger(1, this@toHex).toString(16)
private fun ByteArray.mac(key: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply {
    init(SecretKeySpec(key, "HmacSHA256"))
}.doFinal(this)
private fun String.sha256(): String = MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHex()
