//package com.lightningkite.lightningserver.files
//
//import com.lightningkite.lightningserver.auth.AuthRequirement
//import com.lightningkite.lightningserver.auth.noAuth
//import com.lightningkite.lightningserver.definition.ServerDefinition
//import com.lightningkite.lightningserver.definition.builder.ServerBuilder
//import com.lightningkite.services.database.Database
//import com.lightningkite.services.files.FileScanner
//import com.lightningkite.services.files.PublicFileSystem
//import kotlin.time.Duration
//import kotlin.time.Duration.Companion.days
//
//public class UploadEarlyEndpoint(
//    public val files: () -> PublicFileSystem,
//    public val database: () -> Database,
//    public val fileScanner: () -> List<FileScanner> = { listOf() },
//    public val jailFilePath: String = "upload-jail",
//    public val filePath: String = "uploaded",
//    public val expiration: Duration = 1.days,
//    public val authOptions: AuthRequirement<*> = noAuth,
//) : ServerBuilder() {
//
//    val endpoint = get.api(
//        authOptions = authOptions,
//        summary = "Upload File for Request",
//        description = "Upload a file to make a request later.  Times out in around 10 minutes.",
//        errorCases = listOf(),
//        implementation = { _: Unit ->
//            val id = UUID.random()
//            if (fileScanner().isEmpty()) {
//                val newFile = files().root.resolve(filePath).resolve("$id.file")
//                val newItem = UploadForNextRequest(
//                    expires = now().plus(expiration),
//                    file = ServerFile(newFile.url)
//                )
//                database().collection<UploadForNextRequest>().insertOne(newItem)
//                UploadInformation(
//                    uploadUrl = newFile.uploadUrl(expiration),
//                    futureCallToken = signUrl(safeResolver.prefix + id.toString())
//                )
//            } else {
//                val newFile = files().root.resolve(jailFilePath).resolve("$id.file")
//                val newItem = UploadForNextRequest(
//                    expires = now().plus(expiration),
//                    file = ServerFile(newFile.url)
//                )
//                database().collection<UploadForNextRequest>().insertOne(newItem)
//                UploadInformation(
//                    uploadUrl = newFile.uploadUrl(expiration),
//                    futureCallToken = signUrl(unsafeResolver.prefix + id.toString())
//                )
//            }
//        }
//    )
//
//    val verify = post("verify").api(
//        authOptions = authOptions,
//        summary = "Verify uploaded file",
//        description = "Checks out a file and moves it out of jail if it's safe.  Makes for significantly faster subsequent requests.",
//        errorCases = listOf(),
//        implementation = { url: String ->
//            if (url.startsWith(safeResolver.prefix)) return@api url
//            if (!url.startsWith(unsafeResolver.prefix)) throw BadRequestException("URL expected to start with ${unsafeResolver.prefix}")
//            if(!verifyUrl(url)) throw BadRequestException("Failed to verify: $url")
//            val id = url.substringAfter(unsafeResolver.prefix).substringBefore('?')
//            val originalFo = files().root.resolve(jailFilePath).resolve("$id.file")
//            val safeFo = files().root.resolve(filePath).resolve("$id.file")
//            runBlocking {
//                fileScanner().copyAndScan(originalFo, safeFo)
//                val newItem = UploadForNextRequest(
//                    expires = now().plus(expiration),
//                    file = ServerFile(safeFo.url)
//                )
//                database().collection<UploadForNextRequest>().insertOne(newItem)
//            }
//            signUrl(safeResolver.prefix + id)
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
//    @TestOnly
//    internal fun signUrl(url: String): String {
//        return url.plus("?useUntil=${now().plus(expiration).toEpochMilliseconds()}").let {
//            it + "&token=" + signer().signUrl(it)
//        }
//    }
//
//    @TestOnly
//    internal fun verifyUrl(url: String): Boolean {
//        val params = url.substringAfter('?')
//            .split('&')
//            .associate { it.substringBefore('=') to it.substringAfter('=').decodeURLQueryComponent() }
//        return verifyUrl(
//            url.substringBefore('?'),
//            params["useUntil"]?.toLong() ?: throw IllegalArgumentException("Parameter 'useUntil' is missing in '$url'"),
//            params["token"] ?: throw IllegalArgumentException("Parameter 'token' is missing in '$url'")
//        )
//    }
//
//    @TestOnly
//    internal fun verifyUrl(url: String, exp: Long, token: String): Boolean {
//        return (now() < Instant.fromEpochMilliseconds(exp)) && signer().verifyUrl(url.substringBefore('?') + "?useUntil=$exp", token)
//    }
//
//}