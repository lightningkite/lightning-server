// by Claude
package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.uuid.Uuid

/**
 * Tests for ModelRestEndpoints - CRUD REST API generation.
 */
class ModelRestEndpointsTest {

    object CrudTestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val info = database.modelInfo<HasId<*>?, CrudItem, Uuid>(
            tableName = "CrudItem",
            auth = noAuth,
            permissions = { ModelPermissions.allowAll() }
        )
        val rest = path.path("items") include ModelRestEndpoints(info)
    }

    @Test
    fun insert_creates_a_new_item() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val item = CrudItem(name = "Test Item", category = "Electronics", price = 99.99, quantity = 10)
            val result = CrudTestServer.rest.insert.test(null, item)

            assertNotNull(result)
            assertEquals(item._id, result._id)
            assertEquals("Test Item", result.name)
            assertEquals("Electronics", result.category)
            assertEquals(99.99, result.price)
            assertEquals(10, result.quantity)
        }
    }

    @Test
    fun detail_returns_inserted_item() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val item = CrudItem(name = "Detail Test", category = "Books", price = 19.99, quantity = 5)
            CrudTestServer.rest.insert.test(null, item)

            val fetched = CrudTestServer.rest.detail.test(item._id, null, Unit)

            assertEquals(item._id, fetched._id)
            assertEquals("Detail Test", fetched.name)
            assertEquals("Books", fetched.category)
        }
    }

    @Test
    fun detail_throws_NotFoundException_for_nonexistent_item() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val nonexistentId = Uuid.random()

            assertFailsWith<NotFoundException>("Should throw NotFoundException") {
                CrudTestServer.rest.detail.test(nonexistentId, null, Unit)
            }
        }
    }

    @Test
    fun list_returns_all_items() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            // Clear any existing data
            CrudTestServer.info.table().deleteManyIgnoringOld(Condition.Always)

            // Insert multiple items
            val item1 = CrudItem(name = "Item 1", category = "A", price = 10.0, quantity = 1)
            val item2 = CrudItem(name = "Item 2", category = "B", price = 20.0, quantity = 2)
            val item3 = CrudItem(name = "Item 3", category = "A", price = 30.0, quantity = 3)

            CrudTestServer.rest.insert.test(null, item1)
            CrudTestServer.rest.insert.test(null, item2)
            CrudTestServer.rest.insert.test(null, item3)

            val results = CrudTestServer.rest.list.test(null, Query(Condition.Always))

            assertEquals(3, results.size)
            assertTrue(results.any { it._id == item1._id })
            assertTrue(results.any { it._id == item2._id })
            assertTrue(results.any { it._id == item3._id })
        }
    }

    @Test
    fun query_filters_by_condition() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val item1 = CrudItem(name = "Filtered 1", category = "CategoryA", price = 10.0, quantity = 1)
            val item2 = CrudItem(name = "Filtered 2", category = "CategoryB", price = 20.0, quantity = 2)
            val item3 = CrudItem(name = "Filtered 3", category = "CategoryA", price = 30.0, quantity = 3)

            CrudTestServer.rest.insert.test(null, item1)
            CrudTestServer.rest.insert.test(null, item2)
            CrudTestServer.rest.insert.test(null, item3)

            // Query for CategoryA only
            val results = CrudTestServer.rest.query.test(
                null, Query(
                condition = condition { it.category eq "CategoryA" }
            ))

            assertEquals(2, results.size)
            assertTrue(results.all { it.category == "CategoryA" })
        }
    }

    @Test
    fun query_with_limit_returns_limited_results() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            // Insert 5 items
            repeat(5) { i ->
                val item = CrudItem(name = "Limited $i", category = "Test", price = i * 10.0, quantity = i)
                CrudTestServer.rest.insert.test(null, item)
            }

            val results = CrudTestServer.rest.query.test(
                null, Query(
                    condition = Condition.Always,
                    limit = 2
                )
            )

            assertEquals(2, results.size)
        }
    }

    @Test
    fun insertBulk_creates_multiple_items() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val items = listOf(
                CrudItem(name = "Bulk 1", category = "Bulk", price = 10.0, quantity = 1),
                CrudItem(name = "Bulk 2", category = "Bulk", price = 20.0, quantity = 2),
                CrudItem(name = "Bulk 3", category = "Bulk", price = 30.0, quantity = 3)
            )

            val results = CrudTestServer.rest.insertBulk.test(null, items)

            assertEquals(3, results.size)
            assertEquals(items.map { it._id }, results.map { it._id })
        }
    }

    @Test
    fun replace_updates_existing_item() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val item = CrudItem(name = "Original", category = "Test", price = 10.0, quantity = 1)
            CrudTestServer.rest.insert.test(null, item)

            val updated = item.copy(name = "Replaced", price = 99.0)
            val result = CrudTestServer.rest.replace.test(item._id, null, updated)

            assertEquals("Replaced", result.name)
            assertEquals(99.0, result.price)

            // Verify in database
            val fetched = CrudTestServer.rest.detail.test(item._id, null, Unit)
            assertEquals("Replaced", fetched.name)
        }
    }

    @Test
    fun replace_throws_NotFoundException_for_nonexistent_item() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val nonexistentId = Uuid.random()
            val item = CrudItem(_id = nonexistentId, name = "New", category = "Test", price = 10.0, quantity = 1)

            assertFailsWith<NotFoundException>("Should throw NotFoundException for nonexistent item") {
                CrudTestServer.rest.replace.test(nonexistentId, null, item)
            }
        }
    }

    @Test
    fun modify_updates_item_partially() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val item = CrudItem(name = "ToModify", category = "Test", price = 50.0, quantity = 5)
            CrudTestServer.rest.insert.test(null, item)

            // Only modify the price
            val result = CrudTestServer.rest.modify.test(item._id, null, modification {
                it.price assign 75.0
            })

            assertEquals("ToModify", result.name) // Unchanged
            assertEquals(75.0, result.price) // Changed
            assertEquals(5, result.quantity) // Unchanged
        }
    }

    @Test
    fun modify_throws_NotFoundException_for_nonexistent_item() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val nonexistentId = Uuid.random()

            assertFailsWith<NotFoundException>("Should throw NotFoundException") {
                CrudTestServer.rest.modify.test(nonexistentId, null, modification {
                    it.name assign "Updated"
                })
            }
        }
    }

    @Test
    fun modifyWithDiff_returns_old_and_new_values() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val item = CrudItem(name = "DiffTest", category = "Test", price = 100.0, quantity = 10)
            CrudTestServer.rest.insert.test(null, item)

            val result = CrudTestServer.rest.modifyWithDiff.test(item._id, null, modification {
                it.name assign "DiffTestModified"
            })

            assertNotNull(result.old)
            assertNotNull(result.new)
            assertEquals("DiffTest", result.old?.name)
            assertEquals("DiffTestModified", result.new?.name)
        }
    }

    @Test
    fun deleteItem_removes_item() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val item = CrudItem(name = "ToDelete", category = "Test", price = 10.0, quantity = 1)
            CrudTestServer.rest.insert.test(null, item)

            // Verify it exists
            val fetched = CrudTestServer.rest.detail.test(item._id, null, Unit)
            assertEquals(item._id, fetched._id)

            // Delete it
            CrudTestServer.rest.deleteItem.test(item._id, null, Unit)

            // Verify it's gone
            assertFailsWith<NotFoundException>("Deleted item should not be found") {
                CrudTestServer.rest.detail.test(item._id, null, Unit)
            }
        }
    }

    @Test
    fun deleteItem_throws_NotFoundException_for_nonexistent_item() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val nonexistentId = Uuid.random()

            assertFailsWith<NotFoundException>("Should throw NotFoundException") {
                CrudTestServer.rest.deleteItem.test(nonexistentId, null, Unit)
            }
        }
    }

    @Test
    fun bulkDelete_removes_matching_items() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            // Clear any existing data
            CrudTestServer.info.table().deleteManyIgnoringOld(Condition.Always)

            // Insert items in different categories
            val item1 = CrudItem(name = "Keep 1", category = "Keep", price = 10.0, quantity = 1)
            val item2 = CrudItem(name = "Delete 1", category = "Delete", price = 20.0, quantity = 2)
            val item3 = CrudItem(name = "Delete 2", category = "Delete", price = 30.0, quantity = 3)
            val item4 = CrudItem(name = "Keep 2", category = "Keep", price = 40.0, quantity = 4)

            CrudTestServer.rest.insert.test(null, item1)
            CrudTestServer.rest.insert.test(null, item2)
            CrudTestServer.rest.insert.test(null, item3)
            CrudTestServer.rest.insert.test(null, item4)

            // Delete all items in "Delete" category
            val deletedCount = CrudTestServer.rest.bulkDelete.test(null, condition {
                it.category eq "Delete"
            })

            assertEquals(2, deletedCount)

            // Verify remaining items
            val remaining = CrudTestServer.rest.list.test(null, Query(Condition.Always))
            assertEquals(2, remaining.size)
            assertTrue(remaining.all { it.category == "Keep" })
        }
    }

    @Test
    fun bulkModify_updates_matching_items() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val item1 = CrudItem(name = "Modify 1", category = "ToModify", price = 10.0, quantity = 1)
            val item2 = CrudItem(name = "Modify 2", category = "ToModify", price = 20.0, quantity = 2)
            val item3 = CrudItem(name = "Keep", category = "Keep", price = 30.0, quantity = 3)

            CrudTestServer.rest.insert.test(null, item1)
            CrudTestServer.rest.insert.test(null, item2)
            CrudTestServer.rest.insert.test(null, item3)

            // Modify all items in "ToModify" category
            val modifiedCount = CrudTestServer.rest.bulkModify.test(
                null, MassModification(
                condition = condition { it.category eq "ToModify" },
                modification = modification { it.price assign 99.0 }
            ))

            assertEquals(2, modifiedCount)

            // Verify the modifications
            val modified = CrudTestServer.rest.query.test(
                null, Query(
                condition = condition { it.category eq "ToModify" }
            ))
            assertTrue(modified.all { it.price == 99.0 })

            // Verify unmodified item
            val kept = CrudTestServer.rest.detail.test(item3._id, null, Unit)
            assertEquals(30.0, kept.price)
        }
    }

    @Test
    fun count_returns_correct_count() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            // Clear any existing data
            CrudTestServer.info.table().deleteManyIgnoringOld(Condition.Always)

            // Insert items in different categories
            repeat(3) { CrudTestServer.rest.insert.test(null, CrudItem(category = "A")) }
            repeat(5) { CrudTestServer.rest.insert.test(null, CrudItem(category = "B")) }

            val countA = CrudTestServer.rest.count.test(null, condition { it.category eq "A" })
            val countB = CrudTestServer.rest.count.test(null, condition { it.category eq "B" })
            val countAll = CrudTestServer.rest.count.test(null, Condition.Always)

            assertEquals(3, countA)
            assertEquals(5, countB)
            assertEquals(8, countAll)
        }
    }

    @Test
    fun upsert_inserts_new_item() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val id = Uuid.random()
            val item = CrudItem(_id = id, name = "Upserted New", category = "Test", price = 50.0, quantity = 5)

            val result = CrudTestServer.rest.upsert.test(id, null, item)

            assertEquals(id, result._id)
            assertEquals("Upserted New", result.name)

            // Verify in database
            val fetched = CrudTestServer.rest.detail.test(id, null, Unit)
            assertEquals("Upserted New", fetched.name)
        }
    }

    @Test
    fun upsert_updates_existing_item() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val item = CrudItem(name = "Original", category = "Test", price = 10.0, quantity = 1)
            CrudTestServer.rest.insert.test(null, item)

            val updated = item.copy(name = "Upserted Updated", price = 100.0)
            val result = CrudTestServer.rest.upsert.test(item._id, null, updated)

            assertEquals(item._id, result._id)
            assertEquals("Upserted Updated", result.name)
            assertEquals(100.0, result.price)
        }
    }

    @Test
    fun bulkReplace_updates_multiple_items_by_ID() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val item1 = CrudItem(name = "BulkReplace 1", category = "Test", price = 10.0, quantity = 1)
            val item2 = CrudItem(name = "BulkReplace 2", category = "Test", price = 20.0, quantity = 2)

            CrudTestServer.rest.insert.test(null, item1)
            CrudTestServer.rest.insert.test(null, item2)

            val updated = listOf(
                item1.copy(name = "Updated 1", price = 100.0),
                item2.copy(name = "Updated 2", price = 200.0)
            )

            val results = CrudTestServer.rest.bulkReplace.test(null, updated)

            assertEquals(2, results.size)
            assertTrue(results.any { it.name == "Updated 1" && it.price == 100.0 })
            assertTrue(results.any { it.name == "Updated 2" && it.price == 200.0 })
        }
    }

    @Test
    fun permissions_returns_model_permissions() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val permissions = CrudTestServer.rest.permissions.test(null, Unit)

            assertNotNull(permissions)
            // With allowAll permissions, should have full access
            // All conditions should be Always, meaning full access
            assertTrue(permissions.create is Condition.Always)
            assertTrue(permissions.read is Condition.Always)
            assertTrue(permissions.update is Condition.Always)
            assertTrue(permissions.delete is Condition.Always)
        }
    }

    @Test
    fun query_with_skip_for_pagination() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            // Insert items
            repeat(10) { i ->
                CrudTestServer.rest.insert.test(null, CrudItem(name = "Item $i", price = (i + 1) * 10.0))
            }

            val page1 = CrudTestServer.rest.query.test(
                null, Query(
                    condition = Condition.Always,
                    skip = 0,
                    limit = 3
                )
            )

            val page2 = CrudTestServer.rest.query.test(
                null, Query(
                    condition = Condition.Always,
                    skip = 3,
                    limit = 3
                )
            )

            assertEquals(3, page1.size)
            assertEquals(3, page2.size)

            // Pages should have different items
            val page1Ids = page1.map { it._id }.toSet()
            val page2Ids = page2.map { it._id }.toSet()
            assertTrue(page1Ids.intersect(page2Ids).isEmpty())
        }
    }

    @Test
    fun empty_list_returns_empty_result() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            // Clear any existing data
            CrudTestServer.info.table().deleteManyIgnoringOld(Condition.Always)

            val results = CrudTestServer.rest.list.test(null, Query(Condition.Always))
            assertEquals(0, results.size)
        }
    }

    @Test
    fun query_with_Never_condition_returns_empty() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            CrudTestServer.rest.insert.test(null, CrudItem(name = "Test"))

            val results = CrudTestServer.rest.query.test(null, Query(Condition.Never))
            assertEquals(0, results.size)
        }
    }

    @Test
    fun count_with_Never_condition_returns_zero() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            CrudTestServer.rest.insert.test(null, CrudItem(name = "Test"))

            val count = CrudTestServer.rest.count.test(null, Condition.Never)
            assertEquals(0, count)
        }
    }

    // The endpoints below catch UniqueViolationException and rethrow BadRequestException(detail="unique"),
    // or throw NotFoundException; declaring those cases keeps the W6 "undeclared error" advisory quiet and
    // documents the errors. See ModelRestEndpoints implementations.
    private fun declaresUnique(handler: ApiHttpHandler<*, *, *, *>) =
        handler.errorCases.any { it.http == 400 && it.detail == "unique" }

    private fun declaresNotFound(handler: ApiHttpHandler<*, *, *, *>) =
        handler.errorCases.any { it.http == 404 }

    @Test
    fun endpoints_declare_the_errors_they_throw() {
        val rest = CrudTestServer.rest

        // Every endpoint that catches UniqueViolationException declares the 400 "unique" case.
        listOf(rest.insert, rest.insertBulk, rest.upsert, rest.bulkReplace, rest.replace,
            rest.bulkModify, rest.modifyWithDiff, rest.modify, rest.modifySimple).forEach {
            assertTrue(declaresUnique(it), "expected 400:unique in errorCases")
        }

        // Every endpoint that can throw NotFoundException declares the 404 case.
        listOf(rest.detail, rest.upsert, rest.replace, rest.modifyWithDiff, rest.modify,
            rest.modifySimple, rest.deleteItem).forEach {
            assertTrue(declaresNotFound(it), "expected 404 in errorCases")
        }

        // Read-only endpoints that never throw keep an empty error list.
        listOf(rest.list, rest.query, rest.count, rest.permissions).forEach {
            assertTrue(it.errorCases.isEmpty(), "read-only endpoint should declare no errors")
        }
    }

    @Test
    fun bulkDelete_with_Never_condition_deletes_nothing() = runBlocking {
        CrudTestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            // Clear any existing data
            CrudTestServer.info.table().deleteManyIgnoringOld(Condition.Always)

            CrudTestServer.rest.insert.test(null, CrudItem(name = "Test"))

            val deletedCount = CrudTestServer.rest.bulkDelete.test(null, Condition.Never)
            assertEquals(0, deletedCount)

            // Verify item still exists
            val remaining = CrudTestServer.rest.count.test(null, Condition.Always)
            assertEquals(1, remaining)
        }
    }
}

// Data class for CRUD tests - defined outside test class to avoid KSP conflicts
@Serializable
@GenerateDataClassPaths
data class CrudItem(
    override val _id: Uuid = Uuid.random(),
    val name: String = "",
    val category: String = "",
    val price: Double = 0.0,
    val quantity: Int = 0,
) : HasId<Uuid>
