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

                val pingMessages: Observable<WebSocketInterface> = Observable.interval(5000L, TimeUnit.MILLISECONDS, HttpClient.responseScheduler!!).map { _ ->
                    println("Sending ping to $url")
                    it.write.onNext(WebSocketFrame(text = " "))
                }.switchMap { Observable.never() }

                val timeoutAfterSeconds: Observable<WebSocketInterface> = it.read
                    .doOnNext { println("Got message from $shortUrl: ${it}") }
                    .timeout(10_000L, TimeUnit.MILLISECONDS, HttpClient.responseScheduler!!)
                    .switchMap { Observable.never() }

                Observable.merge(
                    Observable.just(it),
                    pingMessages,
                    timeoutAfterSeconds
                )
            }
            .doOnError { println("Socket to $shortUrl FAILED with $it") }
            .retryWhen @SwiftReturnType("Observable<Error>") { it.delay(1000L, TimeUnit.MILLISECONDS, HttpClient.responseScheduler!!) }
            .doOnDispose {
                println("Disconnecting socket to $shortUrl")
                sharedSocketCache.remove(url)
            }
            .replay(1)
            .refCount()
    }
}

class MultiplexedWebsocketPart(val messages: Observable<String>, val send: (String) -> Unit)
class WebSocketIsh<IN: IsCodableAndHashable, OUT: IsCodableAndHashable>(val messages: Observable<IN>, val send: (OUT) -> Unit)

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
): Observable<WebSocketIsh<IN, OUT>> {
    val shortUrl = url.substringBefore('?')
    val channel = UUID.randomUUID().toString()
    var lastSocket: WebSocketInterface? = null
    return sharedSocket(url)
        .map {
//            println("Setting up socket to $shortUrl with $path")
            lastSocket = it
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
            val part = MultiplexedWebsocketPart(
                messages = it.read.mapNotNull {
                    val text = it.text ?: return@mapNotNull null
                    if (text == "") return@mapNotNull null
                    val message: MultiplexMessage = text.fromJsonString() ?: return@mapNotNull null
                    if(message.channel == channel) message.data else null
                },
                send = { message ->
                    it.write.onNext(WebSocketFrame(text = MultiplexMessage(channel = channel, data = message).toJsonString()))
                }
            )
            val typedPart = WebSocketIsh<IN, OUT>(
                messages = part.messages.mapNotNull { it.fromJsonString(inType) },
                send = { m -> part.send(m.toJsonString(outType)) }
            )
            typedPart
        }
        .doOnDispose {
//            println("Disconnecting channel on socket to $shortUrl with $path")
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
