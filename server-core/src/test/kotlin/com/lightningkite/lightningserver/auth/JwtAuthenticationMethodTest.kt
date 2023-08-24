package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class JwtAuthenticationMethodTest {
    @Test
    fun test() {
        val hasher = SecureHasherSettings()()
        val method = JwtAuthenticationMethod(
            fromStringInRequest = Authentication.FromStringInRequest.AuthorizationHeader(),
            hasher = { hasher })
        val claims = JwtClaims(
            iss = "test",
            sub = "Test",
            aud = "teSt",
            exp = System.currentTimeMillis() / 1000 + 1000,
            iat = System.currentTimeMillis() / 1000
        )
        runBlocking {
            method.tryGet(HttpRequest(
                endpoint = HttpEndpoint(ServerPath.root, HttpMethod.GET),
                headers = HttpHeaders {
                    set(HttpHeader.Authorization, "Bearer ${method.sign(claims)}")
                }
            )).let { println(it) }
        }
    }
}