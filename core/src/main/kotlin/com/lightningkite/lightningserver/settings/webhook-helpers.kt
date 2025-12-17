package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.fullUrl
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.services.data.HttpAdapter
import com.lightningkite.services.data.WebhookSubserviceWithResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public operator fun <Input, Output> Runtime<WebhookSubserviceWithResponse<Input, Output>>.invoke(frequency: Duration = 1.minutes, handler: suspend context(ServerRuntime) (Input)->Output): WebhookServer<Input, Output> =
    WebhookServer(this, handler, frequency)

public class WebhookServer<Input, Output>(
    private val rt: Runtime<WebhookSubserviceWithResponse<Input, Output>>,
    private val handler: suspend context(ServerRuntime) (Input) -> Output,
    private val frequency: Duration
) : ServerBuilder() {
    public val webhook: HttpHandler<PathSpec0> = path.path("webhook").post bind Runtime { rt() as HttpAdapter<Input, Output> }.invoke(handler)
    public val webhookSetup: StartupTask = path.path("webhook-setup") bind StartupTask {
        rt().configureWebhook(webhook.location.path.resolved().fullUrl())
    }
    public val schedule: ScheduledTask = path.path("schedule") bind ScheduledTask(frequency, handler = {
        rt().onSchedule()
    })
}

public operator fun <Input, Output> Runtime<HttpAdapter<Input, Output>>.invoke(handler: suspend context(ServerRuntime) (Input)->Output): HttpHandler<PathSpec0> = HttpHandler<PathSpec0> { request ->
    try {
        this@invoke().render(
            handler(
                this@invoke().parse(
                    request.queryParameters.entries,
                    request.headers.normalizedEntries.mapValues { it.value.map { it.toHttpString() } },
                    request.body ?: throw BadRequestException()
                )
            )
        ).let { response ->
            HttpResponse(
                status = HttpStatus(response.status),
                body = response.body,
                headers = HttpHeaders(response.headers.entries.flatMap { it.value.map { v -> it.key to v } })
            )
        }
    } catch(e: HttpAdapter.SpecialCaseException) {
        val response = e.intendedResponse
        HttpResponse(
            status = HttpStatus(response.status),
            body = response.body,
            headers = HttpHeaders(response.headers.entries.flatMap { it.value.map { v -> it.key to v } })
        )
    }
}

//public operator fun <Input, Output> Runtime<WebsocketAdapter<Input, Output>>.invoke(handler: context(ServerRuntime) (Input)->Output): WebSocketHandler<Input, Output> = WebSocketHand