package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath


@LightningServerDsl
val ServerPath.get: HttpEndpoint get() = HttpEndpoint(this, HttpMethod.GET)
@LightningServerDsl
val ServerPath.post: HttpEndpoint get() = HttpEndpoint(this, HttpMethod.POST)
@LightningServerDsl
val ServerPath.put: HttpEndpoint get() = HttpEndpoint(this, HttpMethod.PUT)
@LightningServerDsl
val ServerPath.patch: HttpEndpoint get() = HttpEndpoint(this, HttpMethod.PATCH)
@LightningServerDsl
val ServerPath.delete: HttpEndpoint get() = HttpEndpoint(this, HttpMethod.DELETE)
@LightningServerDsl
val ServerPath.head: HttpEndpoint get() = HttpEndpoint(this, HttpMethod.HEAD)


@LightningServerDsl
fun ServerPath.get(path: String): HttpEndpoint = HttpEndpoint(this.path(path), HttpMethod.GET)
@LightningServerDsl
fun ServerPath.post(path: String): HttpEndpoint = HttpEndpoint(this.path(path), HttpMethod.POST)
@LightningServerDsl
fun ServerPath.put(path: String): HttpEndpoint = HttpEndpoint(this.path(path), HttpMethod.PUT)
@LightningServerDsl
fun ServerPath.patch(path: String): HttpEndpoint = HttpEndpoint(this.path(path), HttpMethod.PATCH)
@LightningServerDsl
fun ServerPath.delete(path: String): HttpEndpoint = HttpEndpoint(this.path(path), HttpMethod.DELETE)
@LightningServerDsl
fun ServerPath.head(path: String): HttpEndpoint = HttpEndpoint(this.path(path), HttpMethod.HEAD)

@LightningServerDsl
fun HttpEndpoint.handler(handler: suspend (HttpRequest) -> HttpResponse): HttpEndpoint {
    Http.endpoints[this] = handler
    if(Http.fixEndingSlash && this.path.after != ServerPath.Afterwards.ChainedWildcard) fixEnding()
    return this
}

@LightningServerDsl
fun HttpEndpoint.fixEnding(): HttpEndpoint {
    Http.endpoints[this.copy(path.copy(after = when(path.after) {
        ServerPath.Afterwards.TrailingSlash -> ServerPath.Afterwards.None
        ServerPath.Afterwards.None -> ServerPath.Afterwards.TrailingSlash
        ServerPath.Afterwards.ChainedWildcard -> throw IllegalArgumentException()
    }))] = {
        HttpResponse.pathMoved(this.path.toString())
    }
    return this
}