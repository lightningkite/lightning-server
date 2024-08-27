package com.lightningkite.lightningserver.files

import io.ktor.client.request.*
import java.io.File
import kotlin.time.Duration.Companion.days

class AzureFileSystemTest: FileSystemTests() {
    override val system: FileSystem? by lazy {
        val credentials = File("local/test-credentials.txt")
        if(!credentials.exists()) {
            println("No credentials to test with at ${credentials.absolutePath}")
            return@lazy null
        }
        AzureFileSystem
        FilesSettings(credentials.readText(), signedUrlExpiration = 1.days)()
    }

    override fun uploadHeaders(builder: HttpRequestBuilder) {
        builder.header("x-ms-blob-type", "BlockBlob")
    }
}
