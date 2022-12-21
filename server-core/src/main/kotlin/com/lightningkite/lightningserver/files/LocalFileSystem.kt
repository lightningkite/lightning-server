package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.core.*
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.*
import java.io.File
import java.io.RandomAccessFile


class LocalFileSystem(rootFile: File, val serveDirectory: String, val signer: JwtSigner) : FileSystem {
    val rootFile: File = rootFile.absoluteFile
    override val root: FileObject = LocalFile(this, rootFile)

    init {
        FileSystem.register(this)
    }

    val fetch = ServerPath("$serveDirectory/{...}").get.handler {
        val location = signer.verify(
            it.queryParameter("token") ?: throw BadRequestException("No token provided")
        ).removePrefix("/")
        if (it.wildcard == null) throw BadRequestException("No file to look up")
        val wildcard = it.wildcard.removePrefix("/")
        if (location != wildcard) throw BadRequestException("Token does not match file - token had ${location}, path had ${it.wildcard}")
        if (wildcard.contains("..")) throw IllegalStateException()
        val file = rootFile.resolve(wildcard)
        if (!file.exists()) throw NotFoundException("No file ${wildcard} found")
        val fileObject = LocalFile(this, file)
        if (!file.absolutePath.startsWith(rootFile.absolutePath)) throw IllegalStateException()
        val range = it.headers[HttpHeader.ContentRange] ?: it.headers[HttpHeader.Range]
        val contentType = fileObject.contentTypeFile
            .takeIf { it.exists() }
            ?.readText()
            ?.let { ContentType(it) }
            ?: ContentType.fromExtension(file.extension)
        if (range != null) {
            val trimmed = range.substringAfter("=")
            val parts = trimmed.split(",").map { it.trim() }

            val content = parts.map { part ->
                val start = part.substringBefore('-').takeIf { it.isNotEmpty() }?.toLong() ?: 0
                val end = part.substringAfter('-').takeIf { it.isNotEmpty() }?.toLong()
                if (start == 0L && end == null) {
                    HttpContent.Stream(
                        getStream = { file.inputStream() },
                        length = file.length(),
                        type = contentType
                    )
                } else {
                    val f = RandomAccessFile(file, "r")
                    val array = if (end != null)
                        ByteArray(end.minus(start).plus(1).coerceAtLeast(0L).toInt())
                    else
                        ByteArray(f.length().minus(start).plus(1).coerceAtLeast(0L).toInt())
                    f.seek(start)
                    f.readFully(array)
                    HttpContent.Binary(
                        bytes = array,
                        type = contentType
                    )
                }
            }

            //TODO: Support sending multiple parts back. Currently it only uses the first part.
            HttpResponse(
                body = content.first(),
            )
        } else {
            HttpResponse(
                body = HttpContent.Stream(
                    getStream = { file.inputStream() },
                    length = file.length(),
                    type = contentType
                ),
            )
        }
    }

    val upload = ServerPath("$serveDirectory/{...}").put.handler {
        if (it.wildcard == null) throw BadRequestException("No file to look up")
        val parsedToken = signer.verify(
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

