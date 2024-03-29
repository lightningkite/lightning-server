@file:SharedCode
package com.lightningkite.lightningdb.live

import com.lightningkite.khrysalis.*
import com.lightningkite.lightningdb.MultiplexMessage
import com.lightningkite.rx.mapNotNull
import com.lightningkite.rx.okhttp.*
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import java.util.*
import java.util.concurrent.TimeUnit


var sharedSocketShouldBeActive: Observable<Boolean> = Observable.just(true)
private var retryTime = 1000L
private var lastRetry = 0L

var _overrideWebSocketProvider: ((url: String) -> Observable<WebSocketInterface>)? = null
private val sharedSocketCache = HashMap<String, Observable<WebSocketInterface>>()
fun sharedSocket(url: String): Observable<WebSocketInterface> {
    return sharedSocketCache.getOrPut(url) {
        sharedSocketShouldBeActive
            .distinctUntilChanged()
            .switchMap {
                val shortUrl = url.substringBefore('?')
                if (!it) Observable.never<WebSocketInterface>()
                else {
                    println("Creating socket to $url")
                    (_overrideWebSocketProvider?.invoke(url) ?: HttpClient.webSocket(url))
                        .switchMap {
                            lastRetry = System.currentTimeMillis()
//                            println("Connection to $shortUrl established, starting pings")
                            // Only have this observable until it fails

                            val pingMessages: Observable<WebSocketInterface> =
                                Observable.interval(30_000L, TimeUnit.MILLISECONDS, HttpClient.responseScheduler!!)
                                    .map { _ ->
//                                        println("Sending ping to $url")
                                        it.write.onNext(WebSocketFrame(text = " "))
                                    }.switchMap { Observable.never() }

                            val timeoutAfterSeconds: Observable<WebSocketInterface> = it.read
                                .doOnNext {
//                                    println("Got message from $shortUrl: ${it}")
                                    if (System.currentTimeMillis() > lastRetry + 60_000L) {
                                        retryTime = 1000L
                                    }
                                }
                                .timeout(40_000L, TimeUnit.MILLISECONDS, HttpClient.responseScheduler!!)
                                .switchMap { Observable.never() }

                            Observable.merge(
                                Observable.just(it),
                                pingMessages,
                                timeoutAfterSeconds
                            )
                        }
                        .doOnError { println("Socket to $shortUrl FAILED with $it") }
                        .retryWhen @SwiftReturnType("Observable<Error>") {
                            val temp = retryTime
                            retryTime = temp * 2L
                            it.delay(temp, TimeUnit.MILLISECONDS, HttpClient.responseScheduler!!)
                        }
                        .doOnDispose {
                            println("Disconnecting socket to $shortUrl")
                        }
                }
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
    return sharedSocket(url)
        .switchMap { sharedSocket ->
//            println("Setting up channel $channel to $shortUrl with $path")
            val multiplexedIn = sharedSocket.read.mapNotNull {
                val text = it.text ?: return@mapNotNull null
                if (text.isBlank()) return@mapNotNull null
                text.fromJsonString<MultiplexMessage>()
            }.filter { it.channel == channel }
            var current: PublishSubject<String> = PublishSubject.create()
            multiplexedIn
                .mapNotNull { message ->
                    when {
                        message.start -> {
//                            println("Channel ${message.channel} established with $sharedSocket")
                            WebSocketIsh<String, String>(
                                messages = current,
                                send = { message ->
//                                    println("Sending $message to $channel")
                                    sharedSocket.write.onNext(
                                        WebSocketFrame(
                                            text = MultiplexMessage(
                                                channel = channel,
                                                data = message
                                            ).toJsonString()
                                        )
                                    )
                                }
                            )
                        }
                        message.data != null -> {
//                            println("Got ${message.data} to ${message.channel}")
                            current.onNext(message.data!!)
                            null
                        }
                        message.end -> {
//                            println("Channel ${message.channel} terminated")
                            current = PublishSubject.create()
                            sharedSocket.write.onNext(
                                WebSocketFrame(
                                    text = MultiplexMessage(
                                        channel = channel,
                                        path = path,
                                        queryParams = queryParams,
                                        start = true
                                    ).toJsonString()
                                )
                            )
                            null
                        }
                        else -> null
                    }
                }
                .doOnSubscribe { _ ->
//                    println("Sending onSubscribe Startup Message")
                    sharedSocket.write.onNext(
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
                .doOnDispose {
//                    println("Disconnecting channel on socket to $shortUrl with $path")
                    sharedSocket.write.onNext(
                        WebSocketFrame(
                            text = MultiplexMessage(
                                channel = channel,
                                path = path,
                                end = true
                            ).toJsonString()
                        )
                    )
                }
                .retryWhen @SwiftReturnType("Observable<Error>") {
                    val temp = retryTime
                    retryTime = temp * 2L
                    it.delay(temp, TimeUnit.MILLISECONDS, HttpClient.responseScheduler!!)
                }
        }
}
