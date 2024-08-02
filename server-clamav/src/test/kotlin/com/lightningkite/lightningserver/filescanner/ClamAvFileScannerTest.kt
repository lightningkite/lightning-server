package com.lightningkite.lightningserver.filescanner

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpContent
import kotlinx.coroutines.runBlocking
import kotlinx.html.*
import kotlin.test.Test
import kotlin.test.assertFailsWith


class ClamAvFileScannerTest {
    @Test
    fun test() = runBlocking<Unit> {
        try {
            Runtime.getRuntime().exec(arrayOf("clamd", "--version"))
        } catch(e: Exception) {
            println("Could not find clamav on this machine.  Exiting.")
            return@runBlocking
        }
        ClamAvFileScanner
        val x = FileScannerSettings(listOf("clamav://localhost/UNIX"))()
        x.scan(HttpContent.html {
            head {
                title = "Title"
            }
            body {
                h1 { +"My first page" }
                p { +"Welcome to my page!" }
            }
        })
        assertFailsWith<BadRequestException> {
            x.scan(HttpContent.Text(
                "X5O!P%@AP[4\\PZX54(P^)7CC)7}\$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!\$H+H*",
                ContentType.Text.Plain
            ))
        }
    }
}