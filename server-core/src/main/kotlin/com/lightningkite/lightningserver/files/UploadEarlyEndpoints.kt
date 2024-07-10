//package com.lightningkite.lightningserver.files
//
//import com.lightningkite.lightningdb.*
//import com.lightningkite.lightningserver.auth.noAuth
//import com.lightningkite.lightningserver.core.ServerPath
//import com.lightningkite.lightningserver.core.ServerPathGroup
//import com.lightningkite.lightningserver.encryption.*
//import com.lightningkite.lightningserver.exceptions.NotFoundException
//import com.lightningkite.lightningserver.http.HttpContent
//import com.lightningkite.lightningserver.http.get
//import com.lightningkite.lightningserver.schedule.schedule
//import com.lightningkite.lightningserver.typed.api
//import com.lightningkite.lightningserver.typed.arg
//import com.lightningkite.lightningserver.typed.get
//import com.lightningkite.lightningserver.typed.path1
//import kotlinx.coroutines.DelicateCoroutinesApi
//import kotlinx.coroutines.GlobalScope
//import kotlinx.coroutines.launch
//import com.lightningkite.now
//import com.lightningkite.uuid
//import io.ktor.http.*
//import kotlin.time.Duration
//import org.jetbrains.annotations.TestOnly
//import kotlin.time.Duration.Companion.days
//
//class UploadEarlyEndpoints(
//    path: ServerPath,
//    val files: () -> FileSystem,
//    val finalFilePath: String = "uploaded",
//    val database: () -> Database,
//    val signer: () -> SecureHasher = secretBasis.hasher("upload-early-2"),
//    val jailFilePath: String = "upload-jail",
//    val jailFiles: () -> FileSystem = files,
//    val expiration: Duration = 1.days,
//    val check: suspend (suspend (HttpContent) -> Unit) -> Unit = { },
//    val copyAndCheck: suspend (FileObject, FileObject) -> Unit = { src, dst ->
//        try {
//            src.copyTo(dst)
//            check { dst.get()!! }
//        } catch(e: Exception) {
//            dst.delete()
//            throw e
//        }
//    },
//) : ServerPathGroup(path) {
//
//    companion object {
//        var default: UploadEarlyEndpoints? = null
//    }
//
//    init {
//        prepareModels()
//        ExternalServerFileSerializer.fileValidators += this::validateFile
//        ExternalServerFileSerializer.uploadFile = {
//            check { it }
//            val d = files().root.resolveRandom("uploaded", "file")
//            d.put(it)
//            d
//        }
//        default = this
//    }
//
//    val start = path("start").get.api(
//        authOptions = noAuth,
//        summary = "Start File Upload",
//        description = "Start uploading a file to make a request later.  Times out in around 10 minutes.",
//        errorCases = listOf(),
//        implementation = { _: Unit ->
//            val id = uuid()
//            val newFile = jailFiles().root.resolve(jailFilePath).resolve("${id}.temp")
//            val newItem = UploadForNextRequest(
//                _id = id,
//                expires = now().plus(expiration),
//                file = ServerFile(newFile.url)
//            )
//            database().collection<UploadForNextRequest>().insertOne(newItem)
//            UploadInformation2(
//                id = id,
//                uploadUrl = newFile.uploadUrl(expiration),
//            )
//        }
//    )
//
//    val finish = path("finish").arg("id", UUIDSerializer).get.api(
//        authOptions = noAuth,
//        summary = "Finish file upload",
//        description = "Finish uploading a file to make a request later, scanning the file for issues.",
//        errorCases = listOf(),
//        implementation = { _: Unit ->
//            val upload = database().collection<UploadForNextRequest>().get(path1) ?: throw NotFoundException()
//            val jailed = upload.file.fileObject
//            val final = files().root.resolve(finalFilePath).resolve("${upload._id}.file")
//            copyAndCheck(jailed, final)
//            jailed.delete()
//            ServerFile(signUsageUrl(final.url))
//        }
//    )
//
//    val cleanupSchedule = schedule("cleanupUploads", 1.days) {
//        database().collection<UploadForNextRequest>().deleteMany(condition { it.expires lt now() }).forEach {
//            try {
//                it.file.fileObject.delete()
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }
//        }
//    }
//
//    @OptIn(DelicateCoroutinesApi::class)
//    fun validateFile(url: String, params: Map<String, String>): Boolean {
//        val token = params["token"] ?: return false
//        val exp = params["useUntil"]?.toLongOrNull() ?: return false
//        if (now().toEpochMilliseconds() > exp) return false
//        val file = ServerFile(url)
//        if (!verifyUsageUrl(url, exp, token)) return false
//        GlobalScope.launch {
//            database().collection<UploadForNextRequest>()
//                .deleteMany(condition { it.file eq ServerFile(url) })
//        }
//        return true
//    }
//
//    @TestOnly
//    internal fun signUsageUrl(url: String): String {
//        return url.plus("?useUntil=${now().plus(expiration).toEpochMilliseconds()}").let {
//            it + "&token=" + signer().signUrl(it)
//        }
//    }
//
//    @TestOnly
//    internal fun verifyUsageUrl(url: String): Boolean {
//        val params = url.substringAfter('?').split('&')
//            .associate { it.substringBefore('=') to it.substringAfter('=').decodeURLQueryComponent() }
//        return verifyUsageUrl(url.substringBefore('?'), params["useUntil"]!!.toLong(), params["token"]!!)
//    }
//
//    @TestOnly
//    internal fun verifyUsageUrl(url: String, exp: Long, token: String): Boolean {
//        return signer().verifyUrl(url.substringBefore('?') + "?useUntil=$exp", token)
//    }
//
//}