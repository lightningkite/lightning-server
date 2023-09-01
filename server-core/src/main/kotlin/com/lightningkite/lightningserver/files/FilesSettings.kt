@file:UseContextualSerialization(Duration::class)

package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.settings.Pluggable
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.json.JsonNames
import java.io.File
import java.time.Duration
import java.util.Base64
import kotlin.random.Random

/**
 * Settings that define what file storage solution to use and how to connect to it.
 */
@Serializable
data class FilesSettings(
    @JsonNames("storageUrl") val url: String = "file://${File("./local/files/").absolutePath}?secret=${Base64.getEncoder().encodeToString(Random.nextBytes(20))}",
    val signedUrlExpiration: Duration? = null
) : () -> FileSystem {
    companion object : Pluggable<FilesSettings, FileSystem>() {
        init {
            register("file") {
                Regex("""file://(?<folderPath>[^?]+)(\?(?<params>.*))?""").matchEntire(it.url)
                    ?.let { match ->
                        val params = match.groups["params"]?.value?.split('&')?.associate {
                            it.substringBefore('=') to it.substringAfter('=', "")
                        } ?: mapOf()
                        LocalFileSystem(
                            rootFile = File(match.groups["folderPath"]!!.value),
                            serveDirectory = params["serve"] ?: "uploaded-files",
                            signedUrlExpiration = it.signedUrlExpiration,
                            signer = SecureHasher.HS256(params["secret"]?.toByteArray() ?: throw IllegalArgumentException("No secret provided"))
                        )
                    }
                    ?: throw IllegalStateException("Invalid Local File storageUrl. It must follow the pattern: file:://[folderPath]|[servePath]\nServe Directory is Optional and will default to \"uploaded-files\"")
            }
        }
    }

    override fun invoke(): FileSystem = parse(url.substringBefore("://"), this)

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

