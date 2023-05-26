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
    val name: String
    suspend fun list(): List<FileObject>?
    @Deprecated("Use head instead", ReplaceWith("head()")) suspend fun info(): FileInfo? = head()
    suspend fun head(): FileInfo?
    @Deprecated("Use put instead", ReplaceWith("put(content)")) suspend fun write(content: HttpContent) = put(content)
    @Deprecated("Use get instead", ReplaceWith("get().stream()")) suspend fun read(): InputStream = get()!!.stream()
    suspend fun put(content: HttpContent)
    suspend fun get(): HttpContent?
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

