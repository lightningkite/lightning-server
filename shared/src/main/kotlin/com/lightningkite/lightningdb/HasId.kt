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

@SwiftProtocolExtends("Codable", "Hashable")
interface HasEmail {
    val email: String
}

@SwiftProtocolExtends("Codable", "Hashable")
interface HasPhoneNumber {
    val phoneNumber: String
}

@SwiftProtocolExtends("Codable", "Hashable")
interface HasMaybeEmail {
    val email: String?
}

@SwiftProtocolExtends("Codable", "Hashable")
interface HasMaybePhoneNumber {
    val phoneNumber: String?
}

@SwiftProtocolExtends("Codable", "Hashable")
interface HasPassword {
    val hashedPassword: String
}

