package com.lightningkite.ktordb

import com.lightningkite.ktordb.application.*
import com.mongodb.client.model.changestream.UpdateDescription
import kotlinx.coroutines.runBlocking
import org.bson.BsonDocument
import org.junit.Test
import org.litote.kmongo.bson
import kotlin.test.assertEquals


class UpdateDescriptionTest: MongoTest() {

    @Test
    fun testUpdate() {
        val item1 = EmbeddedMap(map = mapOf("item1" to RecursiveEmbed()))

        val collection = (EmbeddedMap.mongo as MongoFieldCollection<EmbeddedMap>).wraps
        val codec = collection.codecRegistry.get(collection.documentClass)

        var update = UpdateDescription(
            null,
            documentOf("map.item2" to documentOf("value2" to "String")).toBsonDocument()
        )
        var result = codec.fromUpdateDescription(item1, update)
        assertEquals(result, item1.copy(map = item1.map + ("item2" to RecursiveEmbed(value2 = "String"))))

        update = UpdateDescription(
            null,
            documentOf("map.item1.value2" to "other string").toBsonDocument()
        )
        result = codec.fromUpdateDescription(item1, update)
        assertEquals(result, item1.copy(map = mapOf("item1" to RecursiveEmbed(value2 = "other string"))))

        update = UpdateDescription(
            null,
            documentOf("map.item1.embedded" to documentOf("value2" to "String")).toBsonDocument()
        )
        result = codec.fromUpdateDescription(item1, update)
        assertEquals(result, item1.copy(map = mapOf("item1" to RecursiveEmbed(embedded = RecursiveEmbed(value2="String"),))))

    }

    @Test
    fun testRemove() {
        val item1 = EmbeddedMap(map = mapOf("item1" to RecursiveEmbed(embedded = RecursiveEmbed()), "item2" to RecursiveEmbed()))

        val collection = (EmbeddedMap.mongo as MongoFieldCollection<EmbeddedMap>).wraps
        val codec = collection.codecRegistry.get(collection.documentClass)

        var update = UpdateDescription(
            mutableListOf("map.item2"),
            null,
        )

        var result = codec.fromUpdateDescription(item1, update)
        assertEquals(result, item1.copy(map = mapOf("item1" to RecursiveEmbed(embedded = RecursiveEmbed()))))

        update = UpdateDescription(
            mutableListOf("map.item1.embedded"),
            null,
        )

        result = codec.fromUpdateDescription(item1, update)
        assertEquals(result, item1.copy(map = mapOf("item1" to RecursiveEmbed(), "item2" to RecursiveEmbed())))
    }

}