@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.exceptions.ExceptionSettings
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.logging.LoggingSettings
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.ktor.lightningServer
import com.lightningkite.lightningserver.pubsub.LocalPubSub
import com.lightningkite.lightningserver.serialization.parse
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.json.Json
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals


class ConfigureAuthKtTest {
    @Serializable
    data class TestUser(override val _id: UUID = UUID.randomUUID(), override val email: String = "") : HasId<UUID>,
        HasEmail

    @Test
    fun testSelfKtor() {
        TestSettings
        val authEndpoints = ServerPath("auth").authEndpoints(TestSettings.jwtSigner, TestSettings.database, TestSettings.email, onNewUser = { TestUser(email = it) })
        val user = TestUser(email = "test@test.com")
        testApplication {
            environment { watchPaths = listOf() }
            application {
                lightningServer(LocalPubSub, LocalCache)
            }
            val client = createClient {
                install(ContentNegotiation) {
                    json(Json {
                        serializersModule = ClientModule
                    })
                }
            }
            TestSettings.database().collection<TestUser>().insertOne(user)
            val token = TestSettings.jwtSigner().token(user._id)
            perfTest {
                val self = client.get(authEndpoints.path("self").toString()) {
                    header("Authorization", "Bearer $token")
                    accept(ContentType.Application.Json)
                }.body<TestUser>()
                assertEquals(user, self)
            }.also { println("Time: $it ms for testSelfKtor") }
        }
    }

    @Test
    fun testSelf() {
        TestSettings
        val authEndpoints = ServerPath("auth").authEndpoints(TestSettings.jwtSigner, TestSettings.database, TestSettings.email, onNewUser = { TestUser(email = it) })
        val user = TestUser(email = "test@test.com")
        runBlocking {
            TestSettings.database().collection<TestUser>().insertOne(user)
            val token = TestSettings.jwtSigner().token(user._id)
            perfTest {
                val self = authEndpoints.path("self").get.test(
                    headers = HttpHeaders(
                        mapOf(
                            HttpHeader.Authorization to "Bearer $token",
                            HttpHeader.Accept to ContentType.Application.Json.toString()
                        )
                    )
                ).body!!.parse<TestUser>()
                assertEquals(user, self)
            }.also { println("Time: $it ms for testSelf") }
        }
    }

    private inline fun perfTest(action: () -> Unit): Long {
        val s = System.currentTimeMillis()
        repeat(10000) { action() }
        return System.currentTimeMillis() - s
    }
}
