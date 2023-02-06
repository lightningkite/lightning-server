package com.lightningkite.lightningserver.files

import io.ktor.client.request.*
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SftpTest: FileSystemTests() {
    init { Sftp }

    override val system: FileSystem? get() {
        return File("./local/url.txt").also{
            if(!it.exists()) return null
        }.readText().let { FilesSettings(storageUrl = it) }.invoke()
    }
    override fun testSignedUrlAccess() = Unit
    override fun uploadHeaders(builder: HttpRequestBuilder) = Unit
    override fun testSignedUpload() = Unit
}