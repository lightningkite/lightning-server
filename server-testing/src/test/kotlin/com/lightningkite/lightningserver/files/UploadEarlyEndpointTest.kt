package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.typed.test
import kotlinx.coroutines.runBlocking
import org.junit.Test

class UploadEarlyEndpointTest {
    @Test
    fun test(): Unit = runBlocking {
        TestSettings
        val a = TestSettings.earlyUpload.endpoint.test(null, Unit)
        runBlocking {
            val match = Http.matcher.match(a.uploadUrl.removePrefix(generalSettings().publicUrl).substringBefore('?'), HttpMethod.PUT)!!
            Http.endpoints[match.endpoint]!!.invoke(HttpRequest(
                match.endpoint,
                match.parts,
                match.wildcard,
                queryParameters = a.uploadUrl.substringAfter('?').split('&').map { it.substringBefore('=') to it.substringAfter('=') },
                body = HttpContent.Text("Test", ContentType.Text.Plain)
            )).let { assert(it.status.success) }
        }
        TestSettings.consumeFile.test(null, ServerFile(a.futureCallToken))
        val result = TestSettings.consumeFile.test(null, ServerFile(a.futureCallToken))
        println("Resulting file: $result")
        runBlocking {
            val match = Http.matcher.match(result.removePrefix(generalSettings().publicUrl).substringBefore('?'), HttpMethod.GET)!!
            Http.endpoints[match.endpoint]!!.invoke(HttpRequest(
                match.endpoint,
                match.parts,
                match.wildcard,
                queryParameters = a.uploadUrl.substringAfter('?').split('&').map { it.substringBefore('=') to it.substringAfter('=') },
            )).let { assert(it.status.success) }
        }
    }
}

