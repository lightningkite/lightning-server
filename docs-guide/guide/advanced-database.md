# Advanced database

Sorting, paging, aggregates, indexes, text search, bulk and conditional writes, and running code when a table
changes.  Read [Database](database.md) first; this chapter uses the same imports.

## Example model

<!-- sample: com/lightningkite/lightningserver/guide/samples/AdvancedDatabaseSamples.kt#adv-model -->
```kotlin
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
```

The functions below take a `Table<Product>`; in a server, that's `products()` from `registerTable` or
`info.table(...)` from a [model info](model-rest.md).

## Sorting and paging

<!-- sample: com/lightningkite/lightningserver/guide/samples/AdvancedDatabaseSamples.kt#adv-sort-page -->
```kotlin
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
```

`sort { }` builds a `List<SortPart<T>>`; `SortPart(field, ascending = true, ignoreCase = false)` is the same thing
written directly.  `Query` bundles condition, sort, skip, and limit into one serializable value (its `limit`
defaults to 100); it's what clients send to the generated `query` endpoint.

Sort order is the same on every backend: nulls sort before every other value, and strings compare by code point
(`"Ab"` before `"aa"`) unless `ignoreCase` is set.  `skip` gets slower as it grows on most backends; for deep paging,
filter on the last value you saw instead (`Product.price lt lastPrice`).

`find` also takes `maxQueryMs` (default 15 seconds), after which the query is cancelled.

## Aggregates

<!-- sample: com/lightningkite/lightningserver/guide/samples/AdvancedDatabaseSamples.kt#adv-aggregate -->
```kotlin
suspend fun toolCount(products: Table<Product>): Int =
    products.count(Product.category eq "tools")

suspend fun productsPerCategory(products: Table<Product>): Map<String, Int> =
    products.groupCount(groupBy = Product.category)

suspend fun averagePrice(products: Table<Product>): Double? =
    products.aggregate(Aggregate.Average, property = Product.price)

suspend fun stockPerCategory(products: Table<Product>): Map<String, Double?> =
    products.groupAggregate(Aggregate.Sum, groupBy = Product.category, property = Product.stock)
```

Each takes an optional `condition` (default `Condition.Always`).  `Aggregate` is `Sum`, `Average`,
`StandardDeviationSample`, or `StandardDeviationPopulation`.  `aggregate` returns `null` when no rows match.

## Indexes

Indexes are declared on the model and created by the table's pre-deploy task:

| Annotation | Target | Effect |
|---|---|---|
| `@Index` | property | single-field index |
| `@Index(IndexUniqueness.Unique)` | property | unique; a duplicate insert or update throws `UniqueViolationException` |
| `@Index(IndexUniqueness.UniqueNullSparse)` | property | unique among non-null values |
| `@IndexSet(["a", "b"])` | class, repeatable | compound index; takes `unique` like `@Index` |
| `@TextIndex(["a", "b"])` | class | full-text search over these string fields |

Field names in `@IndexSet` and `@TextIndex` are serial names, which match the property names unless you use
`@SerialName`.  The `ram` backend enforces unique indexes too, so tests catch violations.
[Generated REST endpoints](model-rest.md) turn `UniqueViolationException` into a 400 with detail `unique`.

## Text search

<!-- sample: com/lightningkite/lightningserver/guide/samples/AdvancedDatabaseSamples.kt#adv-text -->
```kotlin
suspend fun searchProducts(products: Table<Product>, text: String, maxPrice: Double): List<Product> =
    products.findByTextIndex(text, condition = Product.price lte maxPrice, limit = 20)
        .map { it.model }
        .toList()
```

`findByTextIndex` searches the model's `@TextIndex` fields and returns `ScoredResult`s (`model`, `score`), most
relevant first.  The query uses search-box syntax: every word must match, `"quoted phrases"` match exactly, `-word`
excludes.  It ignores sort order, and it throws if the model has no `@TextIndex`.  Stemming and typo tolerance vary
by backend.  Text search is not a `Condition`, so it can't be combined with `or`.

## Bulk writes

<!-- sample: com/lightningkite/lightningserver/guide/samples/AdvancedDatabaseSamples.kt#adv-bulk -->
```kotlin
suspend fun discountCategory(products: Table<Product>, category: String): Int =
    products.updateManyIgnoringResult(Product.category eq category, Modification { it.price *= 0.9 })

suspend fun restockLowItems(products: Table<Product>): CollectionChanges<Product> =
    products.updateMany(Product.stock lt 5, Modification { it.stock += 10 })

suspend fun removeDiscontinued(products: Table<Product>, skus: Set<String>): Int =
    products.deleteManyIgnoringOld(Product.sku inside skus)
```

`CollectionChanges.changes` is a list of `EntryChange(old, new)`.  Bulk writes are not atomic as a whole: a
concurrent reader can see some rows changed and others not, and a failure part-way leaves the earlier rows changed.

## Upsert

<!-- sample: com/lightningkite/lightningserver/guide/samples/AdvancedDatabaseSamples.kt#adv-upsert -->
```kotlin
suspend fun receiveShipment(products: Table<Product>, incoming: Product): EntryChange<Product> =
    products.upsertOne(
        condition = Product.sku eq incoming.sku,
        modification = Modification { it.stock += incoming.stock },
        model = incoming,
    )
```

If a row matches, the modification is applied; otherwise `model` is inserted.  `old == null` in the result means
it inserted.  `upsertOneById(id, model)` replaces or inserts by id.  Upserts are not covered by the row-level
atomicity guarantee: backends differ in how they resolve two concurrent upserts of the same key, so back the
condition with a unique index.

## Compare-and-swap

<!-- sample: com/lightningkite/lightningserver/guide/samples/AdvancedDatabaseSamples.kt#adv-cas -->
```kotlin
suspend fun reserveOne(products: Table<Product>, id: Uuid): Boolean =
    products.updateOne(
        condition = (Product._id eq id) and (Product.stock gt 0),
        modification = Modification { it.stock += -1 },
    ).old != null
```

`updateOne` is atomic for its row and returns the row as it was before the change, so it's the primitive for
claims, counters with limits, and optimistic concurrency: put the state you expect in the condition, and a
non-null `old` means you won.  Don't use `deleteMany` to claim work; two callers can both be handed the same row.

## Signals and interceptors

Wrap a table to run code around writes.  With a model info, put the wrapping in `signals`, so it applies to every
write through `info.table()` and `info.table(auth)`, including the generated REST endpoints:

<!-- sample: com/lightningkite/lightningserver/guide/samples/AdvancedDatabaseSamples.kt#adv-signals -->
```kotlin
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
```

| Wrapper | Runs |
|---|---|
| `interceptCreate { model -> model }` | before `insert` and the insert half of an upsert; returns the model to store |
| `interceptCreates { models -> models }` | the same, for the whole batch |
| `interceptReplace { model -> model }` | before replaces |
| `interceptModification { mod -> mod }` | before updates and upserts; returns the modification to apply |
| `interceptChange { mod -> mod }` | before every insert, replace, upsert, and update, as a modification |
| `interceptChangePerInstance { ... }` | like `interceptChange`, per affected row |
| `interceptDelete { model -> }` | before each delete, with the row about to be removed |
| `postCreate { model -> }` | after an insert, or an upsert that inserted |
| `postChange { old, new -> }` | after an update or replace of an existing row |
| `postDelete { model -> }` | after each delete |
| `postNewValue { model -> }` | after any write that leaves a new value |
| `postRawChanges { changes -> }` | after every write, with its `EntryChange`s |
| `withChangeListener { changes -> }` | after every write, with a `CollectionChanges` |

Things to know:

- **Signals see only writes through the model info.**  `registration()` / `info.dangerouslyDirectTable()` and
  `info.baseTable()` skip them.
- **`post...` wrappers need the changed rows**, so they turn `...IgnoringResult` calls into the full versions.
  That costs a read per write; keep them off hot tables you don't need them on.
- **`interceptDelete` is not atomic**: it finds the rows first, then deletes them by id.
- Post-signals run after the write, in the same request; an exception from one fails the request but does not
  undo the write.  Long work belongs in a [task](tasks.md).
- Live websocket updates use the same mechanism; see [Realtime model updates](model-realtime.md).

## Next

- [Model REST endpoints](model-rest.md): expose these operations over HTTP.
- [Permissions](permissions.md): restrict what each caller can see and change.
- [Tasks](tasks.md): pre-deploy tasks (which prepare tables and indexes) and background work.
