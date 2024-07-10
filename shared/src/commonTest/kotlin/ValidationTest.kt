package com.lightningkite

import com.lightningkite.lightningdb.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.*

@Serializable
@GenerateDataClassPaths
data class Sample(
    @MaxLength(5) val x: String = "asdf",
    @IntegerRange(0, 100) val y: Int = 4,
)

class ValidationTest {
    init {
        com.lightningkite.prepareModels()
    }

    inline fun <reified T> assertPasses(item: T) {
        Json.serializersModule.validateFast(Json.serializersModule.serializer<T>(), item) { fail(it.toString()) }
    }

    inline fun <reified T> assertFails(item: T) {
        var failed = false
        Json.serializersModule.validateFast(Json.serializersModule.serializer<T>(), item) {
            println(it)
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun test() {
        assertPasses(Sample("ASDFA"))
        assertFails(Sample("ASDFAB"))
        assertPasses(modification<Sample> { it.x assign "ASDFA" })
        assertFails(modification<Sample> { it.x assign "ASDFAB" })
        assertFails(modification<Sample> {
            modifications += it.x.mapModification(
                Modification.Chain(
                    listOf(
                        Modification.Assign("asdfa"),
                        Modification.Assign("asdfab"),
                    )
                )
            )
        })
        assertFails(modification<Sample> {
            it.x assign "asdfab"
            it.y assign 101
        })
    }
}