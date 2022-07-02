package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath


@LightningServerDsl
val ServerPath.get: HttpRoute get() = HttpRoute(this, HttpMethod.GET)
@LightningServerDsl
val ServerPath.post: HttpRoute get() = HttpRoute(this, HttpMethod.POST)
@LightningServerDsl
val ServerPath.put: HttpRoute get() = HttpRoute(this, HttpMethod.PUT)
@LightningServerDsl
val ServerPath.patch: HttpRoute get() = HttpRoute(this, HttpMethod.PATCH)
@LightningServerDsl
val ServerPath.delete: HttpRoute get() = HttpRoute(this, HttpMethod.DELETE)
@LightningServerDsl
val ServerPath.head: HttpRoute get() = HttpRoute(this, HttpMethod.HEAD)


@LightningServerDsl
fun ServerPath.get(path: String): HttpRoute = HttpRoute(this.path(path), HttpMethod.GET)
@LightningServerDsl
fun ServerPath.post(path: String): HttpRoute = HttpRoute(this.path(path), HttpMethod.POST)
@LightningServerDsl
fun ServerPath.put(path: String): HttpRoute = HttpRoute(this.path(path), HttpMethod.PUT)
@LightningServerDsl
fun ServerPath.patch(path: String): HttpRoute = HttpRoute(this.path(path), HttpMethod.PATCH)
@LightningServerDsl
fun ServerPath.delete(path: String): HttpRoute = HttpRoute(this.path(path), HttpMethod.DELETE)
@LightningServerDsl
fun ServerPath.head(path: String): HttpRoute = HttpRoute(this.path(path), HttpMethod.HEAD)

@LightningServerDsl
fun HttpRoute.handler(handler: suspend (HttpRequest) -> HttpResponse): HttpRoute {
    Http.routes[this] = handler
    return this
}