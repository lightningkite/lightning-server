package com.lightningkite.lightningserver.files

import io.ktor.client.request.*
import java.io.File

class SftpTest: FileSystemTests() {
    init { Sftp }

    override val system: FileSystem? get() {
        return File("./local/url.txt").also{
            if(!it.exists()) return null
        }.readText().let { FilesSettings(url = it) }.invoke()
    }
    override fun testSignedUrlAccess() = Unit
    override fun uploadHeaders(builder: HttpRequestBuilder) = Unit
    override fun testSignedUpload() = Unit
}
