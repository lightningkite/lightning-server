@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.*
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.reflect.KProperty1

@SwiftProtocolExtends("Codable", "Hashable")
interface HasId<ID : Comparable<ID>> {
    val _id: ID
}

@Suppress("UNCHECKED_CAST")
object HasIdFields {
    fun <T: HasId<ID>, ID: Comparable<ID>> _id() = HasId<ID>::_id as KProperty1<T, ID>
}

@SwiftProtocolExtends("Codable", "Hashable")
interface HasEmail {
    val email: String
}

@Suppress("UNCHECKED_CAST")
object HasEmailFields {
    fun <T: HasEmail> email() = HasEmail::email as KProperty1<T, String>
}


@SwiftProtocolExtends("Codable", "Hashable")
interface HasPhoneNumber {
    val phoneNumber: String
}

@Suppress("UNCHECKED_CAST")
object HasPhoneNumberFields {
    fun <T: HasPhoneNumber> phoneNumber() = HasPhoneNumber::phoneNumber as KProperty1<T, String>
}

@SwiftProtocolExtends("Codable", "Hashable")
interface HasMaybeEmail {
    val email: String?
}

@Suppress("UNCHECKED_CAST")
object HasMaybeEmailFields {
    fun <T: HasMaybeEmail> email() = HasMaybeEmail::email as KProperty1<T, String?>
}


@SwiftProtocolExtends("Codable", "Hashable")
interface HasMaybePhoneNumber {
    val phoneNumber: String?
}

@Suppress("UNCHECKED_CAST")
object HasMaybePhoneNumberFields {
    fun <T: HasMaybePhoneNumber> phoneNumber() = HasMaybePhoneNumber::phoneNumber as KProperty1<T, String?>
}