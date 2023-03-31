package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.typed.parseUrlPartOrBadRequest
import org.junit.Test
import java.util.*

class ReadPrimitiveTest {
    @Test
    fun primitiveTest() {
        println("29a51eee-3048-41f3-b2c3-fdd06ad72edd".parseUrlPartOrBadRequest<UUID>())
    }
}