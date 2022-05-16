@file:SharedCode
package com.lightningkite.ktordb

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.khrysalis.SwiftDescendsFrom
import com.lightningkite.khrysalis.SwiftProtocolExtends
import com.lightningkite.khrysalis.fatalError
import kotlinx.serialization.Serializable
import java.util.*

@SwiftProtocolExtends("Codable", "Hashable")
interface HasId<ID: Comparable<ID>> {
    val _id: ID
}

object HasIdFields {
    fun <T: HasId<ID>, ID: Comparable<ID>> _id() = DataClassProperty<T, ID>(
        name = "_id",
        get = { it._id },
        set = { _, _ -> fatalError() },
        compare = compareBy { it._id }
    )
}

@SwiftProtocolExtends("Codable", "Hashable")
interface HasEmail {
    val email: String
}

object HasEmailFields {
    fun <T: HasEmail> email() = DataClassProperty<T, String>(
        name = "email",
        get = { it.email },
        set = { _, _ -> fatalError() },
        compare = compareBy { it.email }
    )
}
