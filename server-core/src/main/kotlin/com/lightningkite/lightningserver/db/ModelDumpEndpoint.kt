@file:UseContextualSerialization(ServerFile::class)
package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailClient
import com.lightningkite.lightningserver.email.EmailLabeledValue
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.files.FileSystem
import com.lightningkite.lightningserver.files.fileObject
import com.lightningkite.lightningserver.files.resolveRandom
import com.lightningkite.lightningserver.files.serverFile
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.kabobCase
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.now
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.serializer
import java.io.*
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

open class ModelDumpEndpoints<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    path: ServerPath,
    val info: ModelInfo<*, T, ID>,
    val authOptions: AuthOptions<USER>,
    val file: ()->FileSystem,
    val email: ()->EmailClient,
) : ServerPathGroup(path) {

    val collectionName get() = info.collectionName

    fun DumpType.ext() = when(this) {
        DumpType.CSV -> "csv"
        DumpType.JSON_LINES -> "jsonl"
    }

    val dump = path("dump").post.api(
        authOptions = authOptions,
        inputType = DumpRequest.serializer(info.serialization.serializer),
        outputType = String.serializer(),
        summary = "Dump",
        description = "Get a dump file of all the models.",
        errorCases = listOf(),
        implementation = { input: DumpRequest<T> ->
            val file = file().root.resolve("temp-files-dump").resolveRandom(info.collectionName.kabobCase(), input.type.ext() + ".zip")
            dumpTask(DumpTask(input, file.serverFile, authOrNull?.serializable(now().plus(10.minutes))))
            file.signedUrl
        }
    )
    @Serializable data class DumpTask<T>(val request: DumpRequest<T>, val file: ServerFile, val onBehalfOf: RequestAuthSerializable? = null)
    @Suppress("UNCHECKED_CAST")
    val dumpTask = task<DumpTask<T>>("$path/dumpTask", DumpTask.serializer(info.serialization.serializer)) {
        val auth = it.onBehalfOf?.real()
        val out = File.createTempFile("out", when(it.request.type) {
            DumpType.CSV -> "csv.zip"
            DumpType.JSON_LINES -> "jsonl.zip"
        })
        // Admin already required, no need for further limiting
        val flow = if(auth == null)
            (info as ModelInfo<HasId<*>?, T, ID>).collection(null).find(it.request.condition)
        else if(info.authOptions.accepts(auth))
            (info as ModelInfo<HasId<*>?, T, ID>).collection(AuthAccessor(auth as RequestAuth<HasId<*>>, null)).find(it.request.condition)
        else
            info.collection().find(it.request.condition)

        fun writer() = out.outputStream().buffered().let(::ZipOutputStream).let { z ->
            z.putNextEntry(ZipEntry("${info.collectionName.kabobCase()}.${it.request.type.ext()}"))
            z.bufferedWriter()
        }

        when(it.request.type) {
            DumpType.CSV -> {
                writer().use { out2 ->
                    val emit = Serialization.csv.beginEncodingToAppendable(info.serialization.serializer, out2)
                    flow.collect {
                        emit(it)
                    }
                }
            }
            DumpType.JSON_LINES -> {
                writer().use { out2 ->
                    flow.collect {
                        out2.appendLine(Serialization.json.encodeToString(info.serialization.serializer, it))
                    }
                }
            }
        }
        it.file.fileObject.put(HttpContent.file(out, when(it.request.type) {
            DumpType.CSV -> ContentType.Application.Zip
            DumpType.JSON_LINES -> ContentType.Application.Zip
        }))
        it.request.email?.let { address ->
            email().send(
                Email(
                    subject = "${info.collectionName} Dump from ${generalSettings().projectName}",
                    to = listOf(EmailLabeledValue(address)),
                    plainText = "Your dump can be found at ${it.file.fileObject.signedUrl}.  Link will expire shortly.",
                    html = "Your dump can be found <a href='${it.file.fileObject.signedUrl}'>here</a>.  Link will expire shortly."
                )
            )
        }
    }

    val cleanDumps = schedule("$path/cleanDumps", 10.minutes) {
        file().root.resolve("temp-files-dump").list()?.forEach {
            if(it.head()!!.lastModified < now().minus(1.days)) {
                it.delete()
            }
        }
    }
}

private fun File.toZip(dest: File) {
    ZipOutputStream(BufferedOutputStream(FileOutputStream(dest))).use { output ->
        FileInputStream(this).use { input ->
            BufferedInputStream(input).use { origin ->
                val entry = ZipEntry(this.name)
                output.putNextEntry(entry)
                origin.copyTo(output, 1024)
            }
        }
    }
}
