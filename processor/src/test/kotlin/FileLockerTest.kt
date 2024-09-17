package com.lightningkite.lightningdb

import java.io.File
import java.io.RandomAccessFile
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class FileLockerTest {
    @Test fun testChecksum() {
        val base = File("../shared/src/commonMain/kotlin/com/lightningkite")
        println(base.absolutePath)
        repeat(5) {
            println(base.walkTopDown().filter { it.extension == "kt" }.checksum())
        }
    }

    @Test
    fun test() {
        /*
        GOAL:
        Concurrent usages don't die.

        - Abandon if already up to date
        - Start by locking with hash
        - If another instance is also joins here, it waits for the first
        - Build to a new folder
        - On complete, swap folders in
         */
        val testFolder = File("build/testdata")
        testFolder.mkdirs()
        val dependency = testFolder.resolve("src/test.txt").also { it.parentFile.mkdirs() }
            .also { it.writeText("Test ${Random.nextInt()}") }
        var runCount = 0
        (0..<10).map {
            Thread {
                println("Starting thread $it")
                processFiles(
                    1,
                    sequenceOf(dependency),
                    testFolder.resolve("test.lock"),
                    testFolder.resolve("out")
                ) {
                    runCount++
                    Thread.sleep(300)
                    file("out.txt").use {
                        val t = dependency.readText()
                        it.appendLine("ORIGINAL TEXT:")
                        it.appendLine(t)
                        it.appendLine("LENGTH: ${t.length}")
                        it.appendLine("JAVA HASH: ${t.hashCode()}")
                    }
                }
                println(testFolder.resolve("out/out.txt").readText())
            }.also { it.start() }
        }.forEach {
            it.join()
        }
        assertEquals(1, runCount)
    }
}