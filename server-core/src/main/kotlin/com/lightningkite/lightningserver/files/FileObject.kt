package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.http.HttpContent
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.time.Duration

interface FileObject {
    fun resolve(path: String): FileObject
    val parent: FileObject?
    suspend fun list(): List<FileObject>?
    suspend fun info(): FileInfo?
    suspend fun write(content: HttpContent)
    suspend fun read(): InputStream
    suspend fun delete()
    fun checkSignature(queryParams: String): Boolean {
        return runBlocking {
            val response = client.get("$url?$queryParams") {
                header("Range", "0-0")
            }
            response.status.isSuccess()
        }
    }
    val url: String
    val signedUrl: String
    fun uploadUrl(timeout: Duration): String

    data class FileObjectMultipartUpload(val fileObject: FileObject, val key: String, val id: String) {
        suspend fun part(partNumber: Int): String = fileObject.uploadPartUrl(key, id, partNumber)
        suspend fun finish(): FileObject {
            fileObject.finishMultipart(key, id)
            return fileObject
        }
    }
    suspend fun startMultipart(): FileObjectMultipartUpload
    suspend fun uploadPartUrl(multipartKey: String, multipartId: String, partNumber: Int): String
    suspend fun finishMultipart(multipartKey: String, multipartId: String)
}

