package com.lightningkite.lightningserver.typed

import kotlinx.serialization.KSerializer


public interface Fetcher {
    public fun withHeaderCalculator(calculator: suspend () -> List<Pair<String, String>>): Fetcher

//    public suspend operator fun <I, O> invoke(
//        url: String,
//        method: HttpMethod,
//        inSerializer: KSerializer<I>,
//        body: I,
//        outSerializer: KSerializer<O>
//    ): O
//
//    public fun <I, O> websocket(url: String, inSerializer: KSerializer<I>, outSerializer: KSerializer<O>): TypedWebSocket<I, O>
}
