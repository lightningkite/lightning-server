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
import com.lightningkite.lightningserver.routes.docName
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
import java.io.File
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

open class ModelDumpEndpoints<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    path: ServerPath,
    val info: ModelInfo<*, T, ID>,
    val authOptions: AuthOptions<USER>,
    val file: ()->FileSystem,
    val email: ()->EmailClient,
) : ServerPathGroup(path) {

    val collectionName get() = info.collectionName

    val dump = path("dump").post.api(
        authOptions = authOptions,
        inputType = DumpRequest.serializer(info.serialization.serializer),
        outputType = String.serializer(),
        summary = "Dump",
        description = "Get a dump file of all the models.",
        errorCases = listOf(),
        implementation = { input: DumpRequest<T> ->
            val file = file().root.resolve("temp-files-dump").resolveRandom("dump", when(input.type) {
                DumpType.CSV -> "csv"
                DumpType.JSON_LINES -> "jsonl"
            })
            dumpTask(DumpTask(input, file.serverFile, authOrNull?.serializable(now().plus(10.minutes))))
            file.signedUrl
        }
    )
    @Serializable data class DumpTask<T>(val request: DumpRequest<T>, val file: ServerFile, val onBehalfOf: RequestAuthSerializable? = null)
    @Suppress("UNCHECKED_CAST")
    val dumpTask = task<DumpTask<T>>("$path/dumpTask", DumpTask.serializer(info.serialization.serializer)) {
        val auth = it.onBehalfOf?.real()
        val out = File.createTempFile("out", when(it.request.type) {
            DumpType.CSV -> "csv"
            DumpType.JSON_LINES -> "jsonl"
        })
        // Admin already required, no need for further limiting
        val flow = if(auth == null)
            (info as ModelInfo<HasId<*>?, T, ID>).collection(null).find(it.request.condition)
        else if(info.authOptions.accepts(auth))
            (info as ModelInfo<HasId<*>?, T, ID>).collection(AuthAccessor(auth as RequestAuth<HasId<*>>, null)).find(it.request.condition)
        else
            info.collection().find(it.request.condition)

        when(it.request.type) {
            DumpType.CSV -> {
                out.writer().use { out ->
                    val emit = Serialization.csv.beginEncodingToAppendable(info.serialization.serializer, out)
                    flow.collect {
                        emit(it)
                    }
                }
            }
            DumpType.JSON_LINES -> {
                out.writer().use { out ->
                    flow.collect {
                        out.appendLine(Serialization.json.encodeToString(info.serialization.serializer, it))
                    }
                }
            }
        }
        it.file.fileObject.put(HttpContent.file(out, when(it.request.type) {
            DumpType.CSV -> ContentType.Text.CSV
            DumpType.JSON_LINES -> ContentType.Text.Plain
        }))
        it.request.email?.let { address ->
            email().send(
                Email(
                    subject = "${info.collectionName} Dump from ${generalSettings().projectName}",
                    to = listOf(EmailLabeledValue(address)),
                    plainText = "Your dump can be found at ${it.file.fileObject.signedUrl}",
                    html = "Your dump can be found <a href='${it.file.fileObject.signedUrl}'>here</a>"
                )
            )
        }
    }
}