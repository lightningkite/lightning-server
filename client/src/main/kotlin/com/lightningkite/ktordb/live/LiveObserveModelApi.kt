@file:SharedCode
package com.lightningkite.ktordb.live

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.ktordb.*
import com.lightningkite.ktordb.HasId
import com.lightningkite.ktordb.ListChange
import com.lightningkite.ktordb.Query
import io.reactivex.rxjava3.core.Observable

class LiveObserveModelApi<Model : HasId>(
    val openSocket: (query: Query<Model>) -> Observable<List<Model>>
) : ObserveModelApi<Model>() {

    companion object {
        inline fun <reified Model : HasId> create(
            multiplexUrl: String,
            token: String,
            headers: Map<String, String>,
            path: String
        ): LiveObserveModelApi<Model> = LiveObserveModelApi(
            openSocket = { query ->
                multiplexedSocket<ListChange<Model>, Query<Model>>(
                    "$multiplexUrl?jwt=$token",
                    path
                )
                    .switchMap {
                        it.send(query)
                        it.messages.onErrorResumeNext { Observable.never() }
                    }
                    .toListObservable(query.orderBy.comparator ?: compareBy { it._id })
            }
        )
    }

    val alreadyOpen = HashMap<Query<Model>, Observable<List<Model>>>()

    override fun observe(query: Query<Model>): Observable<List<Model>> {
        //multiplexedSocket<ListChange<Model>, Query<Model>>("$multiplexUrl?jwt=$token", path)
        return alreadyOpen.getOrPut(query) {
            openSocket(query)
                .doOnDispose { alreadyOpen.remove(query) }
                .replay(1)
                .refCount()
        }
    }
}

fun <T : HasId> Observable<ListChange<T>>.toListObservable(ordering: Comparator<T>): Observable<List<T>> {
    val localList = ArrayList<T>()
    return map {
        it.wholeList?.let { localList.clear(); localList.addAll(it.sortedWith(ordering)) }
        it.new?.let {
            localList.removeAll { o -> it._id == o._id }
            var index = localList.indexOfFirst { inList -> ordering.compare(it, inList) < 0 }
            if (index == -1) index = localList.size
            localList.add(index, it)
        } ?: it.old?.let { localList.removeAll { o -> it._id == o._id } }
        localList
    }
}