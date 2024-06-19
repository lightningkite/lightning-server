package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.files.ExternalServerFileSerializer
import com.lightningkite.lightningserver.files.fileObject
import com.lightningkite.lightningserver.files.resolveRandom
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.schedule.schedule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

object FileRedirectHandler : Serialization.HttpContentHandler {
    override val contentType: ContentType = ContentType.Text.UriList

    override suspend fun <T> invoke(content: HttpContent, serializer: KSerializer<T>): T {
        val loc = content.text().substringBefore("\n").trim()
        val f = ServerFile(loc).fileObject
        val body = f.get() ?: throw BadRequestException("No file found at '$loc'.")
        val subtype = content.type.parameters["subtype"]?.let { ContentType(it) } ?: ContentType.Application.Json
        val basis = Serialization.parsers[subtype] ?: throw BadRequestException("No parser found for type ${subtype}.")
        return basis(body, serializer)
    }

    override suspend fun <T> invoke(contentType: ContentType, serializer: KSerializer<T>, value: T): HttpContent {
        val subtype = contentType.parameters["subtype"]?.let { ContentType(it) } ?: ContentType.Application.Json
        val basis = Serialization.emitters[subtype] ?: throw BadRequestException("No parser found for type ${subtype}.")
        val file = ExternalServerFileSerializer.fileSystem().root.resolveRandom(
            "temp-download/",
            basis.contentType.extension ?: ""
        )
        file.put(basis.invoke(subtype, serializer, value))
        return HttpContent.Text(file.signedUrl + "\r\n", contentType)
    }

    val cleanSchedule = schedule("cleanRedirectToFiles", 1.days) {
        ExternalServerFileSerializer.fileSystem().root.resolve("temp-download").list()?.forEach {
            it.delete()
        }
    }
}