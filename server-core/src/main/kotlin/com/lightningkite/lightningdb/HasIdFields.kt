package com.lightningkite.lightningdb

import kotlin.reflect.KProperty1

@Suppress("UNCHECKED_CAST")
object HasIdFields {
    fun <T: HasId<ID>, ID: Comparable<ID>> _id() = HasId<ID>::_id as KProperty1<T, ID>
}
@Suppress("UNCHECKED_CAST")
object HasEmailFields {
    fun <T: HasEmail> email() = HasEmail::email as KProperty1<T, String>
}
@Suppress("UNCHECKED_CAST")
object HasPhoneNumberFields {
    fun <T: HasPhoneNumber> phoneNumber() = HasPhoneNumber::phoneNumber as KProperty1<T, String>
}
@Suppress("UNCHECKED_CAST")
object HasMaybeEmailFields {
    fun <T: HasMaybeEmail> email() = HasMaybeEmail::email as KProperty1<T, String?>
}
@Suppress("UNCHECKED_CAST")
object HasMaybePhoneNumberFields {
    fun <T: HasMaybePhoneNumber> phoneNumber() = HasMaybePhoneNumber::phoneNumber as KProperty1<T, String?>
}
@Suppress("UNCHECKED_CAST")
object HasPasswordFields {
    fun <T: HasPassword> hashedPassword() = HasPassword::hashedPassword as KProperty1<T, String>
}
