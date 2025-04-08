package com.lightningkite.lightningserver.cors

import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.settings.CorsSettings
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TestGeneratePreflightCorsHeaders {

    @Test
    fun testOldAllowedDomains(){
        val domain = "some.domain"

        val cors = CorsSettings(allowedDomains = listOf(domain))

        var result = cors
            .generatePreflightCorsHeaders(HttpHeaders { set(HttpHeader.Origin, "domain.other") })

        assertNull(result[HttpHeader.AccessControlAllowOrigin])

        result = cors
            .generatePreflightCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })

        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin])

    }

    @Test
    fun testLimitDomains(){
        val domain = "some.domain"

        val cors = CorsSettings(limitToDomains = listOf(domain))

        var result = cors
            .generatePreflightCorsHeaders(HttpHeaders { set(HttpHeader.Origin, "domain.other") })

        assertNull(result[HttpHeader.AccessControlAllowOrigin])

        result = cors
            .generatePreflightCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })

        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin])

    }

    @Test
    fun testLimitDomainsNull(){

        val cors = CorsSettings(limitToDomains = null)

        var domain = "domain.other"
        var result = cors
            .generatePreflightCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })
        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin])

        domain = "sub.domain.other"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })
        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin])

        domain = "some.long.absurd.domain.for.no.reason"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })
        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin])

        domain = "*"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })
        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin])

        domain = ""
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })
        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin])

    }


    @Test
    fun testLimitMethodsNull(){

        val domain = "domain.other"
        val cors = CorsSettings(limitToDomains = null, limitToMethods = null)

        var methods = "GET"
        var result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestMethod, methods)
            })
        assertEquals(methods, result[HttpHeader.AccessControlAllowMethods])

        methods = "GET, POST"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestMethod, methods)
            })
        assertEquals(methods, result[HttpHeader.AccessControlAllowMethods])

        methods = "NONSTANDARDMETHOD"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestMethod, methods)
            })
        assertEquals(methods, result[HttpHeader.AccessControlAllowMethods])

    }

    @Test
    fun testLimitMethods(){

        val domain = "domain.other"
        var allowed = listOf(HttpMethod.GET.toString())
        var cors = CorsSettings(limitToDomains = null, limitToMethods = allowed)

        var methods = "GET"
        var result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestMethod, methods)
            })
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowMethods])

        methods = "GET, POST"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestMethod, methods)
            })
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowMethods])

        methods = "GET, POST, *"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestMethod, methods)
            })
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowMethods])

        methods = "*"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestMethod, methods)
            })
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowMethods])

        methods = "NONSTANDARDMETHOD"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestMethod, methods)
            })
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowMethods])


        allowed = listOf(HttpMethod.GET.toString(), "NONSTANDARD")
        cors = CorsSettings(limitToDomains = null, limitToMethods = allowed)

        methods = "NONSTANDARDMETHOD"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestMethod, methods)
            })
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowMethods])
    }

    @Test
    fun testAllowedHeadersWildCard(){

        val domain = "domain.other"
        var cors = CorsSettings(limitToDomains = null, allowedHeaders = listOf("*"))
        var autoHeaders = listOf(HttpHeader.ContentType.toString(), HttpHeader.Authorization.toString()).joinToString()

        var headers = "X-Custom-Header"
        var result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestHeaders, headers)
            })
        assertEquals("$autoHeaders, $headers", result[HttpHeader.AccessControlAllowHeaders])

        headers = "X-Custom-Header, Other-Header-Value"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestHeaders, headers)
            })
        assertEquals("$autoHeaders, $headers", result[HttpHeader.AccessControlAllowHeaders])


        cors = CorsSettings(limitToDomains = null, allowedHeaders = listOf("*", "AnotherHeader"))
        autoHeaders = listOf(HttpHeader.ContentType.toString(), HttpHeader.Authorization.toString()).joinToString()

        headers = "X-Custom-Header"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestHeaders, headers)
            })
        assertEquals("$autoHeaders, $headers, AnotherHeader", result[HttpHeader.AccessControlAllowHeaders])

        headers = "X-Custom-Header, Other-Header-Value"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestHeaders, headers)
            })
        assertEquals("$autoHeaders, $headers, AnotherHeader", result[HttpHeader.AccessControlAllowHeaders])

    }


    @Test
    fun testAllowedHeaders(){

        val domain = "domain.other"
        var cors = CorsSettings(limitToDomains = null, allowedHeaders = listOf("AnotherHeader"))
        var autoHeaders = listOf(HttpHeader.ContentType.toString(), HttpHeader.Authorization.toString()).joinToString()

        var headers = "X-Custom-Header"
        var result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestHeaders, headers)
            })
        assertEquals("$autoHeaders, AnotherHeader", result[HttpHeader.AccessControlAllowHeaders])

        headers = "X-Custom-Header, Other-Header-Value"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestHeaders, headers)
            })
        assertEquals("$autoHeaders, AnotherHeader", result[HttpHeader.AccessControlAllowHeaders])


        cors = CorsSettings(limitToDomains = null, allowedHeaders = listOf("AnotherHeader", "X-Another-Header"))
        autoHeaders = listOf(HttpHeader.ContentType.toString(), HttpHeader.Authorization.toString()).joinToString()

        headers = "X-Custom-Header"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestHeaders, headers)
            })
        assertEquals("$autoHeaders, AnotherHeader, X-Another-Header", result[HttpHeader.AccessControlAllowHeaders])


    }


    @Test
    fun testLimitHeadersNull(){

        val domain = "domain.other"
        val cors = CorsSettings(limitToDomains = null, limitToHeaders = null)

        var headers = "X-Custom-Header"
        var result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestHeaders, headers)
            })
        assertEquals(headers, result[HttpHeader.AccessControlAllowHeaders])

        headers = "X-Custom-Header, Other-Header-Value"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestHeaders, headers)
            })
        assertEquals(headers, result[HttpHeader.AccessControlAllowHeaders])

    }

    @Test
    fun testLimitHeaders(){

        val domain = "domain.other"
        var allowed = listOf("X-Custom-Header")
        var cors = CorsSettings(limitToDomains = null, limitToHeaders = allowed)

        var headers = "Some-Value"
        var result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestHeaders, headers)
            })
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowHeaders])

        headers = "X-Custom-Header, Some-Value, *"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestHeaders, headers)
            })
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowHeaders])

        allowed = listOf("X-Custom-Header", "Second-Header-Key", "*")
        cors = CorsSettings(limitToDomains = null, limitToHeaders = allowed)

        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestHeaders, headers)
            })
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowHeaders])
    }


    @Test
    fun testAllowCredentials(){

        var domain = "domain.other"

        var result = CorsSettings(allowCredentials = false, limitToDomains = null)
            .generatePreflightCorsHeaders(HttpHeaders{ set(HttpHeader.Origin, domain)})
        assertNotNull(result[HttpHeader.AccessControlAllowOrigin])
        assertNull(result[HttpHeader.AccessControlAllowCredentials])

        result = CorsSettings(allowCredentials = true, limitToDomains = null)
            .generatePreflightCorsHeaders(HttpHeaders{ set(HttpHeader.Origin, domain)})
        assertNotNull(result[HttpHeader.AccessControlAllowOrigin])
        assertEquals(true.toString(), result[HttpHeader.AccessControlAllowCredentials])

    }

}