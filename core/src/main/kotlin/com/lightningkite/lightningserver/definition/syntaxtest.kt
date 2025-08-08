package com.lightningkite.lightningserver.definition

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.httpHandler
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.first
import com.lightningkite.services.data.TypedData
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

private object Server : ServerBuilder() {
    override val externalSerialization: SerializersModule = EmptySerializersModule()
    override val internalSerialization: SerializersModule = EmptySerializersModule()

    val webUrl = path.setting("webUrl", "localhost8080", String.serializer())

    val test = path.path("test").arg<String>("name").post bind httpHandler { req ->
        webUrl()
        HttpResponse(
            body = TypedData.text("Hello, ${req.first}", MediaType.Text.Plain)
        )
    }

    val modelEndpoints = path.path("model") bind Endpoints
}

private object Endpoints : ServerBuilder() {
    val endpoint = path.path("detail").arg<String>("id").get bind httpHandler {
        Server.webUrl()
        HttpResponse(status = HttpStatus.NotFound)
    }
}