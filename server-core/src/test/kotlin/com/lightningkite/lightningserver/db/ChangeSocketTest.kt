@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.db.testmodels.TestThing
import com.lightningkite.lightningserver.db.testmodels.TestThing_value
import com.lightningkite.lightningserver.typed.test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangeSocketTest {

    @Test
    fun test() {
        val database = TestSettings.database
        runBlocking {
            com.lightningkite.lightningserver.db.testmodels.prepareModels()
            database().collection<TestThing>().deleteMany(Condition.Always())
            TestSettings.ws.test {

                suspend fun assertSent(
                    inserted: TestThing
                ) {
                    while(incoming.tryReceive().isSuccess) {}
                    database().collection<TestThing>().insertOne(inserted)
                    assertEquals(ListChange(new = inserted), incoming.receive().also { println("Got $it") })
                }
                suspend fun assertNotSent(
                    inserted: TestThing
                ) {
                    while(incoming.tryReceive().isSuccess) {}
                    database().collection<TestThing>().insertOne(inserted)
                    assertTrue(incoming.tryReceive().isFailure)
                }

                val initial = TestThing()
                assertNotSent(initial)

                // Initial result

                while(incoming.tryReceive().isSuccess) {}
                this.send(Query())
                assertEquals(ListChange(wholeList = listOf(initial)), incoming.receive().also { println("Got $it") })

                // Broad query

                assertSent(TestThing())

                // Limited query

                while(incoming.tryReceive().isSuccess) {}
                this.send(Query(Condition.OnField(TestThing_value, Condition.Equal(42))))
                assertEquals(ListChange(wholeList = listOf()), incoming.receive().also { println("Got $it") })

                assertNotSent(TestThing())
                assertSent(TestThing(value = 42))
            }
        }
    }

    @Test
    fun test2() {
        val database = TestSettings.database
        runBlocking {
            database().collection<TestThing>().deleteMany(Condition.Always())
            TestSettings.ws2.test {

                suspend fun assertSent(
                    inserted: TestThing
                ) {
                    while(incoming.tryReceive().isSuccess) {}
                    database().collection<TestThing>().insertOne(inserted)
                    assertEquals(ListChange(new = inserted), incoming.receive().also { println("Got $it") })
                }
                suspend fun assertNotSent(
                    inserted: TestThing
                ) {
                    while(incoming.tryReceive().isSuccess) {}
                    database().collection<TestThing>().insertOne(inserted)
                    assertTrue(incoming.tryReceive().isFailure)
                }

                val initial = TestThing()
                assertNotSent(initial)

                // Initial result

                while(incoming.tryReceive().isSuccess) {}
                this.send(Query())
                assertEquals(ListChange(wholeList = listOf(initial)), incoming.receive().also { println("Got $it") })

                // Broad query

                assertSent(TestThing())

                // Limited query

                while(incoming.tryReceive().isSuccess) {}
                this.send(Query(Condition.OnField(TestThing_value, Condition.Equal(42))))
                assertEquals(ListChange(wholeList = listOf()), incoming.receive().also { println("Got $it") })

                assertNotSent(TestThing())
                assertSent(TestThing(value = 42))
            }
        }
    }
}
