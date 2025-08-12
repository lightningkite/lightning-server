package com.lightningkite.lightningserver.runtime.cors

import com.lightningkite.lightningserver.definition.CorsSettings
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.runtime.generateCorsHeaders
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TestGenerateCorsHeaders {


    @Test
    fun testLimitDomains(){
        val domain = "some.domain"

        val cors = CorsSettings(limitToDomains = listOf(domain))

        var result = cors
            .generateCorsHeaders(HttpHeaders { set(HttpHeader.Origin, "domain.other") })

        assertNull(result[HttpHeader.AccessControlAllowOrigin])

        result = cors
            .generateCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })

        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin]?.first()?.root)

    }

    @Test
    fun testLimitDomainsNull(){

        val cors = CorsSettings(limitToDomains = null)

        var domain = "domain.other"
        var result = cors
            .generateCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })
        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin]?.first()?.root)

        domain = "sub.domain.other"
        result = cors
            .generateCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })
        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin]?.first()?.root)

        domain = "some.long.absurd.domain.for.no.reason"
        result = cors
            .generateCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })
        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin]?.first()?.root)

        domain = "*"
        result = cors
            .generateCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })
        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin]?.first()?.root)

        domain = ""
        result = cors
            .generateCorsHeaders(HttpHeaders { set(HttpHeader.Origin, domain) })
        assertEquals(domain, result[HttpHeader.AccessControlAllowOrigin]?.first()?.root)

    }

    @Test
    fun testExposeHeadersNull(){
        var domain = "domain.other"
        val cors = CorsSettings(exposedHeaders = null, limitToDomains = null)

        var header = "X-Custom-Header"
        var result = cors
            .generateCorsHeaders(HttpHeaders {
                set(HttpHeader.Origin, domain)
                set(HttpHeader.AccessControlRequestHeaders, header)
            })
        assertNotNull(result[HttpHeader.AccessControlAllowOrigin])
        assertNull(result[HttpHeader.AccessControlExposeHeaders])

    }

    @Test
    fun testExposeHeaders(){

        var domain = "domain.other"
        val customHeaders = listOf("X-Custom-Header-1", "X-Custom-Header-3","X-Custom-Header-2")

        val cors = CorsSettings(exposedHeaders = customHeaders, limitToDomains = null)

        var result = cors
            .generateCorsHeaders(HttpHeaders{ set(HttpHeader.Origin, domain)})
        assertNotNull(result[HttpHeader.AccessControlAllowOrigin])
        assertEquals(customHeaders.joinToString(), result[HttpHeader.AccessControlExposeHeaders]?.first()?.root)

    }

    @Test
    fun testAllowCredentials(){

        var domain = "domain.other"

        var result = CorsSettings(allowCredentials = false, limitToDomains = null)
            .generateCorsHeaders(HttpHeaders{ set(HttpHeader.Origin, domain)})
        assertNotNull(result[HttpHeader.AccessControlAllowOrigin])
        assertNull(result[HttpHeader.AccessControlAllowCredentials])

        result = CorsSettings(allowCredentials = true, limitToDomains = null)
            .generateCorsHeaders(HttpHeaders{ set(HttpHeader.Origin, domain)})
        assertNotNull(result[HttpHeader.AccessControlAllowOrigin])
        assertEquals(true.toString(), result[HttpHeader.AccessControlAllowCredentials]?.first()?.root)

    }

}