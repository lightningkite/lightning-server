package com.lightningkite.lightningserver

import com.lightningkite.lightningdb.collection
import com.lightningkite.lightningdb.test.User
import com.lightningkite.lightningserver.auth.authOptions
import com.lightningkite.lightningserver.auth.oauth.OauthProviderCredentials
import com.lightningkite.lightningserver.auth.oauth.OauthProviderCredentialsApple
import com.lightningkite.lightningserver.auth.old.BaseAuthEndpoints
import com.lightningkite.lightningserver.auth.old.EmailAuthEndpoints
import com.lightningkite.lightningserver.auth.old.UserEmailAccess
import com.lightningkite.lightningserver.auth.old.userEmailAccess
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.db.modelInfo
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.encryption.SecureHasherSettings
import com.lightningkite.lightningserver.engine.LocalEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.sms.SMSSettings
import kotlin.time.Duration
import java.util.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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


    val info = modelInfo<User, User, UUID>(
        getCollection = { database().collection() },
        forUser = { it },
        authOptions = authOptions(),
        serialization = ModelSerializationInfo()
    )
    val emailAccess: UserEmailAccess<User, UUID> = info.userEmailAccess { User(email = it, phoneNumber = it) }
    val path = ServerPath("auth")
    val baseAuth = BaseAuthEndpoints(path, emailAccess, jwtSigner, 1.hours, 5.minutes)
    val emailAuth = EmailAuthEndpoints(baseAuth, emailAccess, cache, email)

    init {
        com.lightningkite.lightningdb.test.prepareModels()
        com.lightningkite.lightningdb.prepareModels()
        Settings.populateDefaults()
        engine = LocalEngine(LocalCache)
    }
}