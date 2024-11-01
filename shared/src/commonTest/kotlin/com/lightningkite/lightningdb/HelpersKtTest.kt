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
        assertTrue(TextQuery(loose = setOf("four", "three")).fuzzyPresent("one two three four"))
        assertTrue(TextQuery(loose = setOf("four", "threee")).fuzzyPresent("one two three four"))
        assertTrue(TextQuery(loose = setOf("four", "thre")).fuzzyPresent("one two three four"))
        assertTrue(TextQuery(loose = setOf("FOUR", "THRE")).fuzzyPresent("one two three four"))
        assertFalse(TextQuery(loose = setOf("four", "five")).fuzzyPresent("one two three four"))
        assertFalse(TextQuery(loose = setOf("four", "fsd")).fuzzyPresent("one two three four"))
    }
}