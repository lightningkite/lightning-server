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
        val scope = CalculationContext.Standard()
        prepareModels()
        val collectionsToTest = listOf<ModelCollection<SampleModel, UUID>>(
            MockModelCollection(SampleModel.serializer()),
//            ModelCache(MockClientModelRestEndpoints(::println), SampleModel.serializer()),
//            ModelCache(
//                object : ClientModelRestEndpointsPlusWs<SampleModel, UUID> by MockClientModelRestEndpoints(::println) {},
//                SampleModel.serializer()
//            ),
//            ModelCache(
//                object : ClientModelRestEndpoints<SampleModel, UUID> by MockClientModelRestEndpoints(::println) {},
//                SampleModel.serializer()
//            ),
//            ModelCache3(MockClientModelRestEndpoints(::println), SampleModel.serializer()),
//            ModelCache3(
//                object : ClientModelRestEndpoints<SampleModel, UUID> by MockClientModelRestEndpoints(::println) {},
//                SampleModel.serializer()
//            )
        )
        launchGlobal {
            val test1 = SampleModel(title = "Test 1", body = "Test 1 body contents")
            val test2 = SampleModel(title = "Test 2", body = "Test 2 body contents")
            val test3 = SampleModel(title = "Test 3", body = "Test 3 body contents")


            assertEquals(
                listOf(test1, test2, test3).sortedBy { it.at },
                listOf(test1, test2, test3).sortedWith(sort<SampleModel> { it.at.ascending() }.comparator!!)
            )

            fun <Self : Readable<T>, T> Self.reportForTest(label: String): Self {
                scope.reactiveScope(onLoad = { println("Loading...") }) {
                    print("$label updated: ")
                    println("${await()}")
                }
                return this
            }


            suspend fun regularly() = collectionsToTest.forEach {
                if (it is ModelCache<*, *>) it.regularly()
                if (it is ModelCache3<*, *>) it.regularly()
            }

            val query1 = collectionsToTest.mapIndexed { index, it -> it.query(Query<SampleModel>(orderBy = sort { it.at.ascending() })).reportForTest("query1$index") }
            val query2 = collectionsToTest.mapIndexed { index, it ->
                it.query(Query<SampleModel>(condition { it.title eq "Test 1" }, orderBy = sort { it.at.ascending() })).reportForTest("query2$index")
            }
            regularly()
            var rechecksExpected = 0
            var rechecksFinished = 0
            suspend fun recheckQueries() {
                regularly()
                rechecksExpected++
                query1.assertEqual { it.state }
                query2.assertEqual { it.state }
                rechecksFinished++
                regularly()
            }
            recheckQueries()

            val result1 = collectionsToTest.mapIndexed { index, it -> it.insert(test1).reportForTest("result1$index") }
            result1.assertEqual { it.await() }
            recheckQueries()
            result1.forEach { it.modify(modification { it.title assign "Changed" }) }
            result1.assertEqual { it.await() }
            recheckQueries()

            val subs = result1.mapIndexed { index, it -> it.prop { it.title }.reportForTest("sub$index") }
            subs.forEach { it set "New Title 1" }
            recheckQueries()

            val result2 = collectionsToTest.mapIndexed { index, it -> it.insert(test2).reportForTest("result2$index") }
            recheckQueries()
            val result3 = collectionsToTest.mapIndexed { index, it -> it.insert(test3).reportForTest("result3$index") }
            recheckQueries()


            println("Complete!")
            assertEquals(rechecksExpected, rechecksFinished)
        }
        scope.cancel()
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