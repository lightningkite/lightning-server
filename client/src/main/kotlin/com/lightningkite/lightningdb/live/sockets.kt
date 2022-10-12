@file:SharedCode
package com.lightningkite.lightningdb.live

import com.lightningkite.khrysalis.*
import com.lightningkite.lightningdb.MultiplexMessage
import com.lightningkite.rx.mapNotNull
import com.lightningkite.rx.okhttp.*
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.serializer
import java.lang.IllegalStateException
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap
import java.util.*

var _overrideWebSocketProvider: ((url: String) -> Observable<WebSocketInterface>)? = null
private val sharedSocketCache = HashMap<String, Observable<WebSocketInterface>>()
fun sharedSocket(url: String): Observable<WebSocketInterface> {
    return sharedSocketCache.getOrPut(url) {
        val shortUrl = url.substringBefore('?')
        println("Creating socket to $url")
        (_overrideWebSocketProvider?.invoke(url) ?: HttpClient.webSocket(url))
            .switchMap {
                println("Connection to $shortUrl established, starting pings")
                // Only have this observable until it fails

                val pingMessages: Observable<WebSocketInterface> = Observable.interval(30_000L, TimeUnit.MILLISECONDS, HttpClient.responseScheduler!!).map { _ ->
                    println("Sending ping to $url")
                    it.write.onNext(WebSocketFrame(text = " "))
                }.switchMap { Observable.never() }

                val timeoutAfterSeconds: Observable<WebSocketInterface> = it.read
                    .doOnNext { println("Got message from $shortUrl: ${it}") }
                    .timeout(60_000L, TimeUnit.MILLISECONDS, HttpClient.responseScheduler!!)
                    .switchMap { Observable.never() }

                Observable.merge(
                    Observable.just(it),
                    pingMessages,
                    timeoutAfterSeconds
                )
            }
            .doOnError { println("Socket to $shortUrl FAILED with $it") }
            .doOnComplete {
                println("Disconnecting socket to $shortUrl")
                sharedSocketCache.remove(url)
            }
            .replay(1)
            .refCount()
    }
}

class WebSocketIsh<IN: Any, OUT>(val messages: Observable<IN>, val send: (OUT) -> Unit)

@JsName("multiplexedSocketReified")
inline fun <reified IN: IsCodableAndHashableNotNull, reified OUT: IsCodableAndHashable> multiplexedSocket(
    url: String,
    path: String,
    queryParams: Map<String, List<String>> = mapOf()
): Observable<WebSocketIsh<IN, OUT>> = multiplexedSocket(url, path, queryParams, serializer<IN>(), serializer<OUT>())

@JsName("multiplexedSocket")
fun <IN: IsCodableAndHashableNotNull, OUT: IsCodableAndHashable> multiplexedSocket(
    url: String,
    path: String,
    queryParams: Map<String, List<String>> = mapOf(),
    inType: KSerializer<IN>,
    outType: KSerializer<OUT>
): Observable<WebSocketIsh<IN, OUT>> = multiplexedSocketRaw(url, path, queryParams)
    .map {
        WebSocketIsh(
            messages = it.messages.mapNotNull { it.fromJsonString(inType) },
            send = { m -> it.send(m.toJsonString(outType)) }
        )
    }
fun multiplexedSocketRaw(
    url: String,
    path: String,
    queryParams: Map<String, List<String>> = mapOf()
): Observable<WebSocketIsh<String, String>> {
    val shortUrl = url.substringBefore('?')
    val channel = UUID.randomUUID().toString()
    var lastSocket: WebSocketInterface? = null
    return sharedSocket(url)
        .switchMapSingle {
            println("Setting up socket to $shortUrl with $path")
            lastSocket = it
            val multiplexedIn = it.read.mapNotNull {
                val text = it.text ?: return@mapNotNull null
                if (text.isEmpty()) return@mapNotNull null
                text.fromJsonString<MultiplexMessage>()
            }
            multiplexedIn
                .filter { it.channel == channel && it.start }
                .firstOrError()
                .map { _ ->
                    println("Connected to channel $channel")
                    WebSocketIsh<String, String>(
                        messages = multiplexedIn.mapNotNull {
                            if(it.channel == channel) it.data else null
                        },
                        send = { message ->
                    println("Sending $message to $it")
                            it.write.onNext(WebSocketFrame(text = MultiplexMessage(channel = channel, data = message).toJsonString()))
                        }
                    )
                }
                .doOnSubscribe { _ ->
                    it.write.onNext(
                        WebSocketFrame(
                            text = MultiplexMessage(
                                channel = channel,
                                path = path,
                                queryParams = queryParams,
                                start = true
                            ).toJsonString()
                        )
                    )
                }
        }
        .doOnDispose {
            println("Disconnecting channel on socket to $shortUrl with $path")
            lastSocket?.write?.onNext(
                WebSocketFrame(
                    text = MultiplexMessage(
                        channel = channel,
                        path = path,
                        end = true
                    ).toJsonString()
                )
            )
        }
}
