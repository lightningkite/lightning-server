package com.lightningkite.lightningdb.test.com.lightningkite.lightningdb.test

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningdb.test.*
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.encryption.SecureHasherSettings
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.CacheTest
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.db.InMemoryDatabase
import com.lightningkite.lightningserver.files.FileSystemTests
import com.lightningkite.lightningserver.files.LocalFileSystem
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
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
class RamOperationsTests: OperationsTests() {
    override val database: Database = InMemoryDatabase()
}
class RamSortTest: SortTest() {
    override val database: Database = InMemoryDatabase()
}
class RamMetaTest: MetaTest() {
    override val database: Database = InMemoryDatabase()
}

class DelayRamAggregationsTest: AggregationsTest() {
    override val database: Database = InMemoryDatabase().delayed(5L)
}
class DelayRamConditionTests: ConditionTests() {
    override val database: Database = InMemoryDatabase().delayed(5L)
}
class DelayRamModificationTests: ModificationTests() {
    override val database: Database = InMemoryDatabase().delayed(5L)
}
class DelayRamOperationsTests: OperationsTests() {
    override val database: Database = InMemoryDatabase().delayed(5L)
}
class DelayRamSortTest: SortTest() {
    override val database: Database = InMemoryDatabase().delayed(5L)
}
class DelayRamMetaTest: MetaTest() {
    override val database: Database = InMemoryDatabase().delayed(5L)
}

class LocalCacheTest: CacheTest() {
    override val cache: Cache = LocalCache
}

class LocalFilesTest: FileSystemTests() {
    init {
        TestSettings
    }
    override val system: LocalFileSystem = LocalFileSystem(File("build/local-files-test"), "hosted-files", null, SecureHasherSettings()())
    override fun testSignedUrlAccess() {
        runBlocking {
            val testFile = system.root.resolve("test.txt")
            val message = "Hello world!"
            testFile.put(HttpContent.Text(message, ContentType.Text.Plain))
            val signed = testFile.signedUrl
            assert(signed.startsWith(testFile.url))
            println("testFile.signedUrl: ${signed}")
            val response = system.fetch.test(
                wildcard = signed.substringAfterLast("hosted-files").substringBefore('?'),
                queryParameters = signed.substringAfter('?').split('&')
                    .map { it.substringBefore('=') to it.substringAfter('=') }
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

class SecurityTest() {
    init { TestSettings }
    @Test
    fun test() {
        runBlocking {
            val unsecured = TestSettings.database().collection<LargeTestModel>("SecurityTest_test")
            val secured = unsecured.withPermissions(ModelPermissions(
                create = Condition.Always(),
                read = Condition.Always(),
                readMask = mask {
                    always(it.intNullable.maskedTo(null))
                },
                update = Condition.Always(),
                delete = Condition.Always(),
            ))
            unsecured.insert(listOf(
                LargeTestModel(intNullable = 1),
                LargeTestModel(intNullable = 2),
                LargeTestModel(intNullable = 3),
                LargeTestModel(intNullable = 4),
            ))
            val results = secured.find(Condition.Always()).toList()
            assertEquals(4, results.size)
            for(r in results) assertEquals(null, r.intNullable)
        }
    }
}

