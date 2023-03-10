package com.lightningkite.lightningdb

import kotlinx.coroutines.flow.Flow


suspend fun <Model> Flow<Model>.collectChunked(chunkSize: Int, action: suspend (List<Model>) -> Unit) {
    val list = ArrayList<Model>()
    this.collect {
        list.add(it)
        if (list.size >= chunkSize) {
            action(list)
            list.clear()
        }
    }
    action(list)
}
