package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.exceptions.BadRequestException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ValidationTest {

    @Serializable
    data class SampleItem(
        @IntegerRange(0, 100) val number: Int = 20,
        @MaxLength(8) @ExpectedPattern("[a-zA-Z0-9]+") val text: String = "text",
    )

    @Test fun test() = runBlocking {
        Serialization.validateOrThrow(SampleItem.serializer(), SampleItem())
        assertThrows<BadRequestException> { Serialization.validateOrThrow(SampleItem.serializer(), SampleItem(number = 111)) }
        assertThrows<BadRequestException> { Serialization.validateOrThrow(SampleItem.serializer(), SampleItem(text = "asdfasdfa")) }
        assertThrows<BadRequestException> { Serialization.validateOrThrow(SampleItem.serializer(), SampleItem(text = "$")) }
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