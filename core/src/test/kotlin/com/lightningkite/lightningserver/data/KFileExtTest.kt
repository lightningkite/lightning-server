package com.lightningkite.lightningserver.data

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class KFileExtTest {
    @Test
    fun testKFileToJavaFile() {
        val path = Path("/tmp/test.txt")
        val kFile = com.lightningkite.services.data.KFile(SystemFileSystem, path)
        val javaFile = kFile.toJavaFile()

        assertEquals("/tmp/test.txt", javaFile.path)
    }

    @Test
    fun testJavaFileToKFile() {
        val javaFile = File("/tmp/test.txt")
        val kFile = javaFile.toKFile()

        assertEquals("/tmp/test.txt", kFile.path.toString())
    }

    @Test
    fun testRoundTrip() {
        val originalJavaFile = File("/tmp/test.txt")
        val kFile = originalJavaFile.toKFile()
        val backToJavaFile = kFile.toJavaFile()

        assertEquals(originalJavaFile.path, backToJavaFile.path)
    }

    @Test
    fun testWithSpacesInPath() {
        val javaFile = File("/tmp/test file.txt")
        val kFile = javaFile.toKFile()

        assertEquals("/tmp/test file.txt", kFile.path.toString())
    }

    @Test
    fun testRelativePath() {
        val javaFile = File("relative/path/test.txt")
        val kFile = javaFile.toKFile()

        assertEquals("relative/path/test.txt", kFile.path.toString())
    }
}
