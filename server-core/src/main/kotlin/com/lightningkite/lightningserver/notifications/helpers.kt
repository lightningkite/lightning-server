package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.exceptions.exceptionSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource


internal object NotificationSystemUtils {
    val logger: Logger = LoggerFactory.getLogger("com.lightningkite.lightningserver.notifications")

    object IdGenerator {
        var length = 3
        val characters = ('A'..'Z').toList()
        private val random = Random(123456)
        fun generate(length: Int = IdGenerator.length): String {
            return String(CharArray(length) { characters[random.nextInt(characters.size)] })
        }
    }

    suspend fun <A, T> runAsyncMap(threads: Int, list: List<A>, action: suspend (A) -> T): List<T> =
        coroutineScope {
            val queue = ConcurrentLinkedQueue(list)
            (1..threads)
                .map { _ ->
                    async {
                        val results = mutableListOf<T>()
                        while (!queue.isEmpty()) {
                            val item = queue.poll() ?: continue
                            results.add(action(item))
                        }
                        results
                    }
                }
                .flatMap { it.await() }
        }


    suspend fun <A> runAsync(threads: Int, list: List<A>, action: suspend (A) -> Unit): Unit =
        coroutineScope {
            val queue = ConcurrentLinkedQueue(list)
            (1..threads)
                .map { _ ->
                    async {
                        while (!queue.isEmpty()) {
                            val item = queue.poll() ?: continue
                            action(item)
                        }
                    }
                }
                .map { it.await() }
        }

    suspend fun <T> runFor(seconds: Int, startingValue: T, action: suspend (T) -> T?):T?{

        val loopStart = TimeSource.Monotonic.markNow()
        val duration = seconds.seconds

        var value = startingValue

        while (loopStart.elapsedNow() < duration) {
            value = action(value) ?: return null
        }

        return value
    }

    suspend fun <T> runForEach(seconds: Int, items: Collection<T>, action: suspend (T)->Unit): List<T> {
        val loopStart = TimeSource.Monotonic.markNow()
        val duration = seconds.seconds

        val remaining = items.toMutableList()
        while (loopStart.elapsedNow() < duration && remaining.isNotEmpty()) {
            try {
                action(remaining.removeFirst())
            }
            catch (e: Throwable) {
                exceptionSettings().report(e, "Exception encountered in runForEach")
            }
        }

        return remaining
    }
}