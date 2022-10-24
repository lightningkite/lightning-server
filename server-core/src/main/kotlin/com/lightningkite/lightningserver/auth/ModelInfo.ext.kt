package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.db.ModelInfo
import kotlinx.serialization.KSerializer

fun <T, USER, ID: Comparable<ID>> T.userEmailAccess(
    newUser: suspend (email: String) -> USER
): UserEmailAccess<USER, ID> where USER : HasId<ID>, T: ModelInfo<USER, USER, ID>, USER: HasEmail {
    val info = this
    return object: UserEmailAccess<USER, ID>{
        override suspend fun byEmail(email: String): USER =
            info.collection().findOne(Condition.OnField(HasEmailFields.email(), Condition.Equal(email))) ?: newUser(email)

        override val serializer: KSerializer<USER>
            get() = info.serialization.serializer
        override val idSerializer: KSerializer<ID>
            get() = info.serialization.idSerializer
        override val authInfo: AuthInfo<USER>
            get() = info.serialization.authInfo

        override suspend fun byId(id: ID): USER = info.collection().get(id)!!

        override fun id(user: USER): ID = user._id
    }
}

fun <T, USER, ID: Comparable<ID>> T.userPhoneAccess(
    newUser: suspend (phone: String) -> USER
): UserPhoneAccess<USER, ID> where USER : HasId<ID>, T: ModelInfo<USER, USER, ID>, USER: HasPhoneNumber {
    val info = this
    return object: UserPhoneAccess<USER, ID>{
        override suspend fun byPhone(phone: String): USER =
            info.collection().findOne(Condition.OnField(HasPhoneNumberFields.phoneNumber(), Condition.Equal(phone))) ?: newUser(phone)

        override val serializer: KSerializer<USER>
            get() = info.serialization.serializer
        override val idSerializer: KSerializer<ID>
            get() = info.serialization.idSerializer
        override val authInfo: AuthInfo<USER>
            get() = info.serialization.authInfo

        override suspend fun byId(id: ID): USER = info.collection().get(id)!!

        override fun id(user: USER): ID = user._id
    }
}

fun <T, USER, ID: Comparable<ID>> T.userEmailPhoneAccess(
    newEmailUser: suspend (email: String) -> USER,
    newPhoneUser: suspend (phone: String) -> USER,
): UserPhoneAccess<USER, ID> where USER : HasId<ID>, T: ModelInfo<USER, USER, ID>, USER: HasPhoneNumber, USER: HasEmail {
    val info = this
    return object: UserPhoneAccess<USER, ID>, UserEmailAccess<USER, ID>{
        override suspend fun byEmail(email: String): USER =
            info.collection().findOne(Condition.OnField(HasEmailFields.email(), Condition.Equal(email))) ?: newEmailUser(email)
        override suspend fun byPhone(phone: String): USER =
            info.collection().findOne(Condition.OnField(HasPhoneNumberFields.phoneNumber(), Condition.Equal(phone))) ?: newPhoneUser(phone)

        override val serializer: KSerializer<USER>
            get() = info.serialization.serializer
        override val idSerializer: KSerializer<ID>
            get() = info.serialization.idSerializer
        override val authInfo: AuthInfo<USER>
            get() = info.serialization.authInfo

        override suspend fun byId(id: ID): USER = info.collection().get(id)!!

        override fun id(user: USER): ID = user._id
    }
}