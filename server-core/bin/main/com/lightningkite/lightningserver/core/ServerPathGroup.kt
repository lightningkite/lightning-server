package com.lightningkite.lightningserver.core

import com.lightningkite.lightningserver.http.*

abstract class ServerPathGroup(val path: ServerPath) {
    @LightningServerDsl fun path(string: String) = path.path(string)
    @LightningServerDsl fun get(string: String) = path.get(string)
    @LightningServerDsl fun post(string: String) = path.post(string)
    @LightningServerDsl fun put(string: String) = path.put(string)
    @LightningServerDsl fun patch(string: String) = path.patch(string)
    @LightningServerDsl fun delete(string: String) = path.delete(string)
    @LightningServerDsl fun head(string: String) = path.head(string)
    @LightningServerDsl val get: HttpEndpoint get() = path.get
    @LightningServerDsl val post: HttpEndpoint get() = path.post
    @LightningServerDsl val put: HttpEndpoint get() = path.put
    @LightningServerDsl val patch: HttpEndpoint get() = path.patch
    @LightningServerDsl val delete: HttpEndpoint get() = path.delete
    @LightningServerDsl val head: HttpEndpoint get() = path.head
}