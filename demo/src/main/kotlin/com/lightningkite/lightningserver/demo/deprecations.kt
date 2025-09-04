package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.fetch
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.Schedule
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.delete
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.head
import com.lightningkite.lightningserver.http.options
import com.lightningkite.lightningserver.http.patch
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.http.put
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketConnection
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.services.database.HasId
import io.ktor.client.HttpClient
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.KSerializer
import kotlin.time.Duration


@Deprecated("Use standard system", ReplaceWith("path(string).get"))
fun PathSpec0.get(string: String): HttpEndpoint<PathSpec0> = path(string).get
@Deprecated("Use standard system", ReplaceWith("path(string).post"))
fun PathSpec0.post(string: String): HttpEndpoint<PathSpec0> = path(string).post
@Deprecated("Use standard system", ReplaceWith("path(string).put"))
fun PathSpec0.put(string: String): HttpEndpoint<PathSpec0> = path(string).put
@Deprecated("Use standard system", ReplaceWith("path(string).patch"))
fun PathSpec0.patch(string: String): HttpEndpoint<PathSpec0> = path(string).patch
@Deprecated("Use standard system", ReplaceWith("path(string).delete"))
fun PathSpec0.delete(string: String): HttpEndpoint<PathSpec0> = path(string).delete
@Deprecated("Use standard system", ReplaceWith("path(string).options"))
fun PathSpec0.options(string: String): HttpEndpoint<PathSpec0> = path(string).options
@Deprecated("Use standard system", ReplaceWith("path(string).head"))
fun PathSpec0.head(string: String): HttpEndpoint<PathSpec0> = path(string).head

context(builder: ServerBuilder)
fun PathSpec0.websocket(handler: WebSocketHandler<PathSpec0, *>): WebSocketHandler<PathSpec0, *> = bind(handler)

@Deprecated("Use standard syntax", ReplaceWith("path.path(string)")) fun ServerBuilder.path(string: String): PathSpec0 = PathSpec.root.path(string)
@Deprecated("Use standard syntax", ReplaceWith("path.path(string).get")) fun ServerBuilder.get(string: String): HttpEndpoint<PathSpec0> = PathSpec.root.path(string).get
@Deprecated("Use standard syntax", ReplaceWith("path.path(string).post")) fun ServerBuilder.post(string: String): HttpEndpoint<PathSpec0> = PathSpec.root.path(string).post
@Deprecated("Use standard syntax", ReplaceWith("path.path(string).put")) fun ServerBuilder.put(string: String): HttpEndpoint<PathSpec0> = PathSpec.root.path(string).put
@Deprecated("Use standard syntax", ReplaceWith("path.path(string).patch")) fun ServerBuilder.patch(string: String): HttpEndpoint<PathSpec0> = PathSpec.root.path(string).patch
@Deprecated("Use standard syntax", ReplaceWith("path.path(string).delete")) fun ServerBuilder.delete(string: String): HttpEndpoint<PathSpec0> = PathSpec.root.path(string).delete
@Deprecated("Use standard syntax", ReplaceWith("path.path(string).options")) fun ServerBuilder.options(string: String): HttpEndpoint<PathSpec0> = PathSpec.root.path(string).options
@Deprecated("Use standard syntax", ReplaceWith("path.path(string).head")) fun ServerBuilder.head(string: String): HttpEndpoint<PathSpec0> = PathSpec.root.path(string).head

@Deprecated("Use standard syntax", ReplaceWith("path.get")) val ServerBuilder.get: HttpEndpoint<PathSpec0> get() = PathSpec.root.get
@Deprecated("Use standard syntax", ReplaceWith("path.post")) val ServerBuilder.post: HttpEndpoint<PathSpec0> get() = PathSpec.root.post
@Deprecated("Use standard syntax", ReplaceWith("path.put")) val ServerBuilder.put: HttpEndpoint<PathSpec0> get() = PathSpec.root.put
@Deprecated("Use standard syntax", ReplaceWith("path.patch")) val ServerBuilder.patch: HttpEndpoint<PathSpec0> get() = PathSpec.root.patch
@Deprecated("Use standard syntax", ReplaceWith("path.delete")) val ServerBuilder.delete: HttpEndpoint<PathSpec0> get() = PathSpec.root.delete
@Deprecated("Use standard syntax", ReplaceWith("path.options")) val ServerBuilder.options: HttpEndpoint<PathSpec0> get() = PathSpec.root.options
@Deprecated("Use standard syntax", ReplaceWith("path.head")) val ServerBuilder.head: HttpEndpoint<PathSpec0> get() = PathSpec.root.head

@Deprecated("Use the standard syntax", ReplaceWith("path.path(name) bind ScheduledTask(schedule, handler = action)")) fun ServerBuilder.schedule(name: String, schedule: Schedule, action: suspend ServerRuntime.()->Unit) = PathSpec.root.path(name) bind ScheduledTask(schedule, handler = action)
@Deprecated("Use the standard syntax", ReplaceWith("path.path(name) bind ScheduledTask(frequency = frequency, handler = action)")) fun ServerBuilder.schedule(name: String, frequency: Duration, action: suspend ServerRuntime.()->Unit) = PathSpec.root.path(name) bind ScheduledTask(Schedule.Frequency(frequency), handler = action)

