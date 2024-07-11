package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.bytes.hexToByteArray
import com.lightningkite.lightningserver.bytes.toHexString
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.logging.LoggingSettings
import com.lightningkite.now
import com.lightningkite.uuid
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import software.amazon.awssdk.regions.Region
import java.io.File
import java.math.BigInteger
import java.net.URLDecoder
import java.security.MessageDigest
import kotlin.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContains
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class S3FileSystemTest : FileSystemTests() {
    override val system: FileSystem? by lazy {
        val credentials = File("local/test-credentials.txt")
        if (!credentials.exists()) {
            println("No credentials to test with at ${credentials.absolutePath}")
            return@lazy null
        }
        S3FileSystem
        FilesSettings(credentials.readText(), signedUrlExpiration = 1.days)()
    }

    @Test
    fun signPerformance() {
        val system = system ?: return

        var endAt = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < endAt)
            system.root.resolve("${uuid()}.txt").signedUrl

        var count = 0
        endAt = System.currentTimeMillis() + 1000
        var last = ""
        while (System.currentTimeMillis() < endAt) {
            count++
            last = system.root.resolve("${uuid()}.txt").signedUrl
        }

        println("Count: $count")
        println("Last: $last")
        println("Testable: ${system.root.resolve("test.txt").signedUrl}")
    }
}
