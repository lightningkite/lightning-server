@file:UseContextualSerialization(UUID::class, Instant::class)

package com.lightningkite.lightningserver.db

import com.lightningkite.UUID
import com.lightningkite.lightningdb.*
import com.lightningkite.now
import com.lightningkite.kiteui.launchGlobal
import com.lightningkite.kiteui.reactive.CalculationContext
import com.lightningkite.kiteui.reactive.Readable
import com.lightningkite.kiteui.reactive.await
import com.lightningkite.kiteui.reactive.reactiveScope
import com.lightningkite.uuid
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelRestEndpointsTest {


    @Test
    fun test() {

    }
}

@Serializable
@GenerateDataClassPaths
data class SampleModel(
    override val _id: UUID = uuid(),
    val title: String,
    val body: String,
    val at: Instant = now(),
) : HasId<UUID>

inline fun <T, V> List<T>.assertEqual(mapper: (T) -> V) {
    val first = first().let(mapper)
    for (item in drop(1)) {
        val other = item.let(mapper)
        println("ASSERT EQUALS  $first vs $other")
        assertEquals(first, other)
    }
}