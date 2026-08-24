package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebSocket.Companion.plus
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.withSdkInfo
import com.lightningkite.services.database.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

@Serializable
data class User(
    override val _id: Uuid = Uuid.random(),
    val isSuperUser: Boolean = false,
) : HasId<Uuid> {
    companion object : PrincipalType<User, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<User> = serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): User = User(id)
    }
}

object Server : ServerBuilder() {
    init {
        AuthRequirement.isSuperUser = User.require { it.fetch().isSuperUser }
    }

    val database = setting("database", Database.Settings())

    val root = path.get bind explicitApiHttpHandler(
        summary = "Index",
        auth = noAuth,
        inputType = Unit.serializer(),
        outputType = Int.serializer(),
        implementation = { _: Unit -> 0 }
    )

    val action = path.post bind explicitApiHttpHandler(
        functionName = "Improper SDK Function Name",
        summary = "Action",
        description = "Does something really really cool...",
        auth = User.require(scopes = emptySet()) or noAuth,
        inputType = Unit.serializer(),
        outputType = Int.serializer(),
        implementation = { _: Unit ->
            val name = authOrNull?.fetch()?._id

            0
        }
    )

    val first = path.path("m1") module Module
    val second = path.path("m2") module SecondModule.withSdkInfo("CustomEndpoints", "custom")
    val third = path.path("third") module ThirdModule.withSdkInfo("OtherEndpoints", "other")

    val predefined = path.path("predefined") module preDefinedModule.withSdkInfo("PredefinedEndpoints")

    val inline = path.path("inline") include Inlined
}

@Serializable
data class TestInput(
    val id: Int,
    val name: String,
)

private val testEndpoint = explicitApiHttpHandler<PathSpec1<String>, HasId<*>?, TestInput, String>(
    summary = "Test Endpoint",
    functionName = "testSdkEndpoint",
    description = "This is a test endpoint for the sdk",
    auth = AuthRequirement.Authenticated(
        scopes = setOf(RequiredScope("sdk:test"), RequiredScope("sdk:other")), maxAge = 8.hours
    ) or AuthRequirement.IsSuperUser,
    inputType = TestInput.serializer(),
    outputType = String.serializer(),
    implementation = { _: TestInput -> "hello world" }
)

@Serializable
data class TestModel(
    override val _id: ID = ID(),
    val name: String,
    val statusInfo: TestStatusInfo = TestStatusInfo(),
) : HasId<TestModel.ID> {
    @JvmInline
    @Serializable
    value class ID(override val raw: Uuid = Uuid.random()) : TypedId<Uuid, ID> {
        override fun toString(): String = raw.toString()
    }

    enum class Status {
        Active,
        Inactive,
        Pending,
    }
    @Serializable
    data class TestStatusInfo(
        val status: Status = Status.Active,
        val updatedAt: Int = 0,
    )
}

object Module : ServerBuilder() {
    val info = Server.database.modelInfo(
        auth = noAuth,
        tableName = "TestModel",
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

    val info = Server.database.modelInfo(
        auth = anyAuth,
        tableName = "TestModel",
        permissions = { ModelPermissions.allowAll<TestModel>() }
    )

    val rest = path.path("rest") include ModelRestEndpoints(info) + ModelRestUpdatesWebSocket(info)

    val nonInlined = path.path("noinline") module Inlined.withSdkInfo("NotInlinedApi")

    val endpoint = path.path("endpoint").arg<String>("second").post bind testEndpoint
}

object ThirdModule : ServerBuilder() {
    val info = Server.database.modelInfo(
        auth = noAuth,
        tableName = "TestModel",
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
        description = "This endpoint is sometimes inlined, sometimes not.",
        auth = AuthRequirement.IsAdmin or AuthRequirement.IsSuperUser,
        implementation = { _: Unit -> 42 }
    )
}

val preDefinedModule = object : ServerBuilder() {
    val endpoints = path.path("foo").post bind ApiHttpHandler(
        summary = "Pre-Defined Endpoint",
        description = "This is an endpoint included through a pre-build definition",
        auth = User.require(RequiredScope("pre:defined")) or User.require(RequiredScope("foo")) or AuthRequirement.IsSuperUser,
        implementation = { input: Int -> input * 2 }
    )
}.build()

