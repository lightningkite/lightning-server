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
        assertEquals("joseph@lightningkite.com", " jOsEph".trimmedCaseless().mapRaw { "$it@LigHTNINGkite.com  " }.raw)
        assertEquals("jOsEph@LigHTNINGkite.com", " jOsEph".trimmed().mapRaw { "$it@LigHTNINGkite.com  " }.raw)
        assertEquals(" joseph@lightningkite.com  ", " jOsEph".caseless().mapRaw { "$it@LigHTNINGkite.com  " }.raw)
    }

    @Test fun email() {
        val address = " Test.2+Bob@lightningkite.com".toEmailAddress()
        assertEquals("test.2+bob@lightningkite.com", address.raw)
        assertEquals("test.2+bob@lightningkite.com", address.toString())
        assertEquals("lightningkite.com", address.domain)
        assertEquals("test.2+bob", address.localPart)
        assertEquals("test.2", address.probableAccount)
        assertEquals("test2", "Test.2+Bob@gmail.com".toEmailAddress().probableAccount)
        assertEquals("bob", address.subAddress)
        assertEquals("test.2@lightningkite.com", address.toProbableBaseEmailAddress().raw)
        assertEquals("test2@gmail.com", "Test.2+Bob@gmail.com".toEmailAddress().toProbableBaseEmailAddress().raw)
    }
    @Test fun phone() {

        assertEquals("+1 (801) 300-3000", "(801) 300-3000".toPhoneNumber().toString())
        assertEquals("+18013003000", "(801) 300-3000".toPhoneNumber().raw)

        assertEquals("+380441234567", "+380 (44) 1234567".toPhoneNumber().toString())
        assertEquals("+380441234567", "+380 (44) 1234567".toPhoneNumber().raw)
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