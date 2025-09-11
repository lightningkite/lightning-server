package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.lightningserver.typed.sdk.FetcherSdk
import com.lightningkite.lightningserver.typed.sdk.SDK.writeSdk
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.data.KFile
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.files.PublicFileSystem
import kotlinx.serialization.Serializable
import org.junit.Test
import kotlin.uuid.Uuid

class UploadEarlySdkTests {
    private object Server : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val files = setting("files", PublicFileSystem.Settings())

        val uploadEarly = path.path("upload") bind module(UploadEarlyEndpoint(
            files = files,
            database = database,
            fileScanner = Runtime.Constant(emptyList())
        ))

        val module = path.path("module") bind module(Module)
    }

    @Serializable
    data class Model(
        override val _id: Uuid,
        val name: String
    ) : HasId<Uuid>

    private object Module : ServerBuilder() {
        val info = Server.database.modelInfo(
            auth = noAuth,
            permissions = { ModelPermissions.allowAll<Model>() }
        )

        val rest = path.path("rest") bind ModelRestEndpoints(info)
    }

    @Test
    fun generateSdk() {
        Server.writeSdk(FetcherSdk, KFile("./src/test/kotlin/com/lightningkite/lightningserver/files/generated"), "com.lightningkite.lightningserver.files")
    }
}