package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.core.*
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.*
import java.io.File


class LocalFileSystem(val rootFile: File, val serveDirectory: String, val signer: JwtSigner): FileSystem {
    override val root: FileObject = LocalFile(this, rootFile)
    init {
        FileSystem.register(this)
        routing {
            path("$serveDirectory/{...}").apply{
                get.handler {
                    val location = signer.verify<String>(it.queryParameter("token") ?: throw BadRequestException("No token provided"))
                    if(location != it.wildcard) throw BadRequestException("Token does not match file")
                    if(it.wildcard.contains("..")) throw IllegalStateException()
                    val file = rootFile.resolve(it.wildcard)
                    if(!file.absolutePath.startsWith(rootFile.absolutePath)) throw IllegalStateException()
                    HttpResponse(
                        body = HttpContent.Stream(
                            getStream = { file.inputStream() },
                            length = file.length(),
                            type = ContentType.fromExtension(file.extension)
                        ),
                    )
                }
                post.handler {
                    val parsedToken = signer.verify<String>(it.queryParameter("token") ?: throw BadRequestException("No token provided"))
                    if(!parsedToken.startsWith("W|")) throw UnauthorizedException("Token does not hold write permissions")
                    val location = parsedToken.removePrefix("W|")
                    if(location != it.wildcard) throw BadRequestException("Token does not match file")
                    if(it.wildcard.contains("..")) throw IllegalStateException()
                    val file = root.resolve(it.wildcard)
                    file.write(it.body!!)
                    HttpResponse(status = HttpStatus.NoContent)
                }
            }
        }
    }
}

