package com.lightningkite.lightningserver.files

import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.serialization.parsingFileSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Used to serialize and deserialize a ServerFile as a String. This will also handle security for ServerFiles.
 * If security is required it will serialize as a pre-signed URL. It will also check deserializing of url to confirm it is valid.
 */
object ExternalServerFileSerializer: KSerializer<ServerFile> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = object: SerialDescriptor {
        override val kind: SerialKind = PrimitiveKind.STRING
        override val serialName: String = "ServerFile"
        override val elementsCount: Int get() = 0
        override fun getElementName(index: Int): String = error()
        override fun getElementIndex(name: String): Int = error()
        override fun isElementOptional(index: Int): Boolean = error()
        override fun getElementDescriptor(index: Int): SerialDescriptor = error()
        override fun getElementAnnotations(index: Int): List<Annotation> = error()
        override fun toString(): String = "PrimitiveDescriptor($serialName)"
        private fun error(): Nothing = throw IllegalStateException("Primitive descriptor does not have elements")
        override val annotations: List<Annotation> = ServerFile::class.annotations
    }

    override fun serialize(encoder: Encoder, value: ServerFile) {
        val file = FileSystem.resolve(value.location)
        if(file == null) {
            LoggerFactory.getLogger("com.lightningkite.lightningserver.files").warn("The given url (${value.location}) does not start with any files root.")
            encoder.encodeString(value.location)
        } else {
            encoder.encodeString(file.signedUrl)
        }
    }

    override fun deserialize(decoder: Decoder): ServerFile {
        val raw = decoder.decodeString()
        if(raw.startsWith("data:")) {
            val type = ContentType(raw.removePrefix("data:").substringBefore(';'))
            val base64 = raw.substringAfter("base64,")
            val data = Base64.getDecoder().decode(base64)
            val file = parsingFileSettings!!().root.resolveRandom(
                "uploaded/file",
                type.extension ?: "bin"
            )
            runBlocking {
                file.write(HttpContent.Binary(data, type))
            }
            return ServerFile(file.url)
        } else {
            val file = FileSystem.resolve(raw.substringBefore('?')) ?: throw BadRequestException("The given url ($raw) does not start with any files root.")
            if(file.checkSignature(raw.substringAfter('?')))
                return ServerFile(file.url)
            else
                throw BadRequestException("URL does not appear to be signed properly")
//            val root = files.root
//            val rootUrl = root.publicUrlUnsigned
//            if(!raw.startsWith(rootUrl)) throw BadRequestException("The given url ($raw) does not start with the files root ($rootUrl).")
//            val newFile = root.resolveFile(raw.removePrefix(rootUrl))
//
//            when(newFile) {
//                is AzFileObject -> {
//                    if(files.signedUrlExpirationSeconds != null) {
//                        // TODO: A local check like we do for AWS would be more performant
//                        runBlocking {
//                            if(!client.get(raw) { header("Range", "bytes=0-0") }.status.isSuccess()) throw BadRequestException("URL does not appear to be signed properly")
//                        }
//                    }
//                }
//                is S3FileObject -> {
//                    if(files.signedUrlExpirationSeconds != null) {
//                        val headers = raw.substringAfter('?').split('&').associate {
//                            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
//                        }
//                        val accessKey = files.storageUrl.substringAfter("://").substringBefore('@', "").substringBefore(':')
//                        val secretKey = files.storageUrl.substringAfter("://").substringBefore('@', "").substringAfter(':').substringBefore(':')
//                        val region = raw.substringAfter("://s3-").substringBefore(".amazonaws.com")
//                        val bucket = raw.substringAfter("amazonaws.com/").substringBefore('/')
//                        val objectPath = "/" + raw.substringAfter("amazonaws.com/").substringBefore("?")
//                        val date = headers["X-Amz-Date"]!!
//                        val algorithm = headers["X-Amz-Algorithm"]!!
//                        val expires = headers["X-Amz-Expires"]!!
//                        val credential = headers["X-Amz-Credential"]!!
//                        val scope = credential.substringAfter("/")
//
//                        val canonicalRequest = """
//                    GET
//                    $objectPath
//                    ${raw.substringAfter('?').substringBefore("&X-Amz-Signature=").split('&').sorted().joinToString("&")}
//                    host:s3-$region.amazonaws.com
//
//                    host
//                    UNSIGNED-PAYLOAD
//                """.trimIndent()
//
//                        val toSignString = """
//                    $algorithm
//                    $date
//                    $scope
//                    ${canonicalRequest.sha256()}
//                """.trimIndent()
//
//                        val signingKey = "AWS4$secretKey".toByteArray()
//                            .let { date.substringBefore('T').toByteArray().mac(it) }
//                            .let { region.toByteArray().mac(it) }
//                            .let { "s3".toByteArray().mac(it) }
//                            .let { "aws4_request".toByteArray().mac(it) }
//
//
//                        val signature = toSignString.toByteArray().mac(signingKey).toHex()
//
//                        if(signature != headers["X-Amz-Signature"]!!) {
//                            runBlocking {
//                                if(!client.get(raw) { header("Range", "bytes=0-0") }.status.isSuccess()) throw BadRequestException("URL does not appear to be signed properly")
//                            }
//                        }
//                    }
//                }
//            }
//            return ServerFile(raw)
        }
    }
}
