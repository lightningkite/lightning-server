package com.lightningkite.lightningserver.filescanner

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpContent
import kotlinx.coroutines.runBlocking
import kotlinx.html.*
import org.junit.Assert.*
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CheckMimeFileScannerTest {
    @Test
    fun test() = runBlocking<Unit> {
        try {
            Runtime.getRuntime().exec(arrayOf("clamd", "--version"))
        } catch (e: Exception) {
            println("Could not find clamav on this machine.  Exiting.")
            return@runBlocking
        }
        val x = FileScannerSettings(listOf("mime"))()
        x.scan(HttpContent.html {
            head {
                title = "Title"
            }
            body {
                h1 { +"My first page" }
                p { +"Welcome to my page!" }
            }
        })
        val jpegData = Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=")
        println(jpegData.take(16).joinToString { it.toUByte().toString(16).padStart(2, '0') })
        x.scan(
            HttpContent.Binary(
                jpegData,
                ContentType.Image.JPEG
            )
        )
        assertFailsWith<BadRequestException> {
            x.scan(
                HttpContent.Text(
                    "test data",
                    ContentType.Image.JPEG
                )
            )
        }
    }
}