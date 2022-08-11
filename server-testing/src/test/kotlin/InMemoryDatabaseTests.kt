package com.lightningkite.lightningdb.test

import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.auth.OauthProviderCredentials
import com.lightningkite.lightningserver.cache.CacheInterface
import com.lightningkite.lightningserver.cache.CacheTest
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.db.InMemoryDatabase
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.files.FileSystem
import com.lightningkite.lightningserver.files.FileSystemTests
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.files.LocalFileSystem
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.test
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.setting
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Duration
import kotlin.test.assertEquals

class RamAggregationsTest: AggregationsTest() {
    override val database: Database = InMemoryDatabase()
}
class RamConditionTests: ConditionTests() {
    override val database: Database = InMemoryDatabase()
}
class RamModificationTests: ModificationTests() {
    override val database: Database = InMemoryDatabase()
}
class RamSortTest: SortTest() {
    override val database: Database = InMemoryDatabase()
}
class RamMetaTest: MetaTest() {
    override val database: Database = InMemoryDatabase()
}

class LocalCacheTest: CacheTest() {
    override val cache: CacheInterface = LocalCache
}

class LocalFilesTest: FileSystemTests() {
    init {
        TestSettings
    }
    override val system: LocalFileSystem = LocalFileSystem(File("build/local-files-test"), "hosted-files", JwtSigner())
    override fun testSignedUrlAccess() {
        runBlocking {
            val testFile = system.root.resolve("test.txt")
            val message = "Hello world!"
            testFile.write(HttpContent.Text(message, ContentType.Text.Plain))
            val signed = testFile.signedUrl
            assert(signed.startsWith(testFile.url))
            println("testFile.signedUrl: ${signed}")
            val response = system.fetch.test(
                wildcard = signed.substringAfterLast("hosted-files").substringBefore('?'),
                queryParameters = signed.substringAfter('?').split('&').map { it.substringBefore('=') to it.substringAfter('=') }
            )
            println(response)
            assertEquals(HttpStatus.OK, response.status)
            assertEquals(message, response.body?.text())
        }
    }
    override fun testSignedUpload() {
        runBlocking {
            val testFile = system.root.resolve("test.txt")
            val message = "Hello world!"
            val signed = testFile.uploadUrl(Duration.ofHours(1))
            val response = system.upload.test(
                wildcard = signed.substringAfterLast("hosted-files").substringBefore('?'),
                queryParameters = signed.substringAfter('?').split('&').map { it.substringBefore('=') to it.substringAfter('=') },
                body = HttpContent.Text(message, ContentType.Text.Plain)
            )
            println(response)
            assertEquals(HttpStatus.NoContent, response.status)
        }
    }
}

object TestSettings {
    val database = setting("database", DatabaseSettings())
    val email = setting("email", EmailSettings())
    val jwtSigner = setting("jwt", JwtSigner())
    val files = setting("files", FilesSettings())
    val oauthGoogle = setting<OauthProviderCredentials?>("oauth-google", null)
    val oauthApple = setting<OauthProviderCredentials?>("oauth-apple", null)
    val oauthGithub = setting<OauthProviderCredentials?>("oauth-github", null)

    init {
        Settings.populateDefaults(mapOf(
            "database" to DatabaseSettings("ram")
        ))
    }
}