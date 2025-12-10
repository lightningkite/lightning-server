package com.lightningkite.lightningserver.typed

import kotlinx.io.writeString
import java.io.FileOutputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals

class ZipArchiveTests {

    @Test
    fun testBasicZipCreation() {
        val tempFile = createTempFile("test", ".zip").toFile()
        println("Creating ZIP at: ${tempFile.absolutePath}")

        try {
            // Create a ZIP file
            FileOutputStream(tempFile).use { fos ->
                val zipOut = ZipOutputStream(fos)
                Archive.zip(zipOut).use { archive ->
                    archive.entry("test.txt") {
                        writeString("Hello World")
                    }
                }
            }

            println("ZIP file size: ${tempFile.length()} bytes")

            // Try to read it back
            ZipFile(tempFile).use { zip ->
                val entries = zip.entries().toList()
                println("Found ${entries.size} entries")
                entries.forEach { entry ->
                    println("Entry: ${entry.name}")
                    val content = zip.getInputStream(entry).readAllBytes().decodeToString()
                    println("Content: $content")
                    assertEquals("Hello World", content)
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testZipWithoutClose() {
        val tempFile = createTempFile("test", ".zip").toFile()
        println("Creating ZIP without close at: ${tempFile.absolutePath}")

        try {
            // Create a ZIP file WITHOUT closing the Archive
            FileOutputStream(tempFile).use { fos ->
                val zipOut = ZipOutputStream(fos)
                val archive = Archive.zip(zipOut)
                archive.entry("test.txt") {
                    writeString("Hello World")
                }
                // NOT calling archive.close() or zipOut.close()
            }

            println("ZIP file size: ${tempFile.length()} bytes")

            // Try to read it back
            try {
                ZipFile(tempFile).use { zip ->
                    val entries = zip.entries().toList()
                    println("Found ${entries.size} entries")
                }
            } catch (_: ZipException) {
                println("Zip failed to open due to being unclosed. This is expected.")
            } catch (e: Exception) {
                println("Failed to read ZIP: ${e.message}")
                throw e
            }
        } finally {
            tempFile.delete()
        }
    }
}