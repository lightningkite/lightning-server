package com.lightningkite.lightningserver.security

import com.lightningkite.default
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.now
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Assert.*
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RateLimiterTest {
    @Test
    fun test() {
        runBlocking {
            val c = LocalCache()
            val limiter =
                RateLimiter(cache = { c }, perAuth = { _, _ -> 0.5f }, maxRequestTime = { _, _ -> 10.seconds })
            var time: Instant = Instant.fromEpochMilliseconds(0)
            val clock = object : Clock {
                override fun now(): Instant = time
            }
            try {
                Clock.default = clock


                for(i in 0..1000) {
                    try {
                        time += 2.seconds
                        limiter.gate(null, "A") { time += 4.seconds }
                    } catch(e: HttpStatusException) {
                        break
                    }
                }
                try {
                    repeat(10) {
                        time += 2.seconds
                        limiter.gate(null, "A") { time += 4.seconds }
                    }
                    fail()
                } catch(e: HttpStatusException) {
                    // OK!
                }
                time += 1.minutes
                for(i in 0..1000) {
                    try {
                        time += 2.seconds
                        limiter.gate(null, "A") { time += 4.seconds }
                    } catch(e: HttpStatusException) {
                        break
                    }
                }
                try {
                    repeat(10) {
                        time += 2.seconds
                        limiter.gate(null, "A") { time += 4.seconds }
                    }
                    fail()
                } catch(e: HttpStatusException) {
                    // OK!
                }


            } finally {
                Clock.default = Clock.System
            }
        }
    }
}