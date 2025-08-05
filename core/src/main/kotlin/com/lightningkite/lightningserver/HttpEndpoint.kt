package com.lightningkite.lightningserver



public class HttpEndpoint<Path : PathSpec>(public val path: Path, public val method: HttpMethod)

public val <T : PathSpec> T.get: HttpEndpoint<T> get() = HttpEndpoint(this, HttpMethod.GET)
public val <T : PathSpec> T.post: HttpEndpoint<T> get() = HttpEndpoint(this, HttpMethod.POST)
public val <T : PathSpec> T.put: HttpEndpoint<T> get() = HttpEndpoint(this, HttpMethod.PUT)
public val <T : PathSpec> T.patch: HttpEndpoint<T> get() = HttpEndpoint(this, HttpMethod.PATCH)
public val <T : PathSpec> T.delete: HttpEndpoint<T> get() = HttpEndpoint(this, HttpMethod.DELETE)
public val <T : PathSpec> T.options: HttpEndpoint<T> get() = HttpEndpoint(this, HttpMethod.OPTIONS)
public val <T : PathSpec> T.head: HttpEndpoint<T> get() = HttpEndpoint(this, HttpMethod.HEAD)
