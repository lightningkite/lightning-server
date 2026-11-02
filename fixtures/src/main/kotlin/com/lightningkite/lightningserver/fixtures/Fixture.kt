package com.lightningkite.lightningserver.fixtures

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.ListRegistryExtension
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/** One run of a [FixtureRegistry]: its config, and every fixture's result as it becomes available. */
public class FixtureCache<CONFIG>(public val config: CONFIG) {
    internal val cachedGenerations = ConcurrentHashMap<Fixture<*, *>, Deferred<*>>()
}

/**
 * A piece of generated data.  Invoking another fixture from [generate] waits for it and returns its
 * result, which is how fixtures are ordered: every fixture in a registry starts at once, and each
 * one blocks only on what it reads.
 */
public class Fixture<CONFIG : Any, T>(
    public val generate: suspend context(ServerRuntime) FixtureCache<CONFIG>.() -> T
) {
    context(cache: FixtureCache<CONFIG>)
    public suspend operator fun invoke(): T {
        @Suppress("UNCHECKED_CAST")
        val deferred = cache.cachedGenerations[this] as Deferred<T>?
            ?: throw IllegalStateException("Fixture is not part of the registry currently executing; it was never registered via fixture().")
        return deferred.await()
    }

    // Lazy, because on a multi-threaded dispatcher an eager fixture could run and invoke one that
    // isn't in the map yet.  FixtureRegistry.execute puts every fixture in the map before awaiting any.
    context(server: ServerRuntime, cache: FixtureCache<CONFIG>)
    internal fun start(scope: CoroutineScope): Deferred<T> {
        @Suppress("UNCHECKED_CAST")
        return cache.cachedGenerations.computeIfAbsent(this) {
            scope.async(start = CoroutineStart.LAZY) { cache.generate() }
        } as Deferred<T>
    }
}

/**
 * A set of [Fixture]s run together by [execute].  Declare it once, then register fixtures on it with
 * [fixture] or [seed] from any [ServerBuilder] in the server's tree.
 *
 * With [requireDebug], [execute] refuses to run unless `general.debug` is set.
 */
public class FixtureRegistry<CONFIG : Any>(public val requireDebug: Boolean = true) : ListRegistryExtension<Fixture<CONFIG, *>> {
    private val seedsClaimed = HashMap<Long, String>()

    // Two fixtures sharing a seed draw the same random stream, which looks like a coincidence rather
    // than a bug (rows that line up, or two fixtures generating the same ids).  Claims happen while
    // the server is being defined, so this fails at startup rather than partway through a seed.
    internal fun claimSeed(seedMix: Long, describe: String) {
        seedsClaimed.put(seedMix, describe)?.let { existing ->
            throw IllegalStateException(
                "$describe and $existing would generate from the same seed. Give one of them its own " +
                    "description: seed(table, \"what makes this one different\") { ... }"
            )
        }
    }

    context(runtime: ServerRuntime)
    public suspend fun execute(config: CONFIG) {
        if (requireDebug && !generalSettings().debug) throw IllegalStateException("This fixture registry only runs when general.debug is set.")
        val cache = FixtureCache(config)
        with(cache) {
            coroutineScope {
                runtime.server.extensions[this@FixtureRegistry]
                    ?.map { it.start(this@coroutineScope) }
                    ?.awaitAll()
            }
        }
    }
}

context(builder: ServerBuilder)
public fun <CONFIG : Any, T> FixtureRegistry<CONFIG>.fixture(
    generate: suspend context(ServerRuntime) FixtureCache<CONFIG>.() -> T
): Fixture<CONFIG, T> {
    val fixture = Fixture(generate)
    builder.extensions[this].register(fixture)
    return fixture
}
