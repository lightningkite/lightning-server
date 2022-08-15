package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.core.*
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.*
import io.ktor.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files


class LocalFileSystem(rootFile: File, val serveDirectory: String, val signer: JwtSigner) : FileSystem {
    val rootFile: File = rootFile.absoluteFile
    override val root: FileObject = LocalFile(this, rootFile)

    init {
        FileSystem.register(this)
    }

    val fetch = ServerPath("$serveDirectory/{...}").get.handler {
        val location = signer.verify<String>(
            it.queryParameter("token") ?: throw BadRequestException("No token provided")
        ).removePrefix("/")
        if (it.wildcard == null) throw BadRequestException("No file to look up")
        val wildcard = it.wildcard.removePrefix("/")
        if (location != wildcard) throw BadRequestException("Token does not match file - token had ${location}, path had ${it.wildcard}")
        if (wildcard.contains("..")) throw IllegalStateException()
        val file = rootFile.resolve(wildcard)
        if(!file.exists()) throw NotFoundException("No file ${wildcard} found")
        val fileObject = LocalFile(this, file)
        if (!file.absolutePath.startsWith(rootFile.absolutePath)) throw IllegalStateException()
        val range = it.headers[HttpHeader.ContentRange] ?: it.headers[HttpHeader.Range]
        if (range != null) {
            val r = range.substringBefore('-').toLong() until range.substringAfter('-').toLong()
            HttpResponse(
                body = HttpContent.Binary(
                    bytes = run {
                        val f = RandomAccessFile(file, "r")
                        val array =
                            ByteArray(r.run { (endInclusive - start + 1).coerceAtLeast(0L) }.toInt())
                        f.seek(r.start)
                        f.readFully(array)
                        array
                    },
                    type = fileObject.contentTypeFile.takeIf { it.exists() }?.readText()?.let { ContentType(it) } ?: ContentType.Application.OctetStream
                ),
            )
        } else {
            HttpResponse(
                body = HttpContent.Stream(
                    getStream = { file.inputStream() },
                    length = file.length(),
                    type = fileObject.contentTypeFile.takeIf { it.exists() }?.readText()?.let { ContentType(it) } ?: ContentType.Application.OctetStream
                ),
            )
        }
    }

    val upload = ServerPath("$serveDirectory/{...}").put.handler {
        if (it.wildcard == null) throw BadRequestException("No file to look up")
        val parsedToken = signer.verify<String>(
            it.queryParameter("token") ?: throw BadRequestException("No token provided")
        )
        if (!parsedToken.startsWith("W|")) throw UnauthorizedException("Token does not hold write permissions")
        val location = parsedToken.removePrefix("W|").removePrefix("/")
        val wildcard = it.wildcard.removePrefix("/")
        if (location != wildcard) throw BadRequestException("Token does not match file")
        if (wildcard.contains("..")) throw IllegalStateException()
        val file = root.resolve(wildcard)
        file.write(it.body!!)
        HttpResponse(status = HttpStatus.NoContent)
    }
}

