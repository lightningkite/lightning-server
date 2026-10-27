package com.lightningkite.lightningserver.fixtures

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.DatabaseTableRegistration
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.HasId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger("com.lightningkite.lightningserver.fixtures.Seed")

/** The config of a [FixtureRegistry] whose fixtures use [seed]. */
public interface TestFixtureConfig {
    public val seed: Long
    /** How much test data to generate, in units the fixtures define.  Allows for larger scale testing. */
    public val scale: Double

    public fun faker(seedMix: Long): Faker = Faker(seed * 31 + seedMix)
    public fun faker(seedMix: Any): Faker = faker(seedMix.hashCode().toLong())
}

/**
 * A list that computes each element on demand, under a [Faker] derived from its index.
 *
 * An element is recomputed on every read, so a generator drawing from a shared Random would return a
 * different row each time: the insert would write one value and a later fixture reading the same
 * index would see another, leaving references to rows that were never stored.  The per-index Faker
 * makes reads stable and thread-safe, and the companion constructor requires one in context.
 */
public class LazyList<T> private constructor(
    override val size: Int,
    private val faker: Faker,
    private val calculate: context(Faker) (Int) -> T,
) : AbstractList<T>() {

    override fun get(index: Int): T = with(faker.faker(index)) { calculate(index) }

    public fun <R> map(transform: context(Faker) (T) -> R): LazyList<R> =
        LazyList(size, faker.faker(MAP)) { transform(this@LazyList[it]) }

    /**
     * Turns every element into [each] elements of a new list.  Return null for a slot that is not a
     * row and [seed] skips it, so irregular output can still be a list of known size.
     */
    public fun <R> expand(each: Int, generate: context(Faker) (T, Int) -> R): LazyList<R> =
        LazyList(size * each, faker.faker(EXPAND)) { generate(this@LazyList[it / each], it % each) }

    /** [expand] once per element of [others], handed the element rather than its index. */
    public fun <B, R> expand(others: List<B>, generate: context(Faker) (T, B) -> R): LazyList<R> =
        expand(others.size) { element, index -> generate(element, others[index]) }

    public companion object {
        // Derived lists draw from their own stream so a mapped value is not correlated with its source.
        private const val MAP = "map"
        private const val EXPAND = "expand"

        context(faker: Faker)
        public operator fun <T> invoke(size: Int, calculate: context(Faker) (Int) -> T): LazyList<T> =
            LazyList(size, faker, calculate)
    }
}

/** Seeds the table behind [info], with its table name as the seed. */
context(builder: ServerBuilder)
public fun <C : TestFixtureConfig, M : HasId<ID>, ID : Comparable<ID>, COLLECTION : Iterable<M?>> FixtureRegistry<C>.seed(
    info: ModelInfo<*, M, ID>,
    generate: suspend context(ServerRuntime, Faker) FixtureCache<C>.() -> COLLECTION
): Fixture<C, COLLECTION> = seed(info.registration, info.registration.tableDefinition.name, generate)

/**
 * Inserts the rows [generate] produces into [table], skipping nulls, and returns the collection so
 * later fixtures can build on it.
 *
 * [descriptionUsedForSeeding] is the seed, so a second fixture on the same table needs a different
 * one; the registry refuses a duplicate.
 */
context(builder: ServerBuilder)
public fun <C : TestFixtureConfig, M : HasId<ID>, ID : Comparable<ID>, COLLECTION : Iterable<M?>> FixtureRegistry<C>.seed(
    table: DatabaseTableRegistration<M>,
    descriptionUsedForSeeding: String,
    generate: suspend context(ServerRuntime, Faker) FixtureCache<C>.() -> COLLECTION
): Fixture<C, COLLECTION> {
    val seedMix = descriptionUsedForSeeding.hashCode().toLong()
    claimSeed(seedMix, "${table.tableDefinition.name} ($descriptionUsedForSeeding)")
    return fixture {
        val list = with(config.faker(seedMix)) { generate() }
        val started = TimeSource.Monotonic.markNow()
        var rows = 0
        list.asSequence().filterNotNull().chunked(500).forEach {
            rows += it.size
            table().insert(it)
        }
        logger.info { "Seeded ${table.tableDefinition.name}: $rows rows in ${started.elapsedNow()}" }
        list
    }
}

context(server: ServerRuntime, config: FixtureCache<CONFIG>)
public suspend operator fun <CONFIG : TestFixtureConfig, T> Fixture<CONFIG, List<T>>.get(index: Int): T = this()[index]
