package com.lightningkite.lightningserver.files

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class FileValidationTest {

    @Serializable
    data class SampleItem(
        @Contextual @MimeType("text/plain", maxSize = 50) val file: ServerFile? = null
    )

    @Test fun test() = runBlocking {
        Serialization.validateOrThrow(SampleItem.serializer(), SampleItem())
        TestSettings.files().root.resolve("validation-test-ok.txt").let { f ->
            f.put(HttpContent.Text("Test", ContentType.Text.Plain))
            Serialization.validateOrThrow(SampleItem.serializer(), SampleItem(file = f.serverFile))
        }
        TestSettings.files().root.resolve("validation-test-missing.txt").let { f ->
            assertThrows<BadRequestException> { Serialization.validateOrThrow(SampleItem.serializer(), SampleItem(file = f.serverFile)) }
        }
        TestSettings.files().root.resolve("validation-test-too-big.txt").let { f ->
            f.put(HttpContent.Text("Test".repeat(1000), ContentType.Text.Plain))
            assertThrows<BadRequestException> { Serialization.validateOrThrow(SampleItem.serializer(), SampleItem(file = f.serverFile)) }
        }
        TestSettings.files().root.resolve("validation-test-wrong-type.txt").let { f ->
            f.put(HttpContent.Text("{}", ContentType.Application.Json))
            assertThrows<BadRequestException> { Serialization.validateOrThrow(SampleItem.serializer(), SampleItem(file = f.serverFile)) }
        }
    }
    private inline fun <reified T: Throwable> assertThrows(action: ()->Unit) {
        var died: Throwable? = null
        try {
            action()
        } catch(t: Throwable) {
            t.printStackTrace()
            died = t
        }
        assertNotNull(died)
        assertIs<T>(died)
    }
}