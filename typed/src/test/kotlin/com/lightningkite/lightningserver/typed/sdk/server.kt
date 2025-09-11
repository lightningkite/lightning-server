package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.auth.AnyId
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.withSdkInfo
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.Uuid

object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())

    val root = path.get bind ApiHttpHandler(
        summary = "Index",
        auth = noAuth,
        inputType = Unit.serializer(),
        outputType = Int.serializer(),
        implementation = { _: Unit -> 0 }
    )

    val first = path.path("m1") include Module
    val second = path.path("m2") module SecondModule.withSdkInfo("CustomEndpoints", "custom")
    val third = path.path("third") module ThirdModule.withSdkInfo("OtherEndpoints", "other")

    val predefined = path.path("predefined") module preDefinedModule.withSdkInfo("PredefinedEndpoints")

    val inline = path.path("inline") include Inlined
}

@Serializable
data class TestInput(
    val id: Int,
    val name: String
)

private val testEndpoint = ApiHttpHandler<PathSpec1<String>, HasId<AnyId>?, TestInput, String>(
    summary = "Test",
    auth = noAuth,
    inputType = TestInput.serializer(),
    outputType = String.serializer(),
    implementation = { _: TestInput -> "hello world" }
)

@Serializable
data class TestModel(
    override val _id: Uuid,
    val name: String
) : HasId<Uuid>

object Module : ServerBuilder() {
    val info = Server.database.modelInfo(
        auth = noAuth,
        permissions = { ModelPermissions.allowAll<TestModel>() }
    )

    val rest = path.path("rest") include ModelRestEndpoints(info)

    val inline = path.path("inline") include Inlined

    val inline2 = path.path("inline").path("again") include Inlined

    val nest = path.path("second") module SecondModule

    val duplicate = path.path("duplicate") module SecondModule

    val endpoint = path.path("endpoint").arg<String>("first").post bind testEndpoint
}

object SecondModule : ServerBuilder() {
    init {
        sdkSettings.defaultInfo = SdkModule.Info("DefaultEndpoints", "default")
    }

    val nonInlined = path.path("noinline") module Inlined.withSdkInfo("NotInlinedApi")

    val endpoint = path.path("endpoint").arg<String>("second").post bind testEndpoint
}

object ThirdModule : ServerBuilder() {
    val info = Server.database.modelInfo(
        auth = noAuth,
        permissions = { ModelPermissions.allowAll<TestModel>() }
    )

    val rest = path.path("rest") module ModelRestEndpoints(info)

    val rest2 = path.path("rest2") module ModelRestEndpoints(info)

    val inline = path.path("inline") include Inlined

    val endpoint = path.path("endpoint").arg<String>("third").post bind testEndpoint
}

object Inlined : ServerBuilder() {
    val inlinedEndpoint = path.path("action").arg<Uuid>("id").arg<Uuid>("category").post bind ApiHttpHandler(
        summary = "Inlined Endpoint",
        auth = noAuth,
        implementation = { _: Unit -> 42 }
    )
}

val preDefinedModule = object : ServerBuilder() {
    val endpoints = path.path("foo").post bind ApiHttpHandler(
        summary = "Foo",
        auth = noAuth,
        implementation = { input: Int -> input * 2 }
    )
}.build()

