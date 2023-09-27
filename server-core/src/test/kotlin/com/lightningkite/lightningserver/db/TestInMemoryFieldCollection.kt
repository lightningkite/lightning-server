package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.InMemoryFieldCollection
import com.lightningkite.lightningdb.UniqueViolationException
import com.lightningkite.lightningdb.collection
import com.lightningkite.lightningdb.insertOne
import com.lightningkite.lightningserver.testmodels.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TestInMemoryFieldCollection {

    lateinit var fieldCollection: InMemoryFieldCollection<UniqueFieldClass>
    lateinit var setCollection: InMemoryFieldCollection<UniqueSetClass>
    lateinit var comboCollection: InMemoryFieldCollection<UniqueComboClass>
    lateinit var setJankCollection: InMemoryFieldCollection<UniqueSetJankClass>
    lateinit var comboJankCollection: InMemoryFieldCollection<UniqueComboJankClass>

    @Before
    fun setup(){

        prepareModels()
        com.lightningkite.lightningserver.testmodels.prepareModels()
        fieldCollection = InMemoryDatabase().collection<UniqueFieldClass>() as InMemoryFieldCollection
        setCollection = InMemoryDatabase().collection<UniqueSetClass>() as InMemoryFieldCollection
        comboCollection = InMemoryDatabase().collection<UniqueComboClass>() as InMemoryFieldCollection
        setJankCollection = InMemoryDatabase().collection<UniqueSetJankClass>() as InMemoryFieldCollection
        comboJankCollection = InMemoryDatabase().collection<UniqueComboJankClass>() as InMemoryFieldCollection

    }


    @Test
    fun testUniqueFields():Unit = runBlocking {

        assertEquals(0, fieldCollection.data.size)

        fieldCollection.insertOne(UniqueFieldClass(1,1))
        assertEquals(1, fieldCollection.data.size)
        assertFailsWith<UniqueViolationException> { fieldCollection.insertOne(UniqueFieldClass(1,1)) }
        assertEquals(1, fieldCollection.data.size)
        assertFailsWith<UniqueViolationException> { fieldCollection.insertOne(UniqueFieldClass(2,1)) }
        assertEquals(1, fieldCollection.data.size)
        fieldCollection.insertOne(UniqueFieldClass(2,2))
        assertEquals(2, fieldCollection.data.size)

        fieldCollection.insert(listOf(UniqueFieldClass(3,3), UniqueFieldClass(4,4),))
        assertEquals(4, fieldCollection.data.size)

        assertFailsWith<UniqueViolationException> { fieldCollection.insert(listOf(UniqueFieldClass(3,3), UniqueFieldClass(4,4),)) }
        assertEquals(4, fieldCollection.data.size)
        assertFailsWith<UniqueViolationException> { fieldCollection.insert(listOf(UniqueFieldClass(5,5), UniqueFieldClass(1,1),)) }
        assertEquals(4, fieldCollection.data.size)
    }


    @Test
    fun testUniqueSet():Unit = runBlocking {

        assertEquals(0, setCollection.data.size)

        setCollection.insertOne(UniqueSetClass(1,1,1))
        assertEquals(1, setCollection.data.size)
        assertFailsWith<UniqueViolationException> { setCollection.insertOne(UniqueSetClass(1,1,1)) }
        assertEquals(1, setCollection.data.size)
        assertFailsWith<UniqueViolationException> { setCollection.insertOne(UniqueSetClass(2,1,1)) }
        assertEquals(1, setCollection.data.size)

        setCollection.insertOne(UniqueSetClass(3,1,2))
        setCollection.insertOne(UniqueSetClass(4,2,1))
        assertEquals(3, setCollection.data.size)

        assertFailsWith<UniqueViolationException> { setCollection.insertOne(UniqueSetClass(5,1,2)) }
        assertEquals(3, setCollection.data.size)
        assertFailsWith<UniqueViolationException> { setCollection.insertOne(UniqueSetClass(5,2,1)) }
        assertEquals(3, setCollection.data.size)

        setCollection.insert(listOf(UniqueSetClass(5,5,5), UniqueSetClass(6,6,6),))
        assertEquals(5, setCollection.data.size)

        assertFailsWith<UniqueViolationException> { setCollection.insert(listOf(UniqueSetClass(5,5,5), UniqueSetClass(6,6,6),)) }
        assertEquals(5, setCollection.data.size)
        assertFailsWith<UniqueViolationException> { setCollection.insert(listOf(UniqueSetClass(7,7,7), UniqueSetClass(1,1,1),)) }
        assertEquals(5, setCollection.data.size)

    }


    @Test
    fun testUniqueComboFields():Unit = runBlocking {

        assertEquals(0, comboCollection.data.size)

        comboCollection.insertOne(UniqueComboClass(1,1,1,1))
        assertEquals(1, comboCollection.data.size)
        assertFailsWith<UniqueViolationException> { comboCollection.insertOne(UniqueComboClass(1,1,1,1)) }
        assertEquals(1, comboCollection.data.size)
        assertFailsWith<UniqueViolationException> { comboCollection.insertOne(UniqueComboClass(2,1,1,1)) }
        assertEquals(1, comboCollection.data.size)
        assertFailsWith<UniqueViolationException> { comboCollection.insertOne(UniqueComboClass(1,2,1,1)) }
        assertEquals(1, comboCollection.data.size)
        assertFailsWith<UniqueViolationException> { comboCollection.insertOne(UniqueComboClass(1,1,1,2)) }
        assertEquals(1, comboCollection.data.size)

        comboCollection.insertOne(UniqueComboClass(2,2,2,2))
        comboCollection.insertOne(UniqueComboClass(3,3,1,2))
        comboCollection.insertOne(UniqueComboClass(4,4,2,1))
        comboCollection.insertOne(UniqueComboClass(1,5,3,3))
        assertEquals(5, comboCollection.data.size)

        comboCollection.insert(listOf(UniqueComboClass(6,6,6,6), UniqueComboClass(7,7,7,7),))
        assertEquals(7, comboCollection.data.size)

        assertFailsWith<UniqueViolationException> { comboCollection.insert(listOf(UniqueComboClass(6,6,6,6), UniqueComboClass(1,1,1,1),)) }
        assertEquals(7, comboCollection.data.size)
        assertFailsWith<UniqueViolationException> { comboCollection.insert(listOf(UniqueComboClass(8,8,8,8), UniqueComboClass(1,1,1,1),)) }
        assertEquals(7, comboCollection.data.size)

    }


    @Test
    fun testUniqueSetJank():Unit = runBlocking {

        assertEquals(0, setJankCollection.data.size)

        setJankCollection.insertOne(UniqueSetJankClass(1,1,1, 1, 1,))

        assertEquals(1, setJankCollection.data.size)
        assertFailsWith<UniqueViolationException> { setJankCollection.insertOne(UniqueSetJankClass(1,1,1, 1, 1)) }
        assertEquals(1, setJankCollection.data.size)
        assertFailsWith<UniqueViolationException> { setJankCollection.insertOne(UniqueSetJankClass(1,1,1, 2, 2)) }
        assertEquals(1, setJankCollection.data.size)
        assertFailsWith<UniqueViolationException> { setJankCollection.insertOne(UniqueSetJankClass(1,2,2, 1, 1)) }
        assertEquals(1, setJankCollection.data.size)
        assertFailsWith<UniqueViolationException> { setJankCollection.insertOne(UniqueSetJankClass(1,2,2, 1, 1)) }

        setJankCollection.insertOne(UniqueSetJankClass(1,1,2, 1, 2))
        setJankCollection.insertOne(UniqueSetJankClass(1,2,1, 2, 1))
        assertEquals(3, setJankCollection.data.size)


        setJankCollection.insert(listOf(UniqueSetJankClass(1,2,2, 2, 2), UniqueSetJankClass(1,3,3, 3, 3),))
        assertEquals(5, setJankCollection.data.size)

        assertFailsWith<UniqueViolationException> { setJankCollection.insert(listOf(UniqueSetJankClass(1,2,2, 2, 2), UniqueSetJankClass(1,3,3, 3, 3),)) }
        assertEquals(5, setJankCollection.data.size)
        assertFailsWith<UniqueViolationException> { setJankCollection.insert(listOf(UniqueSetJankClass(4,4,4, 4,4 ), UniqueSetJankClass(1,1,1, 1, 1),)) }
        assertEquals(5, setJankCollection.data.size)

    }


    @Test
    fun testUniqueComboFieldsJank():Unit = runBlocking {

        assertEquals(0, comboJankCollection.data.size)

        comboJankCollection.insertOne(UniqueComboJankClass(1,1,1,1,1,1))
        assertEquals(1, comboJankCollection.data.size)
        assertFailsWith<UniqueViolationException> { comboJankCollection.insertOne(UniqueComboJankClass(1,1,1,1,1,1)) }
        assertEquals(1, comboJankCollection.data.size)
        assertFailsWith<UniqueViolationException> { comboJankCollection.insertOne(UniqueComboJankClass(2,1,1,1,1,1)) }
        assertEquals(1, comboJankCollection.data.size)
        assertFailsWith<UniqueViolationException> { comboJankCollection.insertOne(UniqueComboJankClass(1,2,1,1,1,1)) }
        assertEquals(1, comboJankCollection.data.size)
        assertFailsWith<UniqueViolationException> { comboJankCollection.insertOne(UniqueComboJankClass(1,1,2,2,1,1)) }
        assertEquals(1, comboJankCollection.data.size)
        assertFailsWith<UniqueViolationException> { comboJankCollection.insertOne(UniqueComboJankClass(1,1,1,1,2,2)) }
        assertEquals(1, comboJankCollection.data.size)

        comboJankCollection.insertOne(UniqueComboJankClass(1,2,1,2,1,2))
        comboJankCollection.insertOne(UniqueComboJankClass(1,3,2,1,2,1))
        comboJankCollection.insertOne(UniqueComboJankClass(1,4,3,3,2,2))
        assertEquals(4, comboJankCollection.data.size)

        comboJankCollection.insert(listOf(UniqueComboJankClass(5,5,5,5,5,5), UniqueComboJankClass(6,6,6,6, 6, 6),))
        assertEquals(6, comboJankCollection.data.size)

        assertFailsWith<UniqueViolationException> { comboJankCollection.insert(listOf(UniqueComboJankClass(5,5,5,5,5,5), UniqueComboJankClass(6,6,6,6, 6, 6),)) }
        assertEquals(6, comboJankCollection.data.size)
        assertFailsWith<UniqueViolationException> { comboJankCollection.insert(listOf(UniqueComboJankClass(7,7,7,7, 7, 7), UniqueComboJankClass(1,1,1,1,1 ,1,),)) }
        assertEquals(6, comboJankCollection.data.size)
        assertFailsWith<UniqueViolationException> { comboJankCollection.insert(listOf(UniqueComboJankClass(7,1,8,8,8, 8), UniqueComboJankClass(9,9,9,9,9 ,9,),)) }
        assertEquals(6, comboJankCollection.data.size)

    }

}