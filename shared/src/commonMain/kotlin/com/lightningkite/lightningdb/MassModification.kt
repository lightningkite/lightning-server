@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.SharedCode
import kotlinx.serialization.Serializable

@Serializable
data class MassModification<T: IsCodableAndHashable>(
    val condition: Condition<T>,
    val modification: Modification<T>
)
