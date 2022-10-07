import com.fasterxml.jackson.databind.ser.Serializers
import com.lightningkite.lightningdb.Query
import com.lightningkite.lightningdb.live.LiveObserveModelApi
import com.lightningkite.lightningserver.core.ContentType.Application.Json
import com.lightningkite.lightningserver.demo.TestModel
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.rx.okhttp.HttpClient
import com.lightningkite.rx.okhttp.defaultJsonMapper
import io.reactivex.rxjava3.kotlin.blockingSubscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import org.junit.Assert.*
import org.junit.Test

class ServerTest {
    @Test fun test() {
//        defaultJsonMapper = Serialization.Internal.json
//        val m = LiveObserveModelApi.create<TestModel>(
//            multiplexUrl = "wss://ws.example.demo.ivieleague.com?path=multiplex",
//            token = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJodHRwczovL2V4YW1wbGUuZGVtby5pdmllbGVhZ3VlLmNvbSIsInN1YiI6Ijc4ZDBmNWI5LTZkMTgtNGYwMC05ZjZhLWYyMjk0YTY0OTQ3OCIsImF1ZCI6Imh0dHBzOi8vZXhhbXBsZS5kZW1vLml2aWVsZWFndWUuY29tIiwiZXhwIjoxNjk1MzM4MjU0LCJpYXQiOjE2NjM4MDIyNTR9.5tKOXfNCgiFVcrd946e0rhBh853kedzNJvqjtlJDTCg=",
//            headers = mapOf(),
//            path = "test-model/rest"
//        )
//        HttpClient.ioScheduler = Schedulers.io()
//        HttpClient.responseScheduler = Schedulers.computation()
//        m.observe(Query())
//            .blockingSubscribeBy(
//                onError = { it.printStackTrace() },
//                onNext = {
//                    println(it)
//                }
//            )
    }
}