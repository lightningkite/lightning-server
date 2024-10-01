package com.lightningkite

import org.junit.Assert.*
import kotlin.test.Test

class PhoneNumberTest {
    @Test fun email() {
        val address = " Test.2+Bob@lightningkite.com".toEmailAddress()
        assertEquals("test.2+bob@lightningkite.com", address.raw)
        assertEquals("test.2+bob@lightningkite.com", address.toString())
        assertEquals("lightningkite.com", address.domain)
        assertEquals("test.2", address.localPart)
        assertEquals("test2", address.account)
        assertEquals("bob", address.plusAddress)
        assertEquals("test@lightningkite.com", address.toBaseAccount().raw)
    }
    @Test fun test() {

        assertEquals("+1 (801) 300-3000", "(801) 300-3000".toPhoneNumber().toString())
        assertEquals("+18013003000", "(801) 300-3000".toPhoneNumber().raw)

        assertEquals("+380441234567", "+380 (44) 1234567".toPhoneNumber().toString())
        assertEquals("+380441234567", "+380 (44) 1234567".toPhoneNumber().raw)
    }
}