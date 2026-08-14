package com.lightningkite.lightningserver.testdata

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

public class Counter<K : Any>(private val initial: Long = 0L) {
    private val counters = ConcurrentHashMap<K, AtomicLong>()

    public fun get(key: K): Long = counters[key]?.get() ?: initial
    public fun increment(key: K): Long = counter(key).incrementAndGet()
    public fun decrement(key: K): Long = counter(key).decrementAndGet()
    public fun add(key: K, delta: Long): Long = counter(key).addAndGet(delta)
    public fun reset(key: K, newValue: Long = initial): Long = counter(key).getAndSet(newValue)
    public fun remove(key: K): Long? = counters.remove(key)?.get()
    public fun keys(): Set<K> = counters.keys.toSet()
    public fun snapshot(): Map<K, Long> = counters.mapValues { it.value.get() }

    private fun counter(key: K): AtomicLong = counters.computeIfAbsent(key) { AtomicLong(initial) }
}
