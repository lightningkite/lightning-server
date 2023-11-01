package com.lightningkite.lightningserver.files

import com.lightningkite.Blob
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import kotlin.time.Duration

/**
 * An abstraction that allows FileSystem implementations to access and manipulate the underlying files.
 */
interface FileObject {
    fun resolve(path: String): FileObject
    val parent: FileObject?
    val name: String
    suspend fun list(): List<FileObject>?
    suspend fun head(): FileInfo?
    suspend fun put(content: Blob)
    suspend fun get(): Blob?
    suspend fun delete()
    fun checkSignature(queryParams: String): Boolean

    val url: String
    val signedUrl: String
    fun uploadUrl(timeout: Duration): String
}

