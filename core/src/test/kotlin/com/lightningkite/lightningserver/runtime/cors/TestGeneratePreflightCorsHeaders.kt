package com.lightningkite.lightningserver.runtime.cors

import com.lightningkite.lightningserver.definition.CorsSettings
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.runtime.generatePreflightCorsHeaders
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.text.get

class TestGeneratePreflightCorsHeaders {

    @Test
    fun testLimitDomains(){
        val domain = "some.domain"

        val cors = CorsSettings(limitToDomains = listOf(domain))

        var result = cors
            .generatePreflightCorsHeaders(HttpHeaders { set(HttpHeader.Origin, "domain.other") })

        assertNull(result[HttpHeader.AccessControlAllowOrigin])

        result = cors
            .generatePreflightCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })

        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin]?.first()?.root)

    }

    @Test
    fun testLimitDomainsNull(){

        val cors = CorsSettings(limitToDomains = null)

        var domain = "domain.other"
        var result = cors
            .generatePreflightCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })
        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin]?.first()?.root)

        domain = "sub.domain.other"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })
        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin]?.first()?.root)

        domain = "some.long.absurd.domain.for.no.reason"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })
        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin]?.first()?.root)

        domain = "*"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })
        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin]?.first()?.root)

        domain = ""
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })
        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin]?.first()?.root)

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
        assertEquals(methods, result[HttpHeader.AccessControlAllowMethods]?.first()?.root)

        methods = "GET, POST"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestMethod, methods)
            })
        assertEquals(methods, result[HttpHeader.AccessControlAllowMethods]?.first()?.root)

        methods = "NONSTANDARDMETHOD"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestMethod, methods)
            })
        assertEquals(methods, result[HttpHeader.AccessControlAllowMethods]?.first()?.root)

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
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowMethods]?.first()?.root)

        methods = "GET, POST"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestMethod, methods)
            })
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowMethods]?.first()?.root)

        methods = "GET, POST, *"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestMethod, methods)
            })
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowMethods]?.first()?.root)

        methods = "*"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestMethod, methods)
            })
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowMethods]?.first()?.root)

        methods = "NONSTANDARDMETHOD"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestMethod, methods)
            })
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowMethods]?.first()?.root)


        allowed = listOf(HttpMethod.GET.toString(), "NONSTANDARD")
        cors = CorsSettings(limitToDomains = null, limitToMethods = allowed)

        methods = "NONSTANDARDMETHOD"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestMethod, methods)
            })
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowMethods]?.first()?.root)
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
        assertEquals(headers, result[HttpHeader.AccessControlAllowHeaders]?.first()?.root)

        headers = "X-Custom-Header, Other-Header-Value"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestHeaders, headers)
            })
        assertEquals(headers, result[HttpHeader.AccessControlAllowHeaders]?.first()?.root)

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
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowHeaders]?.first()?.root)

        headers = "X-Custom-Header, Some-Value, *"
        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestHeaders, headers)
            })
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowHeaders]?.first()?.root)

        allowed = listOf("X-Custom-Header", "Second-Header-Key", "*")
        cors = CorsSettings(limitToDomains = null, limitToHeaders = allowed)

        result = cors
            .generatePreflightCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestHeaders, headers)
            })
        assertEquals(allowed.joinToString(), result[HttpHeader.AccessControlAllowHeaders]?.first()?.root)
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
        assertEquals(true.toString(), result[HttpHeader.AccessControlAllowCredentials]?.first()?.root)

    }

}