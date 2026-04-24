package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.lightningserver.typed.sdk.FetcherSdk
import com.lightningkite.lightningserver.typed.sdk.SDK.writeUsingDefaultSettings
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.database.*
import com.lightningkite.services.files.PublicFileSystem
import kotlinx.serialization.Serializable
import org.junit.Test
import kotlin.uuid.Uuid

class UploadEarlySdkTests {
    private object Server : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val files = setting("files", PublicFileSystem.Settings())

        val uploadEarly = path.path("upload") module UploadEarlyEndpoint(
            files = files,
            database = database,
            fileScanner = Runtime.Constant(emptyList())
        )

        val module = path.path("module") module Module
    }

    @Serializable
    data class Model(
        override val _id: Uuid,
        val name: String,
    ) : HasId<Uuid>

    private object Module : ServerBuilder() {
        val info = Server.database.modelInfo(
            auth = noAuth,
            permissions = { ModelPermissions.allowAll<Model>() }
        )

        val rest = path.path("rest") include ModelRestEndpoints(info)
    }

    @Test
    fun generateSdk() {
        FetcherSdk("com.lightningkite.lightningserver.files").writeUsingDefaultSettings(
            Server,
            KFile("./src/test/kotlin/com/lightningkite/lightningserver/files/generated")
        )
    }
}