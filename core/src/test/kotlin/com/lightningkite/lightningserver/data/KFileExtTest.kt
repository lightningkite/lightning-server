package com.lightningkite.lightningserver.data

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class KFileExtTest {
    private val String.unix get() = replace('\\', '/')

    @Test
    fun testKFileToJavaFile() {
        val path = Path("/tmp/test.txt")
        val kFile = com.lightningkite.services.data.KFile(SystemFileSystem, path)
        val javaFile = kFile.toJavaFile()

        assertEquals("/tmp/test.txt", javaFile.path.unix)
    }

    @Test
    fun testJavaFileToKFile() {
        val javaFile = File("/tmp/test.txt")
        val kFile = javaFile.toKFile()

        assertEquals("/tmp/test.txt", kFile.path.toString().unix)
    }

    @Test
    fun testRoundTrip() {
        val originalJavaFile = File("/tmp/test.txt")
        val kFile = originalJavaFile.toKFile()
        val backToJavaFile = kFile.toJavaFile()

        assertEquals(originalJavaFile.path.unix, backToJavaFile.path.unix)
    }

    @Test
    fun testWithSpacesInPath() {
        val javaFile = File("/tmp/test file.txt")
        val kFile = javaFile.toKFile()

        assertEquals("/tmp/test file.txt", kFile.path.toString().unix)
    }

    @Test
    fun testRelativePath() {
        val javaFile = File("relative/path/test.txt")
        val kFile = javaFile.toKFile()

        assertEquals("relative/path/test.txt", kFile.path.toString().unix)
    }
}
