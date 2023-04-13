package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import io.ktor.client.request.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
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

class SftpTest2() {
    init { Sftp }
    @Test fun test(){
        val fs = File("./local/url2.txt").also{
            if(!it.exists()) return
        }.readText().let { FilesSettings(storageUrl = it) }.invoke()
        runBlocking {
            while(true) {
                val items = fs.root.list() ?: listOf()
                if(items.isNotEmpty()) println(items)
                val oldItem = items.find { !it.url.contains("-ais") }
                if (oldItem != null) {
                    println("!! OLD ITEM FOUND !!")
                    println(oldItem)
                    oldItem.local(File("local/sample-data").also { it.mkdirs() }.resolve(oldItem.url.substringAfterLast('/')))
                    return@runBlocking
                }
                delay(60_000L * 5)
            }
        }
    }
}