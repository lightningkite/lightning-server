package com.lightningkite

import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class StringAltsTest {
    @Test fun test() {
        fun String.serCircle(serializer: KSerializer<String>) = Json.decodeFromString(serializer, Json.encodeToString(serializer, this))
        assertEquals("joseph@lightningkite.com", " jOsEph@LigHTNINGkite.com  ".trimmedCaseless().raw)
        assertEquals("jOsEph@LigHTNINGkite.com", " jOsEph@LigHTNINGkite.com  ".trimmed().raw)
        assertEquals(" joseph@lightningkite.com  ", " jOsEph@LigHTNINGkite.com  ".caseless().raw)
        assertEquals("joseph@lightningkite.com", " jOsEph@LigHTNINGkite.com  ".serCircle(TrimLowercaseOnSerialize))
        assertEquals("jOsEph@LigHTNINGkite.com", " jOsEph@LigHTNINGkite.com  ".serCircle(TrimOnSerialize))
        assertEquals(" joseph@lightningkite.com  ", " jOsEph@LigHTNINGkite.com  ".serCircle(LowercaseOnSerialize))
        val testItemRaw = TestItem(
            plain = " jOsEph@LigHTNINGkite.com  ",
            sampleTrimLowercaseOnSerialize = " jOsEph@LigHTNINGkite.com  ",
            sampleTrimOnSerialize = " jOsEph@LigHTNINGkite.com  ",
            sampleLowercaseOnSerialize = " jOsEph@LigHTNINGkite.com  ",
        )
        val testItem = Json.decodeFromString(TestItem.serializer(), Json.encodeToString(TestItem.serializer(), testItemRaw).also { println(it) })
        assertEquals("joseph@lightningkite.com", testItem.sampleTrimLowercaseOnSerialize)
        assertEquals("jOsEph@LigHTNINGkite.com", testItem.sampleTrimOnSerialize)
        assertEquals(" joseph@lightningkite.com  ", testItem.sampleLowercaseOnSerialize)
        assertEquals(" jOsEph@LigHTNINGkite.com  ".trimmedCaseless(), testItem.trimmedCaseless)
        assertEquals(" jOsEph@LigHTNINGkite.com  ".trimmed(), testItem.trimmed)
        assertEquals(" jOsEph@LigHTNINGkite.com  ".caseless(), testItem.caseless)
    }

    @Serializable
    data class TestItem(
        val plain: String,
        @Serializable(TrimLowercaseOnSerialize::class) val sampleTrimLowercaseOnSerialize: String = plain.trim().lowercase(),
        @Serializable(TrimOnSerialize::class) val sampleTrimOnSerialize: String = plain.trim(),
        @Serializable(LowercaseOnSerialize::class) val sampleLowercaseOnSerialize: String = plain.lowercase(),
        val trimmedCaseless: TrimmedCaselessString = plain.trimmedCaseless(),
        val trimmed: TrimmedString = plain.trimmed(),
        val caseless: CaselessString = plain.caseless(),
    )
}