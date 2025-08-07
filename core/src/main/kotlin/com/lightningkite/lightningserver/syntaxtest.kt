package com.lightningkite.lightningserver

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.services.data.TypedData
import kotlinx.serialization.builtins.serializer
import java.io.File

private fun defineServer() {

    PathSpec.root
        .path("path")
        .path("otherpath")
        .arg<String>("test")
        .arg<Boolean>("turnedOn")

    File(".").resolve("asdf")

    object: ServerDefinition() {
        override val internalSerialization: Serialization = Serialization()
        override val externalSerialization: Serialization = Serialization()
        val webUrl = setting("webUrl", "Test", String.serializer())

        val test = path.path("test").arg<String>("sample").get bind httpHandler { request ->
            webUrl()
            HttpResponse(
                body = TypedData.text(request.first, MediaType.Text.Plain)
            )
        }
    }

}

