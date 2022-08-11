@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.serialization.parse
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.email.ConsoleEmailClient


class AuthEndpointsTest {
    @Serializable
    data class TestUser(override val _id: UUID = UUID.randomUUID(), override val email: String = "") : HasId<UUID>,
        HasEmail

    init {
        TestSettings
    }

    val authEndpoints = ServerPath("auth").authEndpoints(
        TestSettings.jwtSigner,
        TestSettings.database,
        TestSettings.email,
        onNewUser = { TestUser(email = it) })

    val user = TestUser(email = "test@test.com")

    @Test
    fun testLogIn() {
        runBlocking {
            val response = authEndpoints.loginEmail.route.test(
                headers = HttpHeaders(
                    mapOf(
                        HttpHeader.Accept to ContentType.Application.Json.toString()
                    )
                ),
                body = HttpContent.Text(""" "test@test.com" """, ContentType.Application.Json)
            )
            assertEquals(HttpStatus.NoContent, response.status)
            assertEquals("test@test.com", ConsoleEmailClient.lastEmailSent?.to?.firstOrNull())
        }
    }

    @Test
    fun testLanding() {
        runBlocking {
            TestSettings.database().collection<TestUser>().upsertOneById(user._id, user)
            val token = TestSettings.jwtSigner().token(user._id)
            val response = authEndpoints.landingRoute.test(
                queryParameters = listOf("jwt" to token)
            )
            assertEquals(HttpStatus.SeeOther, response.status)
            val newToken = response.headers[HttpHeader.SetCookie]?.substringBefore(';')?.substringAfter("=")!!
            TestSettings.jwtSigner().verify<UUID>(newToken)
        }
    }

    @Test
    fun testRefreshToken() {
        runBlocking {
            TestSettings.database().collection<TestUser>().upsertOneById(user._id, user)
            val token = TestSettings.jwtSigner().token(user._id)
            val response = authEndpoints.refreshToken.implementation(user, Unit)
            TestSettings.jwtSigner().verify<UUID>(response)
        }
    }

    @Test
    fun testSelf() {
        runBlocking {
            TestSettings.database().collection<TestUser>().upsertOneById(user._id, user)
            val token = TestSettings.jwtSigner().token(user._id)
            val response = authEndpoints.getSelf.route.test(
                headers = HttpHeaders(
                    mapOf(
                        HttpHeader.Authorization to "Bearer $token",
                        HttpHeader.Accept to ContentType.Application.Json.toString()
                    )
                )
            )
            val retrieved = response.body!!.parse<TestUser>()
            assertEquals(user, retrieved)
        }
    }

    @Test
    fun testSelfBadAuth() {
        runBlocking {
            val response = authEndpoints.getSelf.route.test(
                headers = HttpHeaders(
                    mapOf(
                        HttpHeader.Authorization to "Bearer notarealtoken",
                        HttpHeader.Accept to ContentType.Application.Json.toString()
                    )
                )
            )
            assertEquals(HttpStatus.Unauthorized, response.status)
        }
    }

    @Test
    fun testSelfNoAuth() {
        runBlocking {
            val response = authEndpoints.getSelf.route.test(
                headers = HttpHeaders(
                    mapOf(
                        HttpHeader.Accept to ContentType.Application.Json.toString()
                    )
                )
            )
            assertEquals(HttpStatus.Unauthorized, response.status)
        }
    }
}
