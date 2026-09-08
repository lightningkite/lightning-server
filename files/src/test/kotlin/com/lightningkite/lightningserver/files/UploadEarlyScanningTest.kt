package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Database
import com.lightningkite.services.files.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.serializersModuleOf
import kotlin.test.*
import kotlin.uuid.Uuid

/** Per-test control of the scanner mounted in [UploadEarlyScanningTest.Server]. */
private object ScanControl {
    var behavior: suspend (ExternalFile) -> Unit = {}
    val scanned: MutableList<ExternalFile> = mutableListOf()

    fun reset() {
        behavior = {}
        scanned.clear()
    }
}

private class ControlledScanner(override val context: SettingContext) : FileScanner {
    override val name: String = "controlled-scanner"
    override suspend fun scan(file: ExternalFile) {
        ScanControl.scanned.add(file)
        ScanControl.behavior(file)
    }

    override suspend fun healthCheck(): HealthStatus = HealthStatus(HealthStatus.Level.OK)
}

private fun ExternalFileSystem.jailed(key: String): ExternalFile = root.then("upload-jail").then(key)
private fun ExternalFileSystem.promoted(key: String): ExternalFile = root.then("uploaded").then(key)

private fun decode(ser: ExternalServerFileSerializer, token: String): ServerFile =
    Json { serializersModule = serializersModuleOf(ser) }.decodeFromJsonElement(ser, JsonPrimitive(token))

/**
 * Covers the upload flow with scanners configured - the configuration where the jail, scanning, and
 * promotion actually do something.
 */
class UploadEarlyScanningTest {

    object Server : ServerBuilder() {
        val files = setting("files", ExternalFileSystem.Settings())
        val database = setting("database", Database.Settings())
        val uploadEarly = path.path("upload") include UploadEarlyEndpoint(
            files = files,
            database = database,
            fileScanner = Runtime { listOf(ControlledScanner(serverRuntime)) },
        )

        init {
            registerBasicMediaTypeCoders()
        }
    }

    @BeforeTest
    fun setUp(): Unit = ScanControl.reset()

    /** With scanners configured, uploads must land in the jail, not somewhere already trusted. */
    @Test
    fun uploadUrlTargetsTheJail(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val prepare = uploadEarly.endpoint.test(null, Unit)
        assertTrue(
            prepare.uploadUrl.contains("upload-jail"),
            "Upload should be directed at the jail: ${prepare.uploadUrl}"
        )
        assertIs<UploadToken.Unscanned>(uploadEarly.tokens().parseOrNull(prepare.futureCallToken))
    }

    /**
     * The invariant the whole design rests on: a token for an unscanned upload must never resolve to a
     * usable file, so skipping verify fails loudly instead of yielding a reference to unscanned bytes.
     */
    @Test
    fun unscannedTokenIsRejectedOnUse(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val prepare = uploadEarly.endpoint.test(null, Unit)
        val key = uploadEarly.tokens().parseOrNull(prepare.futureCallToken)!!.key
        files().jailed(key).put(TypedData.text("hello", MediaType.Text.Plain))

        assertFailsWith<IllegalArgumentException> {
            decode(uploadEarly.serializer(), prepare.futureCallToken)
        }
    }

    @Test
    fun verifyPromotesAndTheResultIsUsable(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val prepare = uploadEarly.endpoint.test(null, Unit)
        val key = uploadEarly.tokens().parseOrNull(prepare.futureCallToken)!!.key
        files().jailed(key).put(TypedData.text("hello", MediaType.Text.Plain))

        val verified = uploadEarly.verify.test(null, prepare.futureCallToken)

        val promoted = assertIs<UploadToken.Scanned>(uploadEarly.tokens().parseOrNull(verified)).key
        assertNotEquals(key, promoted, "Promotion must not reuse the client's jail key")
        assertEquals("hello", files().promoted(promoted).get()!!.text())
        assertEquals(
            files().promoted(promoted).serverFile.location,
            decode(uploadEarly.serializer(), verified).location
        )
    }

    /**
     * The scanner must be handed the promoted copy, not the jail file. Scanning the jail file would
     * leave the client free to swap the bytes afterwards through its still-live presigned upload URL.
     */
    @Test
    fun scannerReceivesThePromotedCopy(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val prepare = uploadEarly.endpoint.test(null, Unit)
        val key = uploadEarly.tokens().parseOrNull(prepare.futureCallToken)!!.key
        files().jailed(key).put(TypedData.text("hello", MediaType.Text.Plain))

        uploadEarly.verify.test(null, prepare.futureCallToken)

        val scanned = ScanControl.scanned.single().path.toString()
        assertTrue(scanned.contains("uploaded"), "Scanner should see the promoted copy, got: $scanned")
        assertFalse(scanned.contains("upload-jail"), "Scanner must not see the client-writable jail file")
    }

    /**
     * Regression for the scan/promote race.
     *
     * The swap has to happen *while the scan is running* to prove anything: that is the window the old
     * scan-then-copy ordering left open, and the scanner callback is the only place a test can stand in
     * it. Swapping after verify returns would pass under either ordering, since the copy is done by
     * then - which is exactly the mistake this test previously made.
     */
    @Test
    fun rewritingTheJailDuringTheScanCannotChangeThePromotedFile(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val prepare = uploadEarly.endpoint.test(null, Unit)
        val key = uploadEarly.tokens().parseOrNull(prepare.futureCallToken)!!.key
        val jailed = files().jailed(key)
        jailed.put(TypedData.text("benign", MediaType.Text.Plain))

        // The client's presigned upload URL stays live for the whole expiration window, so it can
        // rewrite the jail at any moment - including mid-scan.
        ScanControl.behavior = { jailed.put(TypedData.text("malicious", MediaType.Text.Plain)) }

        val verified = uploadEarly.verify.test(null, prepare.futureCallToken)
        val promoted = uploadEarly.tokens().parseOrNull(verified)!!.key

        assertEquals(
            "benign",
            files().promoted(promoted).get()!!.text(),
            "The promoted file must be the bytes that were scanned"
        )
        assertEquals("benign", decode(uploadEarly.serializer(), verified).externalFile.get()!!.text())
        assertEquals("malicious", jailed.get()!!.text(), "The mid-scan swap must actually have landed")
    }

    @Test
    fun failedScanRejectsAndLeavesNothingPromoted(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        ScanControl.behavior = { throw FileScanException("infected") }
        val prepare = uploadEarly.endpoint.test(null, Unit)
        val key = uploadEarly.tokens().parseOrNull(prepare.futureCallToken)!!.key
        files().jailed(key).put(TypedData.text("malicious", MediaType.Text.Plain))

        assertFailsWith<FileScanException> { uploadEarly.verify.test(null, prepare.futureCallToken) }

        assertEquals(
            emptyList(),
            files().root.then("uploaded").list(),
            "A file that failed scanning must not remain promoted"
        )
        // Failures are left in the jail deliberately; the expiry cleanup collects them.
        assertNotNull(files().jailed(key).get(), "The rejected upload should stay in the jail")
    }

    /**
     * Re-uploading to the still-live jail path and verifying again yields a separate file. A token
     * already handed out keeps naming the bytes it was certified for.
     */
    @Test
    fun reVerifyingAfterReuploadLeavesTheFirstFileIntact(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val prepare = uploadEarly.endpoint.test(null, Unit)
        val key = uploadEarly.tokens().parseOrNull(prepare.futureCallToken)!!.key
        files().jailed(key).put(TypedData.text("benign", MediaType.Text.Plain))
        val first = uploadEarly.tokens().parseOrNull(uploadEarly.verify.test(null, prepare.futureCallToken))!!.key

        files().jailed(key).put(TypedData.text("second", MediaType.Text.Plain))
        val second = uploadEarly.tokens().parseOrNull(uploadEarly.verify.test(null, prepare.futureCallToken))!!.key

        assertNotEquals(first, second, "Each verify must promote to its own file")
        assertEquals("benign", files().promoted(first).get()!!.text())
        assertEquals("second", files().promoted(second).get()!!.text())
    }

    /**
     * The cleanup on a failed scan must only ever touch the file that just failed. A client must not be
     * able to delete a file it had already had certified by re-uploading something infected.
     */
    @Test
    fun aFailedRescanCannotDestroyAnAlreadyCertifiedFile(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val prepare = uploadEarly.endpoint.test(null, Unit)
        val key = uploadEarly.tokens().parseOrNull(prepare.futureCallToken)!!.key
        files().jailed(key).put(TypedData.text("benign", MediaType.Text.Plain))
        val certified = uploadEarly.tokens().parseOrNull(uploadEarly.verify.test(null, prepare.futureCallToken))!!.key

        ScanControl.behavior = { throw FileScanException("infected") }
        files().jailed(key).put(TypedData.text("malicious", MediaType.Text.Plain))
        assertFailsWith<FileScanException> { uploadEarly.verify.test(null, prepare.futureCallToken) }

        assertEquals("benign", files().promoted(certified).get()!!.text(), "The certified file must survive")
    }

    /** Verifying an already-verified token is a no-op so a client can safely retry. */
    @Test
    fun verifyingAScannedTokenIsANoOp(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val prepare = uploadEarly.endpoint.test(null, Unit)
        val key = uploadEarly.tokens().parseOrNull(prepare.futureCallToken)!!.key
        files().jailed(key).put(TypedData.text("hello", MediaType.Text.Plain))
        val verified = uploadEarly.verify.test(null, prepare.futureCallToken)

        assertEquals(verified, uploadEarly.verify.test(null, verified))
        assertEquals(1, ScanControl.scanned.size, "Re-verifying must not scan again")
    }

    @Test
    fun forgedTokenIsRejected(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val prepare = uploadEarly.endpoint.test(null, Unit)
        val key = uploadEarly.tokens().parseOrNull(prepare.futureCallToken)!!.key
        val forged = prepare.futureCallToken.replace(key, "someone-elses.file")
        assertFailsWith<IllegalArgumentException> { uploadEarly.verify.test(null, forged) }
    }
}

/**
 * An unsigned file system makes the jail/ready split theatre: [ExternalFileSystem.parseExternalUrl]
 * accepts any path under its root, so a client can name the unscanned jail file directly instead of
 * verifying it. Configuring scanners against one must fail loudly rather than appear to scan.
 *
 * Its own server object so that it does not share [com.lightningkite.lightningserver.definition.Runtime]
 * caches with the signed-configuration tests above.
 */
class UploadEarlyUnsignedFileSystemTest {

    object Server : ServerBuilder() {
        val files = setting("files", ExternalFileSystem.Settings())
        val database = setting("database", Database.Settings())
        val uploadEarly = path.path("upload") include UploadEarlyEndpoint(
            files = files,
            database = database,
            fileScanner = Runtime { listOf(ControlledScanner(serverRuntime)) },
        )

        init {
            registerBasicMediaTypeCoders()
        }
    }

    @Test
    fun scannersAgainstAnUnsignedFileSystemAreRefused(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings(
            "file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files&signedUrlDuration=forever"
        )
        database set Database.Settings()
    }) {
        val failure = assertFailsWith<IllegalArgumentException> { uploadEarly.endpoint.test(null, Unit) }
        assertTrue(
            failure.message!!.contains("cannot be enforced"),
            "Should name the unenforceable-scanning problem, got: ${failure.message}"
        )
    }
}
