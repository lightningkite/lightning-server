package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.auth.OauthProviderCredentials
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.setting
import java.util.concurrent.ConcurrentHashMap

object TestSettings {
    val database = setting("database", DatabaseSettings())
    val email = setting("email", EmailSettings())
    val jwtSigner = setting("jwt", JwtSigner())
    val files = setting("files", FilesSettings())
    val oauthGoogle = setting<OauthProviderCredentials?>("oauth-google", null)
    val oauthApple = setting<OauthProviderCredentials?>("oauth-apple", null)
    val oauthGithub = setting<OauthProviderCredentials?>("oauth-github", null)

    init {
        Settings.populateDefaults(mapOf(
            "database" to DatabaseSettings("ram")
        ))
    }
}