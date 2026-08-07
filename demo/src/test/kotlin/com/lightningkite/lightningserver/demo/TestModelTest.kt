package com.lightningkite.lightningserver.demo

import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class TestModelTest {
    private val testModelTable = DatabaseTableDefinition<TestModel>()
    private val userTable = DatabaseTableDefinition<User>()


    @Test
    fun testModelCreation() = runBlocking {
        TestHelper.testServer {
            val model = TestModel(
                name = "Test Item",
                number = 42,
                content = "<p>Test content</p>",
                status = Status.DRAFT
            )

            assertEquals("Test Item", model.name)
            assertEquals(42, model.number)
            assertEquals(Status.DRAFT, model.status)
            assertNotNull(model._id)
        }
    }

    @Test
    fun testModelDefaults() = runBlocking {
        TestHelper.testServer {
            val model = TestModel()

            assertEquals("No Name", model.name)
            assertEquals(3123, model.number)
            assertEquals(Status.DRAFT, model.status)
            assertNotNull(model._id)
            assertNotNull(model.timestamp)
        }
    }

    @Test
    fun testDatabaseInsertAndRead() = runBlocking {
        TestHelper.testServer {
            val db = Server.database()
            val collection = db.table(testModelTable)

            val testItem = TestModel(
                name = "Database Test",
                number = 100,
                content = "Test content",
                status = Status.PUBLISHED
            )

            collection.insertOne(testItem)

            val retrieved = collection.get(testItem._id)
            assertNotNull(retrieved)
            assertEquals("Database Test", retrieved.name)
            assertEquals(100, retrieved.number)
            assertEquals(Status.PUBLISHED, retrieved.status)
        }
    }

    @Test
    fun testDatabaseQuery() = runBlocking {
        TestHelper.testServer {
            val db = Server.database()
            val collection = db.table(testModelTable)

            // Insert test data
            val item1 = TestModel(name = "Item 1", number = 1, status = Status.DRAFT)
            val item2 = TestModel(name = "Item 2", number = 2, status = Status.PUBLISHED)
            val item3 = TestModel(name = "Item 3", number = 3, status = Status.DRAFT)

            collection.insertOne(item1)
            collection.insertOne(item2)
            collection.insertOne(item3)

            // Query for draft items
            val drafts = collection.find(condition { it.status eq Status.DRAFT }).toList()
            assertTrue(drafts.size >= 2)
        }
    }

    @Test
    fun testDatabaseUpdate() = runBlocking {
        TestHelper.testServer {
            val db = Server.database()
            val collection = db.table(testModelTable)

            val testItem = TestModel(name = "Original Name", number = 50)
            collection.insertOne(testItem)

            collection.updateOne(
                condition { it._id eq testItem._id },
                modification { it.name assign "Updated Name" }
            )

            val updated = collection.get(testItem._id)
            assertNotNull(updated)
            assertEquals("Updated Name", updated.name)
            assertEquals(50, updated.number) // Should remain unchanged
        }
    }

    @Test
    fun testDatabaseDelete() = runBlocking {
        TestHelper.testServer {
            val db = Server.database()
            val collection = db.table(testModelTable)

            val testItem = TestModel(name = "To Delete", number = 999)
            collection.insertOne(testItem)

            val beforeDelete = collection.get(testItem._id)
            assertNotNull(beforeDelete)

            collection.deleteOne(condition { it._id eq testItem._id })

            val afterDelete = collection.get(testItem._id)
            assertNull(afterDelete)
        }
    }

    @Test
    fun testUserModel() = runBlocking {
        TestHelper.testServer {
            val user = User(
                email = "test@example.com",
                hashedPassword = "hashed_password",
                isSuperUser = false
            )

            assertEquals("test@example.com", user.email)
            assertNotNull(user._id)
        }
    }

    @Test
    fun testUserDatabaseOperations() = runBlocking {
        TestHelper.testServer {
            val db = Server.database()
            val users = db.table(userTable)

            val newUser = User(
                email = "newuser@example.com",
                hashedPassword = "password_hash"
            )

            users.insertOne(newUser)

            val found = users.findOne(condition { it.email eq "newuser@example.com" })
            assertNotNull(found)
            assertEquals("newuser@example.com", found.email)
        }
    }
}
