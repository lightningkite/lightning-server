package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.OauthProviderCredentials
import com.lightningkite.lightningserver.auth.SecureHasherSettings
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.engine.LocalEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.pubsub.LocalPubSub
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.setting
import java.util.concurrent.ConcurrentHashMap

object TestSettings {
    val database = setting("database", DatabaseSettings("ram"))
    val email = setting("email", EmailSettings("test"))
    val jwtSigner = setting("jwt", SecureHasherSettings())
    val files = setting("files", FilesSettings())
    val oauthGoogle = setting<OauthProviderCredentials?>("oauth-google", null)
    val oauthApple = setting<OauthProviderCredentials?>("oauth-apple", null)
    val oauthGithub = setting<OauthProviderCredentials?>("oauth-github", null)

    init {
        Settings.populateDefaults()
        engine = LocalEngine(LocalCache)
    }
}