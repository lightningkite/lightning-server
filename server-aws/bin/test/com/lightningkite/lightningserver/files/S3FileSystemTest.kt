package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import software.amazon.awssdk.regions.Region
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContains

class S3FileSystemTest: FileSystemTests() {
    override val system: FileSystem? by lazy {
        val credentials = File("local/test-credentials.txt")
        if(!credentials.exists()) {
            println("No credentials to test with at ${credentials.absolutePath}")
            return@lazy null
        }
        S3FileSystem
        FilesSettings(credentials.readText(), signedUrlExpiration = Duration.ofDays(1))()
    }
}
