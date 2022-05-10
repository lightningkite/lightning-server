@file:SharedCode
package com.lightningkite.ktordb

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.ktordb.HasId


data class SignalData<Model : HasId>(
    val item: Model,
    val created: Boolean,
    val deleted: Boolean,
)