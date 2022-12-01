import com.fasterxml.jackson.databind.ser.Serializers
import com.lightningkite.lightningdb.Query
import com.lightningkite.lightningdb.live.LiveObserveModelApi
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.core.ContentType.Application.Json
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.demo.Server
import com.lightningkite.lightningserver.demo.TestModel
import com.lightningkite.lightningserver.engine.Engine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.jsonschema.lightningServerSchema
import com.lightningkite.lightningserver.pubsub.LocalPubSub
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Task
import com.lightningkite.rx.okhttp.HttpClient
import com.lightningkite.rx.okhttp.defaultJsonMapper
import io.reactivex.rxjava3.kotlin.blockingSubscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.slf4j.LoggerFactory
import java.time.Duration

object TestSettings {
    init {
        Server
        Settings.populateDefaults(mapOf(
            Server.database.name to DatabaseSettings("ram"),
            generalSettings.name to GeneralServerSettings(
                debug = true
            )
        ))
        Server.files()
        engine = object: Engine {
            val cache = LocalCache
            val pubSub = LocalPubSub
            val logger = LoggerFactory.getLogger(this::class.java)
            override suspend fun sendWebSocketMessage(id: String, content: String): Boolean {
                logger.debug("Sending $content to $id")
                pubSub.string("ws-$id").emit(content)
                return cache.get<Boolean>("ws-$id-connected") ?: false
            }

            override suspend fun listenForWebSocketMessage(id: String): Flow<String> {
                logger.debug("Listener for $id made")
                return pubSub.string("ws-$id").map { println("Send intercept $id $it"); it }
                    .onStart { cache.set("ws-$id-connected", true, timeToLive = Duration.ofDays(1)) }
                    .onCompletion { cache.set("ws-$id-connected", false) }
            }

            override suspend fun launchTask(task: Task<Any?>, input: Any?) {
                coroutineScope {
                    task.implementation(this, input)
                }
            }
        }
    }
}

class ServerTest {
    @Test fun test() {
        TestSettings
        println(Json(Serialization.jsonWithoutDefaults){ prettyPrint = true }.encodeToString(lightningServerSchema))
    }
}