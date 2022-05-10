package com.lightningkite.ktordb

import com.lightningkite.ktordb.MultiplexMessage
import com.lightningkite.ktordb.live._overrideWebSocketProvider
import com.lightningkite.ktordb.live.multiplexedSocket
import com.lightningkite.rx.okhttp.HttpClient
import com.lightningkite.rx.okhttp.WebSocketFrame
import com.lightningkite.rx.okhttp.WebSocketInterface
import com.lightningkite.rx.okhttp.fromJsonString
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import org.junit.Test
import kotlin.test.assertEquals

class SocketsTest : TestWithTime() {

    class MockSocket() : WebSocketInterface {
        val _ownConnection: BehaviorSubject<WebSocketInterface> = BehaviorSubject.createDefault(this)
        override val ownConnection = _ownConnection.let { HttpClient.threadCorrectly(it) }
        val _read: PublishSubject<WebSocketFrame> = PublishSubject.create()
        override val read = _read.let { HttpClient.threadCorrectly(it) }
        override val write = PublishSubject.create<WebSocketFrame>()
    }

    @Test
    fun testBasics() {
        try {
            val mockSocket = MockSocket()
            _overrideWebSocketProvider = { mockSocket.ownConnection }

            val outLog = ArrayList<WebSocketFrame>()
            mockSocket.write.subscribeBy(onNext = {
                println("<- $it")
                outLog.add(it)
                if(it.text == "") {
                    HttpClient.responseScheduler!!.scheduleDirect {
                        mockSocket._read.onNext(WebSocketFrame(text = ""))
                    }
                }
            }, onError = {}, onComplete = {})
            val inLog = ArrayList<WebSocketFrame>()
            mockSocket.read.subscribeBy(onNext = {
                println("-> $it")
                inLog.add(it)
            }, onError = {}, onComplete = {})

            val socketA = multiplexedSocket<Int, Int>("https://example.com", "testPath")
            advanceTime(1000L)
            assertEquals(0, outLog.size)
            val socketASub = socketA.subscribe()
            advanceTime(1000L)
            assertEquals(1, outLog.size)
            run {
                val registerMessage = outLog.last().text!!.fromJsonString<MultiplexMessage>()!!
                assertEquals("testPath", registerMessage.path)
                assertEquals(true, registerMessage.start)
            }
            advanceTime(10_000L)
            socketASub.dispose()
            advanceTime(1_000L)
            run {
                val registerMessage = outLog.last().text!!.fromJsonString<MultiplexMessage>()!!
                assertEquals("testPath", registerMessage.path)
                assertEquals(true, registerMessage.end)
            }
        } finally {
            _overrideWebSocketProvider = null
        }
    }

    @Test
    fun withRealSocket() {

    }
}

