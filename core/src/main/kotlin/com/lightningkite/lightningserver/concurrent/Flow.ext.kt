package com.lightningkite.lightningserver.concurrent

import kotlinx.coroutines.flow.Flow

public suspend fun <T, K, V, M : MutableMap<K, V>> Flow<T>.associateTo(map: M, transform: (T) -> Pair<K, V>): M {
    collect {
        val (k, v) = transform(it)
        map[k] = v
    }
    return map
}

public suspend fun <T, K, V> Flow<T>.associate(transform: (T) -> Pair<K, V>): Map<K, V> = associateTo(mutableMapOf(), transform)

public suspend fun <T, V, M : MutableMap<T, V>> Flow<T>.associateWithTo(map: M, getValue: (T) -> V): M {
    collect {
        map[it] = getValue(it)
    }
    return map
}

public suspend fun <T, V> Flow<T>.associateWith(getValue: (T) -> V): Map<T, V> = associateWithTo(mutableMapOf(), getValue)

public suspend fun <T, K, M : MutableMap<K, T>> Flow<T>.associateByTo(map: M, getKey: (T) -> K): M {
    collect {
        map[getKey(it)] = it
    }
    return map
}

public suspend fun <T, K> Flow<T>.associateBy(getValue: (T) -> K): Map<K, T> = associateByTo(mutableMapOf(), getValue)

public suspend fun <T, K, M : MutableMap<K, MutableList<T>>> Flow<T>.groupByTo(map: M, getKey: (T) -> K): M {
    collect {
        map.getOrPut(getKey(it), ::mutableListOf).add(it)
    }
    return map
}

public suspend fun <T, K> Flow<T>.groupBy(getKey: (T) -> K): Map<K, List<T>> = groupByTo(mutableMapOf(), getKey)
