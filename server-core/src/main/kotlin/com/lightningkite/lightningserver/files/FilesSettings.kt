@file:UseContextualSerialization(Duration::class)
package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.settings.Pluggable
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.io.File
import java.time.Duration

/**
 * FileSettings defines where server files and user content is stored. This used ApacheVFS which allows the filesystem to be
 * a variety of sources. For now this is set up to handle a local file system, a s3 bucket, or an azure blob container.
 *
 * @param storageUrl Defines where the file system is. This follows ApacheVFS standards.
 * @param userContentPath A path you wish all file paths to be prefixed with.
 * @param signedUrlExpirationSeconds When dealing with secured filesystems that require url signing this will determine how long pre-signed URLs will be valid for.
 */

@Serializable
data class FilesSettings(
    val storageUrl: String = "file://${File("./local/files/").absolutePath}",
    val signedUrlExpiration: Duration? = null,
    val jwtSigner: JwtSigner = JwtSigner()
) : () -> FileSystem {
    companion object : Pluggable<FilesSettings, FileSystem>() {
        init {
            register("file") {
                LocalFileSystem(
                    rootFile = File(it.storageUrl.substringAfter("file://")),
                    serveDirectory = "uploaded-files",
                    signer = it.jwtSigner
                )
            }
        }
    }

    override fun invoke(): FileSystem = parse(storageUrl.substringBefore("://"), this)

//    init {
//        if(storageUrl.startsWith("az")) {
//            val auth = StaticUserAuthenticator("", storageUrl.substringAfter("://").substringBefore('.'), this.key ?: throw IllegalStateException("Azure file system requested, but no key was provided."))
//            println("Establishing authenticator for Azure as $auth")
//            DefaultFileSystemConfigBuilder.getInstance().setUserAuthenticator(AzFileProvider.getDefaultFileSystemOptions(), auth)
//            println(DefaultFileSystemConfigBuilder.getInstance().getUserAuthenticator(AzFileProvider.getDefaultFileSystemOptions()))
//        }
//
//        if(storageUrl.startsWith("file://")) {
//            routing {
//                path("$userContentPath/{...}").apply{
//                    get.handler {
//                        if(it.wildcard?.contains("..") != false) throw IllegalStateException()
//                        val file = root.resolveFile(it.wildcard).content
//                        HttpResponse(
//                            body = HttpContent.Stream(
//                                getStream = { file.inputStream },
//                                length = file.size,
//                                type = ContentType(file.contentInfo.contentType)
//                            ),
//                        )
//                    }
//                    post.handler {
//                        val location = jwtSigner.verify<String>(it.queryParameter("token") ?: throw BadRequestException("No token provided"))
//                        if(location != it.wildcard) throw BadRequestException("Token does not match file")
//                        if(it.wildcard.contains("..")) throw IllegalStateException()
//                        val file = root.resolveFile(it.wildcard).content
//                        it.body?.stream()?.copyTo(file.outputStream)
//                        HttpResponse(status = HttpStatus.NoContent)
//                    }
//                }
//            }
//        }
//    }
}

