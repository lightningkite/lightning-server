package com.lightningkite.lightningserver

import com.lightningkite.lightningdb.collection
import com.lightningkite.lightningserver.auth.oauth.OauthProviderCredentials
import com.lightningkite.lightningserver.auth.oauth.OauthProviderCredentialsApple
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.db.restApiWebsocket
import com.lightningkite.lightningserver.db.testmodels.TestThing
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.encryption.SecureHasherSettings
import com.lightningkite.lightningserver.engine.UnitTestEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.sms.SMSSettings
import com.lightningkite.lightningserver.tasks.Tasks
import kotlinx.coroutines.runBlocking
import java.util.*

object TestSettings {
    val database = setting("database", DatabaseSettings("ram"))
    val email = setting("email", EmailSettings("test"))
    val sms = setting("sms", SMSSettings("test"))
    val jwtSigner = setting("jwt", SecureHasherSettings())
    val cache = setting("cache", CacheSettings())
    val files = setting("files", FilesSettings())
    val oauthGoogle = setting<OauthProviderCredentials?>("oauth_google", null)
    val oauthApple = setting<OauthProviderCredentialsApple?>("oauth_apple", null)
    val oauthGithub = setting<OauthProviderCredentials?>("oauth_github", null)
    val oauthMicrosoft = setting<OauthProviderCredentials?>("oauth_microsoft", null)


    val path = ServerPath("auth")

    val ws = ServerPath("test").restApiWebsocket<Unit, TestThing, UUID>(
        database,
        storeUser = { "" },
        loadUser = { Unit },
        info = ModelInfo(
            getCollection = { database().collection() },
            forUser = { this },
        )
    )
    val ws2 = ServerPath("test2").restApiWebsocket<Unit, TestThing, UUID>(
        database,
        storeUser = { "" },
        loadUser = { Unit },
        info = ModelInfo(
            getCollection = { database().collection() },
            forUser = { this },
        ),
        key = TestThing::_id
    )

    init {
        com.lightningkite.lightningdb.prepareModels()
        Settings.populateDefaults()
        runBlocking {
            Tasks.onSettingsReady()
            engine = UnitTestEngine
            Tasks.onEngineReady()
        }
    }
}