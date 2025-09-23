package com.lightningkite.lightningserver.http

import kotlin.test.Test
import kotlin.test.assertEquals

class ParseTest {
    @Test
    fun pathHack() {
        QueryParameters.parse("path=/my/path?asdf=fdsa")
            .pathHack()
            .let {
                assertEquals("path", it[0].first)
                assertEquals("/my/path?asdf=fdsa", it[0].second)
                assertEquals("asdf", it[1].first)
                assertEquals("fdsa", it[1].second)
            }
    }

    @Test
    fun pathHackWithNoExtraParams() {
        QueryParameters.parse("path=/multiplex")
            .pathHack()
            .let {
                assertEquals(1, it.entries.size)
                assertEquals("path", it[0].first)
                assertEquals("/multiplex", it[0].second)
            }

        QueryParameters.parse("path=/multiplex?param=5")
            .pathHack()
            .let {
                assertEquals(2, it.entries.size)
                assertEquals("path", it[0].first)
                assertEquals("/multiplex?param=5", it[0].second)
                assertEquals("param", it[1].first)
                assertEquals("5", it[1].second)
            }


    }
}