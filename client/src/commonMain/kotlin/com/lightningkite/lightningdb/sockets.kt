
package com.lightningkite.lightningdb

// import com.lightningkite.khrysalis.*
import com.lightningkite.rock.*
import com.lightningkite.rock.reactive.*

fun retryWebsocket(
    url: String
): RetryWebsocket {
    val connected = Property(false)
    var currentWebSocket: WebSocket? = null
    val onOpenList = ArrayList<()->Unit>()
    val onMessageList = ArrayList<(String)->Unit>()
    val onBinaryMessageList = ArrayList<(Blob)->Unit>()
    val onCloseList = ArrayList<(Short)->Unit>()
    fun reset() {
        currentWebSocket = websocket(url).also {
            onOpenList.forEach { l -> it.onOpen(l) }
            onMessageList.forEach { l -> it.onMessage(l) }
            onBinaryMessageList.forEach { l -> it.onBinaryMessage(l) }
            onCloseList.forEach { l -> it.onClose(l) }
        }
    }

    return object: RetryWebsocket {
        private val stayOpenP = ResourceUseImpl()
        override val stayOpen: ResourceUse = stayOpenP.use
        val scope = ReactiveScope {
            val shouldBeOn = stayOpenP.current
            val isOn = connected.current
            if(shouldBeOn && !isOn) {
                reset()
            } else if(!shouldBeOn && isOn) {
                currentWebSocket?.close(1000, "OK")
            }
        }

        override fun close(code: Short, reason: String) {
            scope.clearScopeListeners()
            currentWebSocket?.close(code, reason)
            currentWebSocket = null
        }

        override fun send(data: Blob) {
            currentWebSocket?.send(data)
        }

        override fun send(data: String) {
            currentWebSocket?.send(data)
        }

        override fun onOpen(action: ()->Unit) { onOpenList.add(action) }
        override fun onMessage(action: (String)->Unit) { onMessageList.add(action) }
        override fun onBinaryMessage(action: (Blob)->Unit) { onBinaryMessageList.add(action) }
        override fun onClose(action: (Short)->Unit) { onCloseList.add(action) }
    }
}

interface RetryWebsocket: WebSocket {
    val stayOpen: ResourceUse
}

/*
wrap Pinging atLeast WebSocket {
    override fun onMessage(action: (String)->Unit) { onMessageList.add(action) }
}

 */

class ResourceUseImpl(private val p: Property<Boolean> = Property(false)): Readable<Boolean> by p {
    var count = 0
    val use: ResourceUse = object: ResourceUse {
        override fun start(): () -> Unit {
            if(count++ == 0) p set true
            return {
                if(--count == 0) p set false
            }
        }
    }
}


val WebSocket.mostRecentMessage: Readable<String?> get() = object: Readable<String?> {
    override var once: String? = null
        private set

    val listeners = HashSet<()->Unit>()
    init {
        onMessage {
            once = it
            listeners.forEach { it() }
        }
    }

    override fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
}


fun sample(): SharedReadable<List<String>> {
    val ws = retryWebsocket("testurl")
    ws.onOpen { ws.send("BULLSHIT I SAY") }
    val fullList = ArrayList<String>()
    return SharedReadable {
        blockIfBackground()
        use(ws.stayOpen)
        ws.mostRecentMessage.current?.let {
            fullList.add(it)
        }
        fullList
    }
}


//var sharedSocketShouldBeActive: Observable<Boolean> = Observable.just(true)
//private var retryTime = 1000L
//private var lastRetry = 0L
//
//var _overrideWebSocketProvider: ((url: String) -> Observable<WebSocketInterface>)? = null
//private val sharedSocketCache = HashMap<String, Observable<WebSocketInterface>>()
//fun sharedSocket(url: String): Observable<WebSocketInterface> {
//    return sharedSocketCache.getOrPut(url) {
//        sharedSocketShouldBeActive
//            .distinctUntilChanged()
//            .switchMap {
//                val shortUrl = url.substringBefore('?')
//                if (!it) Observable.never<WebSocketInterface>()
//                else {
//                    println("Creating socket to $url")
//                    (_overrideWebSocketProvider?.invoke(url) ?: HttpClient.webSocket(url))
//                        .switchMap {
//                            lastRetry = System.currentTimeMillis()
////                            println("Connection to $shortUrl established, starting pings")
//                            // Only have this observable until it fails
//
//                            val pingMessages: Observable<WebSocketInterface> =
//                                Observable.interval(30_000L, TimeUnit.MILLISECONDS, HttpClient.responseScheduler!!)
//                                    .map { _ ->
////                                        println("Sending ping to $url")
//                                        it.write.onNext(WebSocketFrame(text = " "))
//                                    }.switchMap { Observable.never() }
//
//                            val timeoutAfterSeconds: Observable<WebSocketInterface> = it.read
//                                .doOnNext {
////                                    println("Got message from $shortUrl: ${it}")
//                                    if (System.currentTimeMillis() > lastRetry + 60_000L) {
//                                        retryTime = 1000L
//                                    }
//                                }
//                                .timeout(40_000L, TimeUnit.MILLISECONDS, HttpClient.responseScheduler!!)
//                                .switchMap { Observable.never() }
//
//                            Observable.merge(
//                                Observable.just(it),
//                                pingMessages,
//                                timeoutAfterSeconds
//                            )
//                        }
//                        .doOnError { println("Socket to $shortUrl FAILED with $it") }
//                        .retryWhen @SwiftReturnType("Observable<Error>") {
//                            val temp = retryTime
//                            retryTime = temp * 2L
//                            it.delay(temp, TimeUnit.MILLISECONDS, HttpClient.responseScheduler!!)
//                        }
//                        .doOnDispose {
//                            println("Disconnecting socket to $shortUrl")
//                        }
//                }
//            }
//            .replay(1)
//            .refCount()
//    }
//}
//
//class WebSocketIsh<IN: Any, OUT>(val messages: Observable<IN>, val send: (OUT) -> Unit)
//
//@JsName("multiplexedSocketReified")
//inline fun <reified IN, reified OUT> multiplexedSocket(
//    url: String,
//    path: String,
//    queryParams: Map<String, List<String>> = mapOf()
//): Observable<WebSocketIsh<IN, OUT>> = multiplexedSocket(url, path, queryParams, serializer<IN>(), serializer<OUT>())
//
//@JsName("multiplexedSocket")
//fun <IN, OUT> multiplexedSocket(
//    url: String,
//    path: String,
//    queryParams: Map<String, List<String>> = mapOf(),
//    inType: KSerializer<IN>,
//    outType: KSerializer<OUT>
//): Observable<WebSocketIsh<IN, OUT>> = multiplexedSocketRaw(url, path, queryParams)
//    .map {
//        WebSocketIsh(
//            messages = it.messages.mapNotNull { it.fromJsonString(inType) },
//            send = { m -> it.send(m.toJsonString(outType)) }
//        )
//    }
//
//fun multiplexedSocketRaw(
//    url: String,
//    path: String,
//    queryParams: Map<String, List<String>> = mapOf()
//): Observable<WebSocketIsh<String, String>> {
//    val shortUrl = url.substringBefore('?')
//    val channel = uuid().toString()
//    return sharedSocket(url)
//        .switchMap { sharedSocket ->
////            println("Setting up channel $channel to $shortUrl with $path")
//            val multiplexedIn = sharedSocket.read.mapNotNull {
//                val text = it.text ?: return@mapNotNull null
//                if (text.isBlank()) return@mapNotNull null
//                text.fromJsonString<MultiplexMessage>()
//            }.filter { it.channel == channel }
//            var current = PublishSubject.create<String>()
//            multiplexedIn
//                .mapNotNull { message ->
//                    when {
//                        message.start -> {
////                            println("Channel ${message.channel} established with $sharedSocket")
//                            WebSocketIsh<String, String>(
//                                messages = current,
//                                send = { message ->
////                                    println("Sending $message to $channel")
//                                    sharedSocket.write.onNext(
//                                        WebSocketFrame(
//                                            text = MultiplexMessage(
//                                                channel = channel,
//                                                data = message
//                                            ).toJsonString()
//                                        )
//                                    )
//                                }
//                            )
//                        }
//                        message.data != null -> {
////                            println("Got ${message.data} to ${message.channel}")
//                            current.onNext(message.data)
//                            null
//                        }
//                        message.end -> {
////                            println("Channel ${message.channel} terminated")
//                            current = PublishSubject.create()
//                            sharedSocket.write.onNext(
//                                WebSocketFrame(
//                                    text = MultiplexMessage(
//                                        channel = channel,
//                                        path = path,
//                                        queryParams = queryParams,
//                                        start = true
//                                    ).toJsonString()
//                                )
//                            )
//                            null
//                        }
//                        else -> null
//                    }
//                }
//                .doOnSubscribe { _ ->
////                    println("Sending onSubscribe Startup Message")
//                    sharedSocket.write.onNext(
//                        WebSocketFrame(
//                            text = MultiplexMessage(
//                                channel = channel,
//                                path = path,
//                                queryParams = queryParams,
//                                start = true
//                            ).toJsonString()
//                        )
//                    )
//                }
//                .doOnDispose {
////                    println("Disconnecting channel on socket to $shortUrl with $path")
//                    sharedSocket?.write?.onNext(
//                        WebSocketFrame(
//                            text = MultiplexMessage(
//                                channel = channel,
//                                path = path,
//                                end = true
//                            ).toJsonString()
//                        )
//                    )
//                }
//                .retryWhen @SwiftReturnType("Observable<Error>") {
//                    val temp = retryTime
//                    retryTime = temp * 2L
//                    it.delay(temp, TimeUnit.MILLISECONDS, HttpClient.responseScheduler!!)
//                }
//        }
//}
