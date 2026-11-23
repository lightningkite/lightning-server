package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.*
import com.lightningkite.services.kfile.KFile
import java.nio.file.Files
import kotlin.test.Test

class ClientSdksTest {
    @Test fun `generators write module interfaces and functions`() {
        val dir = Files.createTempDirectory("sdk").toFile()
        generateSdks(KFile(dir.absolutePath))
        val kotlin = dir.resolve("kotlin").walkTopDown().filter { it.isFile }.associate { it.name to it.readText() }
        val typescript = dir.resolve("typescript").walkTopDown().filter { it.isFile }.associate { it.name to it.readText() }
        (kotlin + typescript).forEach { (name, text) -> println("===== $name\n$text") }

        val api = kotlin.getValue("Api.kt")
        check("val memo: MemoApi" in api) { api }
        check("val status: StatusApi" in api) { api }
        check("suspend fun getVersion(): kotlin.String" in api) { api }
        check("interface MemoApi" in api) { api }
        check("fun withMetadata(" in api) { api }
        check("listMemos" in typescript.values.joinToString()) { typescript.keys.toString() }
    }

    @Test fun `api baseline round-trips without failures`() {
        val file = Files.createTempFile("api-baseline", ".json").toFile()
        writeApiBaseline(KFile(file.absolutePath))
        check(checkApiAgainstBaseline(KFile(file.absolutePath)))
    }
}
