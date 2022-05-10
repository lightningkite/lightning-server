@file:SharedCode
package com.lightningkite.ktordb

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.khrysalis.SwiftDescendsFrom
import com.lightningkite.khrysalis.SwiftProtocolExtends
import com.lightningkite.khrysalis.fatalError
import kotlinx.serialization.Serializable
import java.util.*

@SwiftProtocolExtends("Codable", "Hashable")
interface HasId {
    val _id: UUID

}

object HasIdFields {
    fun <T: HasId> _id() = DataClassProperty<T, UUID>(
        name = "_id",
        get = { it._id },
        set = { _, _ -> fatalError() },
        compare = compareBy { it._id }
    )
}