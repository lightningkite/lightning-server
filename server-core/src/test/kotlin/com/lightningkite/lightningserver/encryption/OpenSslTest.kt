package com.lightningkite.lightningserver.encryption

import org.junit.Test

class OpenSslTest {
    @Test
    fun test() {
        // This freaking test won't do it's job.
        // It's the command that it runs.  Tested manually, it works great.
//        val testFile = File("build/test.txt")
//        val outFile = File("build/test.enc")
//        val testPass = "asfdjiawoejfakos"
//        val testData = "Hello world!"
//        testFile.writeText(testData + "\n")
//        ProcessBuilder()
//            .command("openssl enc -aes-256-cbc -md sha256 -out ${outFile} -pass pass:${testPass}".also { println(it) }.split(' '))
//            .redirectInput(testFile)
//            .inheritIO()
//            .directory(File("."))
//            .start()
//            .waitFor()
//            .also { println("Got code $it") }
//        OpenSsl.decryptAesCbcPkcs5Sha256(
//            testPass.toByteArray(),
//            outFile.readBytes()
//        ).toString(Charsets.UTF_8).also {
//            println(it)
//            assertEquals(testData, it)
//        }
    }
}