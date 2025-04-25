package com.lightningkite.lightningserver.db

import com.lightningkite.GeoCoordinate
import com.lightningkite.serialization.default
import kotlin.test.Test

class DefaultsTest {
    @Test fun basics() {
        println(GeoCoordinate.serializer().default())
    }
}