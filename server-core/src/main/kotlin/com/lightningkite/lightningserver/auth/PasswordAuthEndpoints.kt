package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.cache.CacheInterface
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.email.EmailClient
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.typed.typed
import java.net.URLDecoder
import java.security.SecureRandom
import java.time.Duration

open class PasswordAuthEndpoints<USER : Any, ID>(
    val base: BaseAuthEndpoints<USER, ID>,
    val getByUsername: suspend (String)->USER,
    val getHashedPassword: (USER)->String,
) : ServerPathGroup(base.path) {
    val loginPassword = path("login-password").post.typed(
        summary = "Password Login",
        description = "Log in with a password",
        errorCases = listOf(),
        implementation = { anon: Unit, input: PasswordLogin ->
            val user = getByUsername(input.username)
            if(!input.password.checkHash(getHashedPassword(user)))
                throw BadRequestException(detail = "password-incorrect", message = "Password does not match the account.")
            base.typedHandler.token(user, base.jwtSigner().expiration)
        }
    )
    fun hash(password: String): String = password.secureHash()
}

