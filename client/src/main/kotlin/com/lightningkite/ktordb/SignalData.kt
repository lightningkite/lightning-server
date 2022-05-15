@file:SharedCode
package com.lightningkite.ktordb

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.ktordb.HasId
import java.util.*


data class SignalData<Model : HasId<UUID>>(
    val item: Model,
    val created: Boolean,
    val deleted: Boolean,
)