package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import kotlinx.serialization.Serializable
import org.junit.Test

class ModelRestUpdatesWebsocketTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val info = database.modelInfo<HasId<*>?, Sample, String>(
            auth = noAuth,
            permissions = { ModelPermissions.allowAll() }
        )
        val model = path.path("model") include ModelRestEndpoints(info)
        val ws = path.path("model").path("updates") include ModelRestUpdatesWebsocket(info)
    }

    val test = TestRunner(TestServer, settings = {
        generalSettings set GeneralServerSettings()
        database set Database.Settings()
    })

    @Test fun test() {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
        }
    }
}

@Serializable
@GenerateDataClassPaths
data class Sample(override val _id: String, val name: String): HasId<String>