package com.lightningkite.lightningserver.guide.samples

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.*

// region adv-model
@Queryable
@IndexSet(["category", "price"])
@TextIndex(["name", "description"])
data class Product(
    override val _id: Uuid = Uuid.random(),
    @Index(IndexUniqueness.Unique) val sku: String,
    val name: String,
    val description: String = "",
    val category: String,
    val price: Double,
    val stock: Int = 0,
) : HasId<Uuid>
// endregion adv-model

// region adv-sort-page
suspend fun productsInCategory(products: Table<Product>, category: String, page: Int): List<Product> =
    products.find(
        condition = Product.category eq category,
        orderBy = sort {
            it.price.descending()
            it.name.ascending(ignoreCase = true)
        },
        skip = page * 20,
        limit = 20,
    ).toList()

suspend fun cheapestFirst(products: Table<Product>): List<Product> =
    products.query(Query(orderBy = listOf(SortPart(Product.price)), limit = 10)).toList()
// endregion adv-sort-page

// region adv-aggregate
suspend fun toolCount(products: Table<Product>): Int =
    products.count(Product.category eq "tools")

suspend fun productsPerCategory(products: Table<Product>): Map<String, Int> =
    products.groupCount(groupBy = Product.category)

suspend fun averagePrice(products: Table<Product>): Double? =
    products.aggregate(Aggregate.Average, property = Product.price)

suspend fun stockPerCategory(products: Table<Product>): Map<String, Double?> =
    products.groupAggregate(Aggregate.Sum, groupBy = Product.category, property = Product.stock)
// endregion adv-aggregate

// region adv-text
suspend fun searchProducts(products: Table<Product>, text: String, maxPrice: Double): List<Product> =
    products.findByTextIndex(text, condition = Product.price lte maxPrice, limit = 20)
        .map { it.model }
        .toList()
// endregion adv-text

// region adv-bulk
suspend fun discountCategory(products: Table<Product>, category: String): Int =
    products.updateManyIgnoringResult(Product.category eq category, Modification { it.price *= 0.9 })

suspend fun restockLowItems(products: Table<Product>): CollectionChanges<Product> =
    products.updateMany(Product.stock lt 5, Modification { it.stock += 10 })

suspend fun removeDiscontinued(products: Table<Product>, skus: Set<String>): Int =
    products.deleteManyIgnoringOld(Product.sku inside skus)
// endregion adv-bulk

// region adv-upsert
suspend fun receiveShipment(products: Table<Product>, incoming: Product): EntryChange<Product> =
    products.upsertOne(
        condition = Product.sku eq incoming.sku,
        modification = Modification { it.stock += incoming.stock },
        model = incoming,
    )
// endregion adv-upsert

// region adv-cas
suspend fun reserveOne(products: Table<Product>, id: Uuid): Boolean =
    products.updateOne(
        condition = (Product._id eq id) and (Product.stock gt 0),
        modification = Modification { it.stock += -1 },
    ).old != null
// endregion adv-cas

@Queryable
data class StockAlert(
    override val _id: Uuid = Uuid.random(),
    val product: Uuid,
    val at: Instant,
) : HasId<Uuid>

// region adv-signals
object ShopServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val stockAlerts = database.registerTable<StockAlert>("StockAlert")

    val products = database.modelInfo<HasId<*>?, Product, Uuid>(
        auth = noAuth,
        tableName = "Product",
        signals = { table ->
            table
                .interceptCreate { it.copy(sku = it.sku.uppercase()) }
                .postChange { old, new ->
                    if (old.stock > 0 && new.stock == 0)
                        stockAlerts().insertOne(StockAlert(product = new._id, at = Clock.System.now()))
                }
        },
        permissions = { ModelPermissions.allowAll() },
    )
}
// endregion adv-signals

fun advancedDatabaseTest() = ShopServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    execute {
        val products = ShopServer.products.table()
        products.insert(
            listOf(
                Product(sku = "dr-1", name = "Cordless drill", category = "tools", price = 129.0, stock = 4),
                Product(sku = "dr-2", name = "Hammer drill", category = "tools", price = 249.0, stock = 1),
                Product(sku = "sa-1", name = "Hand saw", category = "tools", price = 25.0, stock = 10),
                Product(sku = "gl-1", name = "Work gloves", description = "Cut resistant", category = "apparel", price = 15.0, stock = 30),
            )
        )
        check(products.find(Condition.Always).toList().all { it.sku == it.sku.uppercase() })

        check(productsInCategory(products, "tools", 0).map { it.sku } == listOf("DR-2", "DR-1", "SA-1"))
        check(productsInCategory(products, "tools", 1).isEmpty())
        check(cheapestFirst(products).first().sku == "GL-1")

        check(toolCount(products) == 3)
        check(productsPerCategory(products) == mapOf("tools" to 3, "apparel" to 1))
        check(averagePrice(products) == (129.0 + 249.0 + 25.0 + 15.0) / 4)
        check(stockPerCategory(products)["tools"] == 15.0)

        check(searchProducts(products, "drill", maxPrice = 200.0).map { it.sku } == listOf("DR-1"))

        val saw = products.find(Product.sku eq "SA-1").first()
        check(reserveOne(products, saw._id))
        check(products.get(saw._id)!!.stock == 9)

        check(discountCategory(products, "apparel") == 1)
        val restocked = restockLowItems(products)
        check(restocked.changes.size == 2)
        check(restocked.changes.all { it.old!!.stock + 10 == it.new!!.stock })

        val shipment = receiveShipment(products, Product(sku = "SA-1", name = "Hand saw", category = "tools", price = 25.0, stock = 5))
        check(shipment.old != null && shipment.new!!.stock == 14)
        val newItem = receiveShipment(products, Product(sku = "ch-1", name = "Chisel", category = "tools", price = 9.0, stock = 5))
        check(newItem.old == null && newItem.new!!.sku == "CH-1")

        check(removeDiscontinued(products, setOf("CH-1")) == 1)

        val drill = products.find(Product.sku eq "DR-2").first()
        products.updateOneById(drill._id, Modification { it.stock assign 0 })
        check(ShopServer.stockAlerts().find(StockAlert.product eq drill._id).toList().size == 1)

        val duplicate = runCatching { products.insertOne(Product(sku = "SA-1", name = "Dup", category = "tools", price = 1.0)) }
        check(duplicate.exceptionOrNull() is UniqueViolationException)
    }
}
