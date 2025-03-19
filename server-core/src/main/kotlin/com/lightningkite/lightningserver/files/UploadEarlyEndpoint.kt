package com.lightningkite.lightningserver.files

import com.lightningkite.UUID
import com.lightningkite.prepareModelsServerCore
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.filescanner.FileScanner
import com.lightningkite.lightningserver.filescanner.copyAndScan
import com.lightningkite.lightningserver.filescanner.scan
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.now
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlin.time.Duration
import org.jetbrains.annotations.TestOnly
import kotlin.time.Duration.Companion.days

class UploadEarlyEndpoint(
    path: ServerPath,
    val files: () -> FileSystem,
    val database: () -> Database,
    val signer: () -> SecureHasher = secretBasis.hasher("upload-early"),
    val fileScanner: () -> List<FileScanner> = { listOf() },
    val jailFilePath: String = "upload-jail",
    val filePath: String = "uploaded",
    val expiration: Duration = 1.days,
    val authOptions: AuthOptions<*> = noAuth,
) : ServerPathGroup(path) {

    companion object {
        var default: UploadEarlyEndpoint? = null
    }

    val unsafeResolver = object : FileSystem.SpecialResolver {
        override val prefix: String = "future://$path/"
        override fun resolve(url: String): FileObject {
            val id = url.substringAfter(prefix).substringBefore('?')
            val originalFo = files().root.resolve(jailFilePath).resolve("$id.file")
            val safeFo = files().root.resolve(filePath).resolve("$id.file")
            runBlocking { fileScanner().copyAndScan(originalFo, safeFo) }
            runBlocking {
                database().collection<UploadForNextRequest>()
                    .deleteManyIgnoringOld(condition { it.file eq ServerFile(safeFo.url) })
            }
            return safeFo
        }

        override fun resolveWithSignature(url: String): FileObject {
            if(!verifyUrl(url)) throw BadRequestException("Failed to verify: $url")
            val id = url.substringAfter(prefix).substringBefore('?')
            val originalFo = files().root.resolve(jailFilePath).resolve("$id.file")
            val safeFo = files().root.resolve(filePath).resolve("$id.file")
            runBlocking { fileScanner().copyAndScan(originalFo, safeFo) }
            runBlocking {
                database().collection<UploadForNextRequest>()
                    .deleteManyIgnoringOld(condition { it.file eq ServerFile(safeFo.url) })
            }
            return safeFo
        }
    }
    val safeResolver = object : FileSystem.SpecialResolver {
        override val prefix: String = "future-safe://$path/"
        override fun resolve(url: String): FileObject {
            val id = url.substringAfter(prefix).substringBefore('?')
            val safeFo = files().root.resolve(filePath).resolve("$id.file")
            runBlocking {
                database().collection<UploadForNextRequest>()
                    .deleteManyIgnoringOld(condition { it.file eq ServerFile(safeFo.url) })
            }
            return safeFo
        }

        override fun resolveWithSignature(url: String): FileObject {
            if(!verifyUrl(url)) throw BadRequestException("Failed to verify: $url")
            val id = url.substringAfter(prefix).substringBefore('?')
            val safeFo = files().root.resolve(filePath).resolve("$id.file")
            runBlocking {
                database().collection<UploadForNextRequest>()
                    .deleteManyIgnoringOld(condition { it.file eq ServerFile(safeFo.url) })
            }
            return safeFo
        }
    }

    init {
        prepareModelsServerCore()
        FileSystem.register(unsafeResolver)
        FileSystem.register(safeResolver)
        FileSystem.default = files
        ExternalServerFileSerializer.uploadFile = {
            fileScanner().scan(it)
            val d = files().root.resolveRandom("uploaded", "file")
            d.put(it)
            d
        }
        default = this
    }

    val endpoint = get.api(
        authOptions = authOptions,
        summary = "Upload File for Request",
        description = "Upload a file to make a request later.  Times out in around 10 minutes.",
        errorCases = listOf(),
        implementation = { _: Unit ->
            val id = UUID.random()
            if (fileScanner().isEmpty()) {
                val newFile = files().root.resolve(filePath).resolve("$id.file")
                val newItem = UploadForNextRequest(
                    expires = now().plus(expiration),
                    file = ServerFile(newFile.url)
                )
                database().collection<UploadForNextRequest>().insertOne(newItem)
                UploadInformation(
                    uploadUrl = newFile.uploadUrl(expiration),
                    futureCallToken = signUrl(safeResolver.prefix + id.toString())
                )
            } else {
                val newFile = files().root.resolve(jailFilePath).resolve("$id.file")
                val newItem = UploadForNextRequest(
                    expires = now().plus(expiration),
                    file = ServerFile(newFile.url)
                )
                database().collection<UploadForNextRequest>().insertOne(newItem)
                UploadInformation(
                    uploadUrl = newFile.uploadUrl(expiration),
                    futureCallToken = signUrl(unsafeResolver.prefix + id.toString())
                )
            }
        }
    )

    val verify = post("verify").api(
        authOptions = authOptions,
        summary = "Verify uploaded file",
        description = "Checks out a file and moves it out of jail if it's safe.  Makes for significantly faster subsequent requests.",
        errorCases = listOf(),
        implementation = { url: String ->
            if (url.startsWith(safeResolver.prefix)) return@api url
            if (!url.startsWith(unsafeResolver.prefix)) throw BadRequestException("URL expected to start with ${unsafeResolver.prefix}")
            if(!verifyUrl(url)) throw BadRequestException("Failed to verify: $url")
            val id = url.substringAfter(unsafeResolver.prefix).substringBefore('?')
            val originalFo = files().root.resolve(jailFilePath).resolve("$id.file")
            val safeFo = files().root.resolve(filePath).resolve("$id.file")
            runBlocking {
                fileScanner().copyAndScan(originalFo, safeFo)
                val newItem = UploadForNextRequest(
                    expires = now().plus(expiration),
                    file = ServerFile(safeFo.url)
                )
                database().collection<UploadForNextRequest>().insertOne(newItem)
            }
            signUrl(safeResolver.prefix + id)
        }
    )

    val cleanupSchedule = schedule("cleanupUploads", 1.days) {
        database().collection<UploadForNextRequest>().deleteMany(condition { it.expires lt now() }).forEach {
            try {
                it.file.fileObject.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @TestOnly
    internal fun signUrl(url: String): String {
        return url.plus("?useUntil=${now().plus(expiration).toEpochMilliseconds()}").let {
            it + "&token=" + signer().signUrl(it)
        }
    }

    @TestOnly
    internal fun verifyUrl(url: String): Boolean {
        val params = url.substringAfter('?')
            .split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=').decodeURLQueryComponent() }
        return verifyUrl(
            url.substringBefore('?'),
            params["useUntil"]?.toLong() ?: throw IllegalArgumentException("Parameter 'useUntil' is missing in '$url'"),
            params["token"] ?: throw IllegalArgumentException("Parameter 'token' is missing in '$url'")
        )
    }

    @TestOnly
    internal fun verifyUrl(url: String, exp: Long, token: String): Boolean {
        return (now() < Instant.fromEpochMilliseconds(exp)) && signer().verifyUrl(url.substringBefore('?') + "?useUntil=$exp", token)
    }

}