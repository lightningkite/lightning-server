@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.lightningdb.HasId
import java.util.*


data class SignalData<Model : HasId<UUID>>(
    val item: Model,
    val created: Boolean,
    val deleted: Boolean,
)