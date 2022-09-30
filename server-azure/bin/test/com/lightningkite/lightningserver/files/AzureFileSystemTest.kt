package com.lightningkite.lightningserver.files

import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.serialization.Serialization
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Duration
import kotlin.test.assertContains
import com.lightningkite.lightningserver.files.FileSystemTests

class AzureFileSystemTest: FileSystemTests() {
    override val system: FileSystem? by lazy {
        val credentials = File("local/test-credentials.txt")
        if(!credentials.exists()) {
            println("No credentials to test with at ${credentials.absolutePath}")
            return@lazy null
        }
        AzureFileSystem
        FilesSettings(credentials.readText(), signedUrlExpiration = Duration.ofDays(1))()
    }

    override fun uploadHeaders(builder: HttpRequestBuilder) {
        builder.header("x-ms-blob-type", "BlockBlob")
    }
}
