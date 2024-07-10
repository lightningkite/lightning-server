package com.lightningkite.lightningserver.files

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.filescanner.FileScanner
import com.lightningkite.lightningserver.filescanner.copyAndScan
import com.lightningkite.lightningserver.filescanner.scan
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.typed.api
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import com.lightningkite.now
import com.lightningkite.uuid
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
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
    val expiration: Duration = 1.days
) : ServerPathGroup(path), FileSystem.SpecialResolver {

    companion object {
        var default: UploadEarlyEndpoint? = null
    }
    override val prefix: String = "future://$path/"
    override fun resolve(url: String): FileObject {
        val post = url.substringAfter(prefix)
        verifyUrl(post)
        val id = post.substringBefore('?')
        val originalFo = files().root.resolve(jailFilePath).resolve("$id.file")
        val safeFo = files().root.resolve(filePath).resolve("$id.file")
        runBlocking { fileScanner().copyAndScan(originalFo, safeFo) }
        return safeFo
    }
    init {
        prepareModels()
        FileSystem.register(this)
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
        authOptions = noAuth,
        summary = "Upload File for Request",
        description = "Upload a file to make a request later.  Times out in around 10 minutes.",
        errorCases = listOf(),
        implementation = { _: Unit ->
            val id = uuid()
            val newFile = files().root.resolve(jailFilePath).resolve("$id.file")
            val newItem = UploadForNextRequest(
                expires = now().plus(expiration),
                file = ServerFile(newFile.url)
            )
            database().collection<UploadForNextRequest>().insertOne(newItem)
            UploadInformation(
                uploadUrl = newFile.uploadUrl(expiration),
                futureCallToken = signUrl(prefix + id.toString())
            )
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
        val params = url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=').decodeURLQueryComponent() }
        return verifyUrl(url.substringBefore('?'), params["useUntil"]?.toLong() ?: throw IllegalArgumentException("Parameter 'useUntil' is missing in '$url'"), params["token"] ?: throw IllegalArgumentException("Parameter 'token' is missing in '$url'"))
    }
    @TestOnly
    internal fun verifyUrl(url: String, exp: Long, token: String): Boolean {
        return signer().verifyUrl(url.substringBefore('?') + "?useUntil=$exp", token)
    }

}