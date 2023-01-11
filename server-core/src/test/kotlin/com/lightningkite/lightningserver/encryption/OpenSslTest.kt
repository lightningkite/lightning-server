package com.lightningkite.lightningserver.encryption

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OpenSslTest {
    @Test fun test() {
        val testFile = File("build/test.txt")
        val outFile = File("build/test.enc")
        val testPass = "asfdjiawoejfakos"
        val testData = "Hello world!"
        testFile.writeText(testData)
        ProcessBuilder()
            .command("openssl enc -aes-256-cbc -in ${testFile} -out ${outFile} -pass pass:${testPass}".also { println(it) }.split(' '))
            .inheritIO()
            .directory(File("."))
            .start()
            .waitFor()
            .also { println("Got code $it") }
        OpenSsl.decryptAesCbcPkcs5Sha256(
            testPass.toByteArray(),
            outFile.readBytes()
        ).toString(Charsets.UTF_8).also {
            println(it)
            assertEquals(testData, it)
        }
    }
}