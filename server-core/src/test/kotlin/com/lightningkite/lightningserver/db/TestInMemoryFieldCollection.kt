package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.InMemoryFieldCollection
import com.lightningkite.lightningdb.collection
import com.lightningkite.lightningdb.insertOne
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.db.testmodels.UniqueComboClass
import com.lightningkite.lightningserver.db.testmodels.UniqueFieldClass
import com.lightningkite.lightningserver.db.testmodels.UniqueSetClass
import com.lightningkite.lightningserver.exceptions.BadRequestException
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TestInMemoryFieldCollection {

    lateinit var comboCollection: InMemoryFieldCollection<UniqueComboClass>
    lateinit var fieldCollection: InMemoryFieldCollection<UniqueFieldClass>
    lateinit var setCollection: InMemoryFieldCollection<UniqueSetClass>

    @Before
    fun setup(){

        prepareModels()
        com.lightningkite.lightningserver.db.testmodels.prepareModels()
        comboCollection = TestSettings.database().collection<UniqueComboClass>() as InMemoryFieldCollection
        comboCollection.drop()
        fieldCollection = TestSettings.database().collection<UniqueFieldClass>() as InMemoryFieldCollection
        fieldCollection.drop()
        setCollection = TestSettings.database().collection<UniqueSetClass>() as InMemoryFieldCollection
        setCollection.drop()
    }


    @Test
    fun testUniqueFields():Unit = runBlocking {

        assertEquals(0, fieldCollection.data.size)

        fieldCollection.insertOne(UniqueFieldClass(1,1))
        assertEquals(1, fieldCollection.data.size)
        assertFailsWith<BadRequestException> { fieldCollection.insertOne(UniqueFieldClass(1,1)) }
        assertEquals(1, fieldCollection.data.size)
        assertFailsWith<BadRequestException> { fieldCollection.insertOne(UniqueFieldClass(2,1)) }
        assertEquals(1, fieldCollection.data.size)
        fieldCollection.insertOne(UniqueFieldClass(2,2))
        assertEquals(2, fieldCollection.data.size)

        fieldCollection.insert(listOf(UniqueFieldClass(3,3), UniqueFieldClass(4,4),))
        assertEquals(4, fieldCollection.data.size)

        assertFailsWith<BadRequestException> { fieldCollection.insert(listOf(UniqueFieldClass(3,3), UniqueFieldClass(4,4),)) }
        assertEquals(4, fieldCollection.data.size)
        assertFailsWith<BadRequestException> { fieldCollection.insert(listOf(UniqueFieldClass(5,5), UniqueFieldClass(1,1),)) }
        assertEquals(4, fieldCollection.data.size)
    }


    @Test
    fun testUniqueSet():Unit = runBlocking {

        assertEquals(0, setCollection.data.size)

        setCollection.insertOne(UniqueSetClass(1,1,1))
        assertEquals(1, setCollection.data.size)
        assertFailsWith<BadRequestException> { setCollection.insertOne(UniqueSetClass(1,1,1)) }
        assertEquals(1, setCollection.data.size)
        assertFailsWith<BadRequestException> { setCollection.insertOne(UniqueSetClass(2,1,1)) }
        assertEquals(1, setCollection.data.size)

        setCollection.insertOne(UniqueSetClass(3,1,2))
        setCollection.insertOne(UniqueSetClass(4,2,1))
        assertEquals(3, setCollection.data.size)

        assertFailsWith<BadRequestException> { setCollection.insertOne(UniqueSetClass(5,1,2)) }
        assertEquals(3, setCollection.data.size)
        assertFailsWith<BadRequestException> { setCollection.insertOne(UniqueSetClass(5,2,1)) }
        assertEquals(3, setCollection.data.size)

        setCollection.insert(listOf(UniqueSetClass(5,5,5), UniqueSetClass(6,6,6),))
        assertEquals(5, setCollection.data.size)

        assertFailsWith<BadRequestException> { setCollection.insert(listOf(UniqueSetClass(5,5,5), UniqueSetClass(6,6,6),)) }
        assertEquals(5, setCollection.data.size)
        assertFailsWith<BadRequestException> { setCollection.insert(listOf(UniqueSetClass(7,7,7), UniqueSetClass(1,1,1),)) }
        assertEquals(5, setCollection.data.size)

    }


    @Test
    fun testUniqueComboFields():Unit = runBlocking {

        assertEquals(0, comboCollection.data.size)

        comboCollection.insertOne(UniqueComboClass(1,1,1,1))
        assertEquals(1, comboCollection.data.size)
        assertFailsWith<BadRequestException> { comboCollection.insertOne(UniqueComboClass(1,1,1,1)) }
        assertEquals(1, comboCollection.data.size)
        assertFailsWith<BadRequestException> { comboCollection.insertOne(UniqueComboClass(2,1,1,1)) }
        assertEquals(1, comboCollection.data.size)
        assertFailsWith<BadRequestException> { comboCollection.insertOne(UniqueComboClass(1,2,1,1)) }
        assertEquals(1, comboCollection.data.size)
        assertFailsWith<BadRequestException> { comboCollection.insertOne(UniqueComboClass(1,1,1,2)) }
        assertEquals(1, comboCollection.data.size)

        comboCollection.insertOne(UniqueComboClass(2,2,2,2))
        comboCollection.insertOne(UniqueComboClass(3,3,1,2))
        comboCollection.insertOne(UniqueComboClass(4,4,2,1))
        comboCollection.insertOne(UniqueComboClass(1,5,3,3))
        assertEquals(5, comboCollection.data.size)

        comboCollection.insert(listOf(UniqueComboClass(6,6,6,6), UniqueComboClass(7,7,7,7),))
        assertEquals(7, comboCollection.data.size)

        assertFailsWith<BadRequestException> { comboCollection.insert(listOf(UniqueComboClass(6,6,6,6), UniqueComboClass(1,1,1,1),)) }
        assertEquals(7, comboCollection.data.size)
        assertFailsWith<BadRequestException> { comboCollection.insert(listOf(UniqueComboClass(8,8,8,8), UniqueComboClass(1,1,1,1),)) }
        assertEquals(7, comboCollection.data.size)

    }

}