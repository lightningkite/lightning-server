package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.http.HttpContent
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.time.Duration

/**
 * An abstraction that allows FileSystem implementations to access and manipulate the underlying files.
 */
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
}

