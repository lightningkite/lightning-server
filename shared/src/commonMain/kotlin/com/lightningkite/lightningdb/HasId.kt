
package com.lightningkite.lightningdb

import kotlinx.serialization.Serializable

interface HasId<ID : Comparable<ID>> {
    val _id: ID
}

interface HasEmail {
    val email: String
}

interface HasPhoneNumber {
    val phoneNumber: String
}

interface HasMaybeEmail {
    val email: String?
}

interface HasMaybePhoneNumber {
    val phoneNumber: String?
}

interface HasPassword {
    val hashedPassword: String
}

