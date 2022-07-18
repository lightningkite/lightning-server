package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.http.HttpContent
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.io.InputStream

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
            client.head("$url?$queryParams").status.isSuccess()
        }
    }
    val url: String
    val signedUrl: String
    fun uploadUrl(timeoutMilliseconds: Long): String
}

