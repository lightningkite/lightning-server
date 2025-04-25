package com.lightningkite.lightningserver.files

import com.lightningkite.default
import com.lightningkite.lightningserver.db.Condition
import com.lightningkite.lightningserver.db.all
import com.lightningkite.lightningserver.db.collection
import com.lightningkite.now
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import com.lightningkite.lightningserver.TestSettings.database
import com.lightningkite.lightningserver.TestSettings.files
import com.lightningkite.lightningserver.TestSettings.path
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.filescanner.FileScanner
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.typed.test
import io.ktor.http.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class UploadEarlyEndpointTest {
    val unSafeUploader = UploadEarlyEndpoint(
        path = path("upload-early"),
        files = files,
        database = database,
        expiration = 1.minutes,
        fileScanner = {
            listOf(object :
                FileScanner {
                override fun requires(claimedType: ContentType): FileScanner.Requires = FileScanner.Requires.Whole
                override fun scan(claimedType: ContentType, data: InputStream) {
                    println("Fake scanned ${data.readBytes().size} bytes")
                }
            })
        }
    )

    val safeUploader = UploadEarlyEndpoint(
        path = path("upload-early"),
        files = files,
        database = database,
        expiration = 1.minutes
    )


    suspend fun uploadFile(uploadUrl: String) {

        val match = Http.matcher.match(
            uploadUrl.removePrefix(generalSettings().publicUrl).substringBefore('?'),
            HttpMethod.PUT
        )!!
        Http.endpoints[match.endpoint]!!.invoke(
            HttpRequest(
                match.endpoint,
                match.parts,
                match.wildcard,
                queryParameters = uploadUrl.substringAfter('?').split('&')
                    .map { it.substringBefore('=') to it.substringAfter('=') },
                body = HttpContent.Text("Test", ContentType.Text.Plain)
            )
        ).let { assert(it.status.success) }
    }

    @BeforeTest
    fun clearUploadTemps(): Unit = runBlocking {
        database().collection<UploadForNextRequest>().deleteManyIgnoringOld(Condition.Always)
    }

    @AfterTest
    fun resetClock() {
        Clock.default = Clock.System
    }

    @Test
    fun testVerifyUrlNoParams(){

        val url = "https://test.com/test.jpg"

        assertFailsWith<IllegalArgumentException> { safeUploader.verifyUrl(url) }
        assertFailsWith<IllegalArgumentException> { safeUploader.verifyUrl(url + "?until=${now().toEpochMilliseconds()}") }
        assertFailsWith<IllegalArgumentException> { safeUploader.verifyUrl(url + "?token=SomeBadTOken") }

    }

    @Test
    fun testVerifyUrlExp() {
        val signedUrl = safeUploader.signUrl("https://test.com/test.jpg")
        var now = now()
        Clock.default = object : Clock {
            override fun now(): Instant = now
        }

        assertTrue(safeUploader.verifyUrl(signedUrl))

        now = now.plus(59.seconds)
        assertTrue(safeUploader.verifyUrl(signedUrl))

        now = now.plus(60.seconds)
        assertFalse(safeUploader.verifyUrl(signedUrl))

        now = now.plus(61.seconds)
        assertFalse(safeUploader.verifyUrl(signedUrl))
    }

    @Test
    fun testVerifyBadUrl() {
        val signedUrl = safeUploader.signUrl("https://test.com/test.jpg")
        assertFalse(safeUploader.verifyUrl("Bad$signedUrl"))
    }

    @Test
    fun testVerifyBadSignature() {
        val signedUrl = safeUploader.signUrl("https://test.com/test.jpg")
        val url = signedUrl.substringBefore('?')
        val params = signedUrl.substringAfter('?')
            .split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=').decodeURLQueryComponent() }
        assertFalse(safeUploader.verifyUrl("$url?useUntil=${params["useUntil"]}&token=0${params["token"]}"))
    }

    @Test
    fun testSafeResolverResolveWithSignature(): Unit = runBlocking {
        val resolver = safeUploader.safeResolver
        val unverified = safeUploader.endpoint.test(null, Unit)
        val result = resolver.resolveWithSignature(unverified.futureCallToken)
        assertEquals(unverified.uploadUrl.substringBefore('?'), result.url)
        assertFailsWith<BadRequestException> {
            val post = unverified.futureCallToken.removePrefix(resolver.prefix)
            val badToken = "Bad" + resolver.prefix + post
            resolver.resolveWithSignature(badToken)
        }
        assertFailsWith<BadRequestException> {
            val post = unverified.futureCallToken.removePrefix(resolver.prefix)
            val badToken = resolver.prefix + "bad" + post
            resolver.resolveWithSignature(badToken)
        }
        assertFailsWith<BadRequestException> {
            val post = unverified.futureCallToken.removePrefix(resolver.prefix)
            val badToken = "badPRefix:///" + post
            resolver.resolveWithSignature(badToken)
        }
    }

    @Test
    fun testSafeResolverResolveClearsUploadEarly(): Unit = runBlocking {
        val nextRequestCollection = database().collection<UploadForNextRequest>()
        assertEquals(0, nextRequestCollection.count(Condition.Always))
        val unverified1 = safeUploader.endpoint.test(null, Unit)
        val unverified2 = safeUploader.endpoint.test(null, Unit)
        assertEquals(2, nextRequestCollection.count(Condition.Always))
        safeUploader.safeResolver.resolve(unverified1.futureCallToken)
        assertEquals(1, nextRequestCollection.count(Condition.Always))
        val leftOver = nextRequestCollection.all().first()
        assertEquals(unverified2.uploadUrl.substringBefore('?'), leftOver.file.location)

    }


    @Test
    fun testSafeResolverResolveWithSignatureClearsUploadEarly(): Unit = runBlocking {
        val nextRequestCollection = database().collection<UploadForNextRequest>()
        assertEquals(0, nextRequestCollection.count(Condition.Always))
        val unverified1 = safeUploader.endpoint.test(null, Unit)
        val unverified2 = safeUploader.endpoint.test(null, Unit)
        assertEquals(2, nextRequestCollection.count(Condition.Always))
        safeUploader.safeResolver.resolveWithSignature(unverified1.futureCallToken)
        assertEquals(1, nextRequestCollection.count(Condition.Always))
        val leftOver = nextRequestCollection.all().first()
        assertEquals(unverified2.uploadUrl.substringBefore('?'), leftOver.file.location)

    }

    @Test
    fun testSafeUploaderCleanDoesNotDeleteResolved(): Unit = runBlocking {
        val nextRequestCollection = database().collection<UploadForNextRequest>()
        assertEquals(0, nextRequestCollection.count(Condition.Always))
        val unverified1 = safeUploader.endpoint.test(null, Unit)

        uploadFile(unverified1.uploadUrl)

        assertEquals(1, nextRequestCollection.count(Condition.Always))
        safeUploader.safeResolver.resolveWithSignature(unverified1.futureCallToken)
        assertEquals(0, nextRequestCollection.count(Condition.Always))
        assertTrue(ServerFile(unverified1.uploadUrl).fileObject.exists())
        Clock.default = object : Clock {
            override fun now(): Instant {
                return Clock.System.now() + 1.days
            }
        }
        safeUploader.cleanupSchedule.handler()
        assertTrue(ServerFile(unverified1.uploadUrl).fileObject.exists())
    }

    @Test
    fun testSafeUploaderCleanDeletesUnresolved(): Unit = runBlocking {
        val nextRequestCollection = database().collection<UploadForNextRequest>()
        assertEquals(0, nextRequestCollection.count(Condition.Always))
        val unverified1 = safeUploader.endpoint.test(null, Unit)
        assertEquals(1, nextRequestCollection.count(Condition.Always))

        uploadFile(unverified1.uploadUrl)
        assertEquals(1, nextRequestCollection.count(Condition.Always))

        assertTrue(ServerFile(unverified1.uploadUrl).fileObject.exists())
        Clock.default = object : Clock {
            override fun now(): Instant {
                return Clock.System.now() + 1.days
            }
        }
        safeUploader.cleanupSchedule.handler()
        assertFalse(ServerFile(unverified1.uploadUrl).fileObject.exists())
        assertEquals(0, nextRequestCollection.count(Condition.Always))

    }


    @Test
    fun testUnSafeUploaderCleanDeletesResolvedJailFile(): Unit = runBlocking {
        val unverified = unSafeUploader.endpoint.test(null, Unit)
        uploadFile(unverified.uploadUrl)

        unSafeUploader.unsafeResolver.resolve(unverified.futureCallToken)

        assertTrue(ServerFile(unverified.uploadUrl).fileObject.exists())
        Clock.default = object : Clock {
            override fun now(): Instant {
                return Clock.System.now() + 1.days
            }
        }
        unSafeUploader.cleanupSchedule.handler()
        assertFalse(ServerFile(unverified.uploadUrl).fileObject.exists())
    }

    @Test
    fun testUnSafeUploaderCleanDeletesUnResolvedJailFile(): Unit = runBlocking {
        val unverified = unSafeUploader.endpoint.test(null, Unit)

        uploadFile(unverified.uploadUrl)

        assertTrue(ServerFile(unverified.uploadUrl).fileObject.exists())
        Clock.default = object : Clock {
            override fun now(): Instant {
                return Clock.System.now() + 1.days
            }
        }
        unSafeUploader.cleanupSchedule.handler()
        assertFalse(ServerFile(unverified.uploadUrl).fileObject.exists())
    }

    @Test
    fun testUnSafeUploaderCleanDoesntDeletesResolvedFile(): Unit = runBlocking {
        val unverified = unSafeUploader.endpoint.test(null, Unit)

        uploadFile(unverified.uploadUrl)

        val result = unSafeUploader.unsafeResolver.resolve(unverified.futureCallToken)
        assertTrue(result.exists())

        assertTrue(ServerFile(unverified.uploadUrl).fileObject.exists())
        Clock.default = object : Clock {
            override fun now(): Instant {
                return Clock.System.now() + 1.days
            }
        }
        unSafeUploader.cleanupSchedule.handler()
        assertFalse(ServerFile(unverified.uploadUrl).fileObject.exists())
        assertTrue(result.exists())
    }


    @Test
    fun testSafeResolverResolve(): Unit = runBlocking {
        val resolver = safeUploader.safeResolver
        val unverified = safeUploader.endpoint.test(null, Unit)
        val result = resolver.resolve(unverified.futureCallToken)
        assertEquals(unverified.uploadUrl.substringBefore('?'), result.url)
    }

    @Test
    fun testUnSafeResolverResolveWithSignature(): Unit = runBlocking {
        val resolver = unSafeUploader.unsafeResolver
        val unverified = unSafeUploader.endpoint.test(null, Unit)

        uploadFile(unverified.uploadUrl)

        val result = resolver.resolveWithSignature(unverified.futureCallToken)
        assertTrue(unverified.uploadUrl.contains(unSafeUploader.jailFilePath))
        assertFalse(result.url.contains(unSafeUploader.jailFilePath))
        assertTrue(result.url.contains(unSafeUploader.filePath))
        assertFailsWith<BadRequestException> {
            val post = unverified.futureCallToken.removePrefix(resolver.prefix)
            val badToken = resolver.prefix + "bad" + post
            unSafeUploader.safeResolver.resolveWithSignature(badToken)
        }
        assertFailsWith<BadRequestException> {
            val post = unverified.futureCallToken.removePrefix(resolver.prefix)
            val badToken = "badPRefix:///" + post
            unSafeUploader.safeResolver.resolveWithSignature(badToken)
        }
    }

    @Test
    fun testUnSafeResolverResolve(): Unit = runBlocking {
        val resolver = unSafeUploader.unsafeResolver
        val unverified = unSafeUploader.endpoint.test(null, Unit)

        uploadFile(unverified.uploadUrl)

        val result = resolver.resolve(unverified.futureCallToken)
        assertTrue(unverified.uploadUrl.contains(unSafeUploader.jailFilePath))
        assertFalse(result.url.contains(unSafeUploader.jailFilePath))
        assertTrue(result.url.contains(unSafeUploader.filePath))
    }
}