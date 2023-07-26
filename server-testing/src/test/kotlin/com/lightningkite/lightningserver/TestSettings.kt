package com.lightningkite.lightningserver

import com.lightningkite.lightningdb.collection
import com.lightningkite.lightningdb.test.User
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.engine.LocalEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.sms.SMSSettings
import java.util.*

object TestSettings {
    val database = setting("database", DatabaseSettings("ram"))
    val email = setting("email", EmailSettings("test"))
    val sms = setting("sms", SMSSettings("test"))
    val jwtSigner = setting("jwt", JwtSigner())
    val cache = setting("cache", CacheSettings())
    val files = setting("files", FilesSettings())
    val oauthGoogle = setting<OauthProviderCredentials?>("oauth_google", null)
    val oauthApple = setting<OauthProviderCredentialsApple?>("oauth_apple", null)
    val oauthGithub = setting<OauthProviderCredentials?>("oauth_github", null)
    val oauthMicrosoft = setting<OauthProviderCredentials?>("oauth_microsoft", null)


    val info = ModelInfo<User, User, UUID>(
        getCollection = { database().collection() },
        forUser = { this }
    )
    val emailAccess: UserEmailAccess<User, UUID> = info.userEmailAccess { User(email = it, phoneNumber = it) }
    val path = ServerPath("auth")
    val baseAuth = BaseAuthEndpoints(path, emailAccess, jwtSigner)
    val emailAuth = EmailAuthEndpoints(baseAuth, emailAccess, cache, email)

    init {
        com.lightningkite.lightningdb.test.prepareModels()
        com.lightningkite.lightningdb.prepareModels()
        Settings.populateDefaults()
        engine = LocalEngine
    }
}