package com.lightningkite.lightningserver.auth.old

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.AuthOption
import com.lightningkite.lightningserver.auth.AuthType
import com.lightningkite.lightningserver.encryption.secureHash
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import kotlinx.serialization.KSerializer

fun <T, USER, ID : Comparable<ID>> T.userPasswordAccess(
    newUser: suspend (email: String, hashedPassword: String) -> USER
): UserPasswordAccess<USER, ID> where USER : HasId<ID>, USER : HasPassword, T : ModelInfo<USER, ID>, USER : HasEmail {
    val info = this
    return object : UserPasswordAccess<USER, ID> {
        override suspend fun byUsername(username: String, password: String): USER {
            val lowercased = username.lowercase()
            return info.collection().findOne(Condition.OnField(HasEmailFields.email(), Condition.Equal(lowercased)))
                ?: newUser(lowercased, password.secureHash()).let { info.collection().insertOne(it)!! }
        }

        override fun hashedPassword(user: USER): String = user.hashedPassword

        override val serializer: KSerializer<USER>
            get() = info.serialization.serializer
        override val idSerializer: KSerializer<ID>
            get() = info.serialization.idSerializer
        override val authType: AuthType = info.authOptions.find { it != null }!!.type

        override suspend fun byId(id: ID): USER = info.collection().get(id) ?: throw UnauthorizedException()

        override fun id(user: USER): ID = user._id
    }
}

fun <T, USER, ID : Comparable<ID>> T.userEmailAccess(
    newUser: suspend (email: String) -> USER
): UserEmailAccess<USER, ID> where USER : HasId<ID>, T : ModelInfo<USER, ID>, USER : HasEmail {
    val info = this
    return object : UserEmailAccess<USER, ID> {
        override suspend fun byEmail(email: String): USER {
            val lowercased = email.lowercase()
            return info.collection().findOne(Condition.OnField(HasEmailFields.email(), Condition.Equal(lowercased)))
                ?: newUser(lowercased).let { info.collection().insertOne(it)!! }
        }

        override val serializer: KSerializer<USER>
            get() = info.serialization.serializer
        override val idSerializer: KSerializer<ID>
            get() = info.serialization.idSerializer
        override val authType: AuthType = info.authOptions.find { it != null }!!.type

        override suspend fun byId(id: ID): USER = info.collection().get(id) ?: throw UnauthorizedException()

        override fun id(user: USER): ID = user._id
    }
}

fun <T, USER, ID : Comparable<ID>> T.userPhoneAccess(
    newUser: suspend (phone: String) -> USER
): UserPhoneAccess<USER, ID> where USER : HasId<ID>, T : ModelInfo<USER, ID>, USER : HasPhoneNumber {
    val info = this
    return object : UserPhoneAccess<USER, ID> {
        override suspend fun byPhone(phone: String): USER {
            val cleaned = phone.filter { it.isDigit() }
            return info.collection()
                .findOne(Condition.OnField(HasPhoneNumberFields.phoneNumber(), Condition.Equal(cleaned))) ?: newUser(
                cleaned
            ).let { info.collection().insertOne(it)!! }
        }

        override val serializer: KSerializer<USER>
            get() = info.serialization.serializer
        override val idSerializer: KSerializer<ID>
            get() = info.serialization.idSerializer
        override val authType: AuthType = info.authOptions.find { it != null }!!.type

        override suspend fun byId(id: ID): USER = info.collection().get(id) ?: throw UnauthorizedException()

        override fun id(user: USER): ID = user._id
    }
}

fun <T, USER, ID : Comparable<ID>> T.userEmailPhoneAccess(
    newEmailUser: suspend (email: String) -> USER,
    newPhoneUser: suspend (phone: String) -> USER,
): UserPhoneAccess<USER, ID> where USER : HasId<ID>, T : ModelInfo<USER, ID>, USER : HasPhoneNumber, USER : HasEmail {
    val info = this
    return object : UserPhoneAccess<USER, ID>, UserEmailAccess<USER, ID> {
        override suspend fun byEmail(email: String): USER {
            val lowercased = email.lowercase()
            return info.collection().findOne(Condition.OnField(HasEmailFields.email(), Condition.Equal(lowercased)))
                ?: newEmailUser(lowercased).let { info.collection().insertOne(it)!! }
        }

        override suspend fun byPhone(phone: String): USER {
            val cleaned = phone.filter { it.isDigit() }
            return info.collection()
                .findOne(Condition.OnField(HasPhoneNumberFields.phoneNumber(), Condition.Equal(cleaned)))
                ?: newPhoneUser(cleaned).let { info.collection().insertOne(it)!! }
        }

        override val serializer: KSerializer<USER>
            get() = info.serialization.serializer
        override val idSerializer: KSerializer<ID>
            get() = info.serialization.idSerializer
        override val authType: AuthType = info.authOptions.find { it != null }!!.type

        override suspend fun byId(id: ID): USER = info.collection().get(id) ?: throw UnauthorizedException()

        override fun id(user: USER): ID = user._id
    }
}

@JvmName("userEmailAccessMaybe")
fun <T, USER, ID : Comparable<ID>> T.userEmailAccess(
    anonymous: suspend () -> USER,
    newUser: suspend (email: String) -> USER
): UserEmailAccess<USER, ID> where USER : HasId<ID>, T : ModelInfo<USER, ID>, USER : HasMaybeEmail {
    val info = this
    return object : UserEmailAccess<USER, ID> {
        override suspend fun byEmail(email: String): USER {
            val lowercased = email.lowercase()
            return info.collection()
                .findOne(Condition.OnField(HasMaybeEmailFields.email(), Condition.Equal(lowercased))) ?: newUser(
                lowercased
            ).let { info.collection().insertOne(it)!! }
        }

        override val serializer: KSerializer<USER>
            get() = info.serialization.serializer
        override val idSerializer: KSerializer<ID>
            get() = info.serialization.idSerializer
        override val authType: AuthType = info.authOptions.find { it != null }!!.type

        override suspend fun byId(id: ID): USER = info.collection().get(id) ?: throw UnauthorizedException()

        override fun id(user: USER): ID = user._id

        override suspend fun anonymous(): USER = anonymous()
    }
}

@JvmName("userPhoneAccessMaybe")
fun <T, USER, ID : Comparable<ID>> T.userPhoneAccess(
    anonymous: suspend () -> USER,
    newUser: suspend (phone: String) -> USER
): UserPhoneAccess<USER, ID> where USER : HasId<ID>, T : ModelInfo<USER, ID>, USER : HasMaybePhoneNumber {
    val info = this
    return object : UserPhoneAccess<USER, ID> {
        override suspend fun byPhone(phone: String): USER {
            val cleaned = phone.filter { it.isDigit() }
            return info.collection()
                .findOne(Condition.OnField(HasMaybePhoneNumberFields.phoneNumber(), Condition.Equal(cleaned)))
                ?: newUser(cleaned).let { info.collection().insertOne(it)!! }
        }

        override val serializer: KSerializer<USER>
            get() = info.serialization.serializer
        override val idSerializer: KSerializer<ID>
            get() = info.serialization.idSerializer
        override val authType: AuthType = info.authOptions.find { it != null }!!.type

        override suspend fun byId(id: ID): USER = info.collection().get(id) ?: throw UnauthorizedException()

        override fun id(user: USER): ID = user._id

        override suspend fun anonymous(): USER = anonymous()
    }
}

@JvmName("userEmailPhoneAccessMaybe")
fun <T, USER, ID : Comparable<ID>> T.userEmailPhoneAccess(
    anonymous: suspend () -> USER,
    newEmailUser: suspend (email: String) -> USER,
    newPhoneUser: suspend (phone: String) -> USER,
): UserPhoneAccess<USER, ID> where USER : HasId<ID>, T : ModelInfo<USER, ID>, USER : HasMaybePhoneNumber, USER : HasMaybeEmail {
    val info = this
    return object : UserPhoneAccess<USER, ID>, UserEmailAccess<USER, ID> {
        override suspend fun byEmail(email: String): USER {
            val lowercased = email.lowercase()
            return info.collection()
                .findOne(Condition.OnField(HasMaybeEmailFields.email(), Condition.Equal(lowercased))) ?: newEmailUser(
                lowercased
            ).let { info.collection().insertOne(it)!! }
        }

        override suspend fun byPhone(phone: String): USER {
            val cleaned = phone.filter { it.isDigit() }
            return info.collection()
                .findOne(Condition.OnField(HasMaybePhoneNumberFields.phoneNumber(), Condition.Equal(cleaned)))
                ?: newPhoneUser(cleaned).let { info.collection().insertOne(it)!! }
        }

        override val serializer: KSerializer<USER>
            get() = info.serialization.serializer
        override val idSerializer: KSerializer<ID>
            get() = info.serialization.idSerializer
        override val authType: AuthType = info.authOptions.find { it != null }!!.type

        override suspend fun byId(id: ID): USER = info.collection().get(id) ?: throw UnauthorizedException()

        override fun id(user: USER): ID = user._id

        override suspend fun anonymous(): USER = anonymous()
    }
}