package com.lightningkite.lightningdb

import kotlinx.coroutines.flow.Flow

/**
 * Will gather instances into a list of chunkSize before passing them into the action provided. If the flow contains less
 * than chunkSize elements it will pass the remaining elements into action.
 *
 * @param chunkSize The size of list you wish to operate on in the action.
 * @param action The action you wish to perform on a list of *Model* with size chunkSize
 */
suspend inline fun <Model> Flow<Model>.collectChunked(chunkSize: Int, crossinline action: (List<Model>) -> Unit) {
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
