package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * W6: a typed endpoint that throws an error whose `detail` is not in its declared `errorCases`
 * logs an advisory warning, but the thrown exception must still surface unchanged (the warning
 * does NOT alter the response). This goes through the full request path (serverRuntime.handle),
 * where the warning lives, unlike the typed `.test()` helper which calls handle(access, input)
 * directly.
 */
class ErrorCaseWarningTest {

    object TestServer : ServerBuilder() {
        init { registerBasicMediaTypeCoders() }

        // Return type declared as String but the body always throws — gives the endpoints a
        // concrete OUTPUT type while still exercising the thrown-error path.
        private fun throwUndeclared(): String = throw BadRequestException(detail = "undeclared-thing", message = "boom")
        private fun throwDeclared(): String = throw BadRequestException(detail = "declared-thing", message = "bad")

        // errorCases is empty, but the implementation throws a detail slug — undeclared (warns).
        val boom = path.path("boom").post bind ApiHttpHandler(
            summary = "Throws an undeclared error",
            auth = noAuth,
            errorCases = emptyList(),
            implementation = { _: Unit -> throwUndeclared() }
        )

        // The thrown detail IS declared here — no warning expected, same response.
        val declared = path.path("declared").post bind ApiHttpHandler(
            summary = "Throws a declared error",
            auth = noAuth,
            errorCases = listOf(LSError(http = 400, detail = "declared-thing", message = "bad")),
            implementation = { _: Unit -> throwDeclared() }
        )
    }

    private fun request(path: String) = HttpRequest<PathSpec>(
        path = RawHttpEndpoint(asString = path, method = HttpMethod.POST),
        queryParameters = QueryParameters.EMPTY,
        headers = HttpHeaders.EMPTY,
        domain = "example.com",
        protocol = "https",
        sourceIp = "local",
    )

    @Test
    fun undeclaredErrorStillSurfacesAs400() = runBlocking {
        TestServer.test({}) {
            // Warning is logged for /boom; both still return 400 (response unchanged by W6).
            assertEquals(400, serverRuntime.handle(request("/boom")).status.code)
            assertEquals(400, serverRuntime.handle(request("/declared")).status.code)
        }
    }
}
