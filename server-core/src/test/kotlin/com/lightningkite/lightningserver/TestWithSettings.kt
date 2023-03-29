package com.lightningkite.lightningserver

import com.lightningkite.lightningdb.collection
import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.auth.OauthProviderCredentials
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.ChangeSocketTest
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.db.restApiWebsocket
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.engine.UnitTestEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.tasks.Tasks
import kotlinx.coroutines.runBlocking
import java.util.*

object TestSettings {
    val database = setting("database", DatabaseSettings())
    val email = setting("email", EmailSettings())
    val jwtSigner = setting("jwt", JwtSigner())
    val files = setting("files", FilesSettings())
    val oauthGoogle = setting<OauthProviderCredentials?>("oauth-google", null)
    val oauthApple = setting<OauthProviderCredentials?>("oauth-apple", null)
    val oauthGithub = setting<OauthProviderCredentials?>("oauth-github", null)
    val ws = ServerPath("test").restApiWebsocket<Unit, ChangeSocketTest.TestThing, UUID>(database, ModelInfo(
        getCollection = { database().collection() },
        forUser = { this }
    ))

    init {
        Settings.populateDefaults(
            mapOf(
                "database" to DatabaseSettings("ram")
            )
        )
        runBlocking {
            Tasks.onSettingsReady()
            engine = UnitTestEngine
            Tasks.onEngineReady()
        }
    }
}