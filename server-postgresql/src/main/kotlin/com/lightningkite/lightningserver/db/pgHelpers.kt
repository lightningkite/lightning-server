package com.lightningkite.lightningserver.db

import com.lightningkite.serialization.DataClassPathPartial
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer


val DataClassPathPartial<*>.colName: String get() = properties.joinToString("__") { it.name }
