package com.lightningkite.lightningserver.auth.old

import com.lightningkite.lightningserver.auth.AuthOption
import com.lightningkite.lightningserver.auth.AuthType
import com.lightningkite.lightningserver.auth.oauth.ExternalProfile
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import kotlinx.serialization.KSerializer

/**
 * Information fo authentication purposes about how to access users, however they are stored.
 */
interface UserAccess<USER : Any, ID> {
    val serializer: KSerializer<USER>
    val idSerializer: KSerializer<ID>
    val authType: AuthType
    fun id(user: USER): ID
    suspend fun byId(id: ID): USER
    suspend fun anonymous(): USER = throw ForbiddenException("Anonymous users not permitted.")
}

interface UserEmailAccess<USER : Any, ID> : UserAccess<USER, ID> {
    suspend fun byEmail(email: String): USER
}

interface UserPhoneAccess<USER : Any, ID> : UserAccess<USER, ID> {
    suspend fun byPhone(phone: String): USER
}

interface UserPasswordAccess<USER : Any, ID> : UserAccess<USER, ID> {
    suspend fun byUsername(username: String, password: String): USER
    fun hashedPassword(user: USER): String
}

interface UserExternalServiceAccess<USER : Any, ID> : UserAccess<USER, ID> {
    suspend fun byExternalService(oauth: ExternalProfile): USER
}

fun <USER : Any, ID> UserEmailAccess<USER, ID>.asExternal(): UserExternalServiceAccess<USER, ID> =
    object : UserExternalServiceAccess<USER, ID>, UserAccess<USER, ID> by this {
        override suspend fun byExternalService(oauth: ExternalProfile): USER {
            return this@asExternal.byEmail(
                oauth.email ?: throw BadRequestException("No verified email found in external service")
            )
        }
    }