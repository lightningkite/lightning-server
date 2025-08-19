package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer

public data class AuthAndPath

public interface ApiHttpHandler<PATH: PathSpec, USER: HasId<*>?, INPUT, OUTPUT>: HttpHandler<PATH> {
    public val authOptions: AuthOptions
    public val inputType: KSerializer<INPUT>
    public val outputType: KSerializer<OUTPUT>
    public val summary: String
    public val description: String
    public val successCode: HttpStatus
    public val errorCases: List<LSError>
    public val examples: List<ApiExample<INPUT, OUTPUT>>
    public suspend fun ServerRuntimeWithAuth<USER, PATH>.handle(input: INPUT): OUTPUT

    override suspend fun handle(serverRuntime: ServerRuntime, request: HttpRequest<PATH>): HttpResponse = with(serverRuntime) {
    }
}

public data class ApiExample<INPUT, OUTPUT>(
    val input: INPUT,
    val output: OUTPUT,
    val name: String = "Example",
    val notes: String? = null,
)
