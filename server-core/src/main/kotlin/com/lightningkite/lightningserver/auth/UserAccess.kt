package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.exceptions.ForbiddenException
import kotlinx.serialization.KSerializer

interface UserAccess<USER : Any, ID> {
    val serializer: KSerializer<USER>
    val idSerializer: KSerializer<ID>
    val authInfo: AuthInfo<USER>
    fun id(user: USER): ID
    suspend fun byId(id: ID): USER
    suspend fun anonymous(): USER = throw ForbiddenException("Anonymous users not permitted.")
}

interface UserEmailAccess<USER: Any, ID>: UserAccess<USER, ID> {
    suspend fun byEmail(email: String): USER
}
interface UserPhoneAccess<USER: Any, ID>: UserAccess<USER, ID> {
    suspend fun byPhone(phone: String): USER
}
interface UserPasswordAccess<USER: Any, ID>: UserAccess<USER, ID> {
    suspend fun byUsername(username: String, password: String): USER
    fun hashedPassword(user: USER): String
}