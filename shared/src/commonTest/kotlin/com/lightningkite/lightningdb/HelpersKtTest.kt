package com.lightningkite.lightningdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelpersKtTest {
    @Test fun textQueryParse() {
        assertEquals(
            TextQuery(
                loose = setOf("one", "two")
            ),
            TextQuery.fromString("one two")
        )
        assertEquals(
            TextQuery(
                loose = setOf("one"),
                exact = setOf("two three")
            ),
            TextQuery.fromString("one \"two three\"")
        )
        assertEquals(
            TextQuery(
                loose = setOf("one"),
                exact = setOf("two three"),
                reject = setOf("four")
            ),
            TextQuery.fromString("one \"two three\" -four ")
        )
    }
    @Test fun requireTermsPresent() {
        assertTrue(TextQuery(loose = setOf("one", "two")).fuzzyPresent("one two three"))
        assertTrue(TextQuery(loose = setOf("one", "twoo")).fuzzyPresent("one two three"))
        assertTrue(TextQuery(loose = setOf("one", "too")).fuzzyPresent("one two three"))
        assertTrue(TextQuery(loose = setOf("ONE", "TOO")).fuzzyPresent("one two three"))
        assertFalse(TextQuery(loose = setOf("one", "four")).fuzzyPresent("one two three"))
        assertFalse(TextQuery(loose = setOf("one", "fsd")).fuzzyPresent("one two three"))
    }
}